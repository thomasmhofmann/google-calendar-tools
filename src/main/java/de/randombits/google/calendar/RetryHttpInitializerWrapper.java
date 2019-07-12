package de.randombits.google.calendar;

import java.io.IOException;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.util.ExponentialBackOff;

/**
 * RetryHttpInitializerWrapper will automatically retry upon RPC failures, preserving the
 * auto-refresh behavior of the Google Credentials.
 */
public class RetryHttpInitializerWrapper implements HttpRequestInitializer {
    private final Credential wrappedCredential;
    private int readTimeoutInMilliseconds;

    public RetryHttpInitializerWrapper(Credential wrappedCredential, 
            int readTimeoutInMilliseconds) {
        this.wrappedCredential = wrappedCredential;
        this.readTimeoutInMilliseconds = readTimeoutInMilliseconds;
    }

    public void initialize(HttpRequest request) {
        request.setReadTimeout(this.readTimeoutInMilliseconds);
        final HttpUnsuccessfulResponseHandler backoffHandler =
                new HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff());
        request.setInterceptor(wrappedCredential);
        request.setUnsuccessfulResponseHandler(new HttpUnsuccessfulResponseHandler() {
            public boolean handleResponse(final HttpRequest request, final HttpResponse response,
                    final boolean supportsRetry) throws IOException {
                if (wrappedCredential.handleResponse(request, response, supportsRetry)) {
                    /*
                     * If credential decides it can handle it, the return code or message indicated
                     * something specific to authentication, and no backoff is desired.
                     */
                    return true;
                } else if (backoffHandler.handleResponse(request, response, supportsRetry)) {
                    /* Otherwise, we defer to the judgement of our internal backoff handler. */
                    System.out.printf("* Retrying %s\n", request.getUrl().toString());
                    return true;
                } else {
                    return false;
                }
            }
        });
        request.setIOExceptionHandler(new HttpBackOffIOExceptionHandler(new ExponentialBackOff()));
    }
}
