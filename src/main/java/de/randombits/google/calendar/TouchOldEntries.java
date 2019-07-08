package de.randombits.google.calendar;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class TouchOldEntries {
    private static final String APPLICATION_NAME = "Google Calendar Cleanup";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    /**
     * Global instance of the scopes required by this app. If modifying these
     * scopes, delete your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    private static final DateTime START_DATE = DateTime.parseRfc3339("2003-12-01T00:00:00");
    private static final DateTime STOP_DATE = DateTime.parseRfc3339("2016-12-31T00:00:00");
    private static String PAGE_TOKEN;

    /**
     * Creates an authorized Credential object.
     * 
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {
        // Load client secrets.
        InputStream in = TouchOldEntries.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY,
                clientSecrets, SCOPES)
                        .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                        .setAccessType("offline").build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static void main(String... args) throws IOException, GeneralSecurityException {
        // Build a new authorized API client service.
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Calendar service = new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                .setApplicationName(APPLICATION_NAME).build();

        String calendarId = "primary";
        do {
            Map<String, Event> toTouch = findEventsToTouch(service, calendarId);
            for (Entry<String, Event> entry: toTouch.entrySet()) {
                touchEvent(service, calendarId, entry.getKey(), entry.getValue());
            }
        } while (PAGE_TOKEN != null);

        System.out.println("No more events to touch.");
    }

    private static void touchEvent(Calendar service, String calendarId, String eventId, Event event) {
        try {
            Event patch = new Event();
            patch.setSummary(event.getSummary() + " touch");
            System.out.printf("Touching %s - %s\n\n", event.getSummary(), eventId);
            service.events().patch(calendarId, eventId, patch).execute();
            patch.setSummary(event.getSummary());
            service.events().patch(calendarId, eventId, patch).execute();
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Event> findEventsToTouch(Calendar service, String calendarId) {
        Map<String, Event> toTouch = new HashMap<>();
        Events events;
        try {
            Calendar.Events.List request = service.events().list(calendarId).setMaxResults(10)
                .setTimeMin(START_DATE)
                .setTimeMax(STOP_DATE)
                .setPageToken(PAGE_TOKEN)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .setQuotaUser("cleanup");
            
            events = request.execute();

            List<Event> items = events.getItems();
            for (Event event : items) {
                DateTime start = event.getStart().getDateTime();
                if (start == null) {
                    start = event.getStart().getDate();
                }
                toTouch.put(event.getId(), event);
                System.out.printf("%s (%s) (%s)\n\n", event.getSummary(), start, event.getId());
            }
            PAGE_TOKEN = events.getNextPageToken();
            System.out.println("Found " + toTouch.size() + " entries to touch.");
            return toTouch;
        } catch (NumberFormatException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}