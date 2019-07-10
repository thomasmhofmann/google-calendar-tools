package de.randombits.google.calendar;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;

import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "GoogleCalendarTools", mixinStandardHelpOptions = true, version = "1.0", description = "Carries out different actions against the Google Calendar API.")
public class GoogleCalendarTools implements Callable<Integer> {

    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "Google Calendar Tools";
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR);
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    enum Action {
        touch, cleanByKeyword
    }

    @Option(names = { "-a",
            "--action" }, required = true, paramLabel = "ACTION", description = "The action to run. Valid values are: : ${COMPLETION-CANDIDATES}")
    Action action;

    @Option(names = { "-c",
            "--calendar" }, paramLabel = "CALENDAR_ID", description = "The ID of the calendar to run actions against. Default is ${DEFAULT-VALUE}")
    String calendarId = "primary";

    @Option(names = { "-b",
            "--batchsize" }, paramLabel = "BATCH_SIZE", description = "The number of events to process in one API call. Default is ${DEFAULT-VALUE}")
    int batchSize = 10;

    @Option(names = { "-i",
    "--interval" }, paramLabel = "REQUEST_INTERVAL", description = "Time to wait between API calls. Default is ${DEFAULT-VALUE}")
    Duration requestInterval = Duration.ofSeconds(3);

    @ArgGroup(exclusive = false, multiplicity = "1")
    DateRange dateRange;

    static class DateRange {
        @Option(names = { "-s",
                "--startDate" }, required = true, paramLabel = "START_DATE", description = "Specify the START_DATE. The action will be run for all events between the START_DATE and END_DATE.")
        Date startDate;

        @Option(names = { "-e",
                "--endDate" }, required = true, paramLabel = "END_DATE", description = "Specify the END_DATE. The action will be run for all events between the START_DATE and END_DATE.")
        Date endDate;
    }

    private Calendar service;
    private String pageToken;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GoogleCalendarTools()).execute(args);
        System.exit(exitCode);
    }

    public GoogleCalendarTools() {
        try {
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            this.service = new Calendar.Builder(httpTransport, JSON_FACTORY, getCredentials(httpTransport))
                    .setApplicationName(APPLICATION_NAME).build();
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Credential getCredentials(NetHttpTransport netHttpTransport) {
        InputStream in = GoogleCalendarTools.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
        if (in == null) {
            throw new RuntimeException("Resource not found: " + CREDENTIALS_FILE_PATH);
        }
        try {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(netHttpTransport, JSON_FACTORY,
                    clientSecrets, SCOPES)
                            .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                            .setAccessType("offline").build();
            LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
            return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Integer call() throws Exception {
        switch (this.action) {
        case touch:
            System.out.println("Running Touch");
            do {
                Map<String, Event> toTouch = findEventsToTouch(service, calendarId);
                touchEvents(service, toTouch);
            } while (this.pageToken != null);

            System.out.println("No more events to touch.");
            break;
        case cleanByKeyword:
            System.out.println("Running Clean");
            break;

        default:
            break;
        }
        return 0;
    }

    private Map<String, Event> findEventsToTouch(Calendar service, String calendarId) {
        Map<String, Event> toTouch = new HashMap<>();
        Events events;
        try {
            /* use the half of the batch size because two updates are needed to touch them */
            Calendar.Events.List request = service.events().list(calendarId).setMaxResults(this.batchSize / 2)
                    .setTimeMin(new DateTime(dateRange.startDate)).setTimeMax(new DateTime(dateRange.endDate))
                    .setPageToken(this.pageToken).setOrderBy("startTime").setSingleEvents(true);

            events = request.execute();

            List<Event> items = events.getItems();
            for (Event event : items) {
                toTouch.put(event.getId(), event);
                System.out.printf("%s (%s) (%s)\n", event.getSummary(), getStart(event), event.getId());
            }
            this.pageToken = events.getNextPageToken();
            System.out.printf("* Found %s entries to touch.\n", toTouch.size());
            return toTouch;
        } catch (NumberFormatException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void touchEvents(Calendar service, Map<String, Event> eventsToTouch) {
        JsonBatchCallback<Event> batchCallback = new JsonBatchCallback<Event>() {
            public void onSuccess(Event event, HttpHeaders responseHeaders) {
            }

            public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                throw new RuntimeException("API Call failed due to: " +  e);
            }
        };

        BatchRequest batchRequest = service.batch();

        try {
            for (Entry<String, Event> eventById : eventsToTouch.entrySet()) {
                Event event = eventById.getValue();
                String eventId = eventById.getKey();

                Event patch = new Event();
                patch.setSummary(event.getSummary() + " touch");
                System.out.printf("Touching %s  %s (%s)\n", event.getSummary(), getStart(event), eventId);
                service.events().patch(calendarId, eventId, patch).queue(batchRequest, batchCallback);
                patch.setSummary(event.getSummary());
                service.events().patch(calendarId, eventId, patch).queue(batchRequest, batchCallback);
            }
            batchRequest.execute();
            System.out.printf("* Sleeping for %d seconds to avoid rate limit being overrun.\n", this.requestInterval.getSeconds());
            Thread.sleep(this.requestInterval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    private DateTime getStart(Event event) {
        DateTime start = event.getStart().getDateTime();
        if (start == null) {
            start = event.getStart().getDate();
        }
        return start;
    }

}
