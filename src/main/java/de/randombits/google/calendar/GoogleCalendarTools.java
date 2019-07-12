package de.randombits.google.calendar;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
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
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;
import com.google.common.util.concurrent.RateLimiter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "GoogleCalendarTools", synopsisSubcommandLabel = "COMMAND",
        mixinStandardHelpOptions = true, version = "1.0",
        description = "Carries out different actions against the Google Calendar API.")
public class GoogleCalendarTools implements Callable<Integer> {

    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "Google Calendar Tools";
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR);
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    @Spec
    CommandSpec spec;

    @Option(names = {"-b", "--batchsize"},
            description = "The number of events to process in one API call. Default is ${DEFAULT-VALUE}.")
    private int batchSize = 10;

    @Option(names = {"-c", "--calendar-id"},
            description = "The ID of the calendar to run actions against. Default is ${DEFAULT-VALUE}.")
    private String calendarId = "primary";

    @Option(names = {"-d", "--dry-run"},
            description = "Do not actually manipulate the calendar. Default is ${DEFAULT-VALUE}.")
    private boolean dryRun;

    @Option(names = {"-i", "--interval"},
            description = "Time to wait between API calls. Default is ${DEFAULT-VALUE}.")
    private Duration requestInterval = Duration.ofSeconds(3);

    @Option(names = {"-f", "--client-credentials"}, required = true,
            description = "JSON file with client credentials necessary to access the Google APIs.")
    private File clientCredentialsFile;

    @Option(names = {"-r", "--rate-limit"},
            description = "Rate limit to apply to API calls. A double value for allowed calls per second. Default is ${DEFAULT-VALUE}.")
    private double rateLimit;

    @Option(names = {"-t", "--read-timeout"},
            description = "Read-timeout for API calls in milliseconds. Default is ${DEFAULT-VALUE}.")
    private int readTimeoutInMilliseconds = 5000;

    private static class SearchOptions {
        @Option(names = {"-k", "--keyword"},
                description = "Filters the events to process by this keyword.")
        private String keyword;

        @Option(names = {"--all-day-events"},
                description = "Process  all-day events. Default is ${DEFAULT-VALUE}.")
        private boolean processAllDayEvents = true;

        @Option(names = {"--timed-events"},
                description = "Process timed events (not all-day). Default is ${DEFAULT-VALUE}.")
        private boolean processTimedEvents = true;
    }

    private static class DateRange {
        @Option(names = {"-e", "--end-date"}, required = true,
                description = "Specify the end date. The action will be run for all events between the start date and the end date.")
        Date endDate;

        @Option(names = {"-s", "--start-date"}, required = true,
                description = "Specify the start date. The action will be run for all events between the start date and the end date.")
        Date startDate;
    }

    private Calendar service;
    private String pageToken;
    private RateLimiter rateLimiter;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GoogleCalendarTools()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    @Command(name = "listCalendars", description = "List all calendars of the user.")
    public void commandListCalendars() {
        System.out.println("Listing calendars..");

        initializeService();
        do {
            listCalendars();
        } while (this.pageToken != null);
    }

    @Command(name = "list",
            description = "Lists entries in the calender. This is useful to get a quick overview when used with keyword for example.")
    public void commandListEvents(
            @Option(names = {"-d", "--details"},
                    description = "Show details of the events") boolean showDetails,
            @Mixin DateRange dateRange, @Mixin SearchOptions searchOptions) {

        System.out.println("Listing calendar entries...");

        initializeService();
        do {
            /*
             * use the half of the batch size because two updates are needed to touch them
             */
            Set<Event> events = findEvents(dateRange, this.batchSize, searchOptions);
            listEvents(events, showDetails);
        } while (this.pageToken != null);
        System.out.println("No more events to list.");
    }

    @Command(name = "touch",
            description = "Touches entries in the calender. This is useful to force syncing of older calendar entries to an Android device for example.")
    public void commandTouchEvents(@Mixin DateRange dateRange, @Mixin SearchOptions searchOptions) {
        System.out.println("Touching calendar entries...");

        initializeService();
        do {
            /*
             * use the half of the batch size because two updates are needed to touch them
             */
            int maxResults = batchSize / 2;
            Set<Event> events = findEvents(dateRange, maxResults, searchOptions);
            touchEvents(events);
        } while (this.pageToken != null);
        System.out.println("No more events to touch.");
    }

    @Command(name = "delete",
            description = "Deletes entries from a calendar that match a search term.")
    public void commandDeleteEvents(@Mixin DateRange dateRange,
            @Mixin SearchOptions searchOptions) {
        System.out.println("Deleting calendar entries...");

        initializeService();
        do {
            Set<Event> events = findEvents(dateRange, this.batchSize, searchOptions);
            deleteEvents(events);
        } while (this.pageToken != null);
        System.out.println("No more events to delete.");

    }

    @Command(name = "move", description = "Move entries from one calendar to another one.")
    public void commandMoveEvents(@Mixin DateRange dateRange, @Mixin SearchOptions searchOptions,
            @Option(names = {"-t", "--target-calendar-id"},
                    description = "The ID of the calendar to move events to when the move action is run.") String targetCalendarId) {
        System.out.println("Moving calendar entries...");

        initializeService();
        do {
            Set<Event> events = findEvents(dateRange, this.batchSize, searchOptions);
            moveEvents(events, targetCalendarId);
        } while (this.pageToken != null);
        System.out.println("No more events to move.");

    }

    @Command(name = "removeColor", description = "Remove information about the color of an event.")
    public void commandRemoveColor(@Mixin DateRange dateRange, @Mixin SearchOptions searchOptions,
            @Option(names = {"-c", "--color"},
                    description = "The HEX code of the color to remove. If not sepecified all entries (regradless of color) have the color information removed.") String color) {
        System.out.println("Removing color information from calendar entries...");

        initializeService();

        Pattern patternToRemove = null;
        if (color != null) {
            patternToRemove = Pattern.compile("<font color=\\\"#" + color + "\\\">●</font>");
        } else {
            patternToRemove = Pattern.compile("<font color=\\\"#.*\\\">●</font>");
        }
        do {
            Set<Event> events = findEvents(dateRange, this.batchSize, searchOptions);
            removeColorFromEvents(events, patternToRemove);
        } while (this.pageToken != null);
        System.out.println("No more events to move.");

    }


    private void initializeService() {
        try {
            NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            RetryHttpInitializerWrapper httpRequestInitializer =
                    new RetryHttpInitializerWrapper(getCredentials(httpTransport), 
                            this.readTimeoutInMilliseconds);
            this.service = new Calendar.Builder(httpTransport, JSON_FACTORY, httpRequestInitializer)
                    .setApplicationName(APPLICATION_NAME).build();
            this.rateLimiter = RateLimiter.create(this.rateLimit);
        } catch (GeneralSecurityException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Credential getCredentials(NetHttpTransport netHttpTransport) {
        try (InputStream in = new FileInputStream(this.clientCredentialsFile)) {
            GoogleClientSecrets clientSecrets =
                    GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    netHttpTransport, JSON_FACTORY, clientSecrets, SCOPES).setDataStoreFactory(
                            new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                            .setAccessType("offline").build();
            LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
            return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void listCalendars() {
        try {
            rateLimit(1);

            CalendarList calendars = this.service.calendarList().list().execute();
            calendars.getItems().stream().forEach((calendar) -> System.out.printf("%s - %s\n",
                    calendar.getSummary(), calendar.getId()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void listEvents(Set<Event> events, boolean showDetails) {
        events.stream().forEach((event) -> {
            if (showDetails) {
                try {
                    System.out.println(event.toPrettyString());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            System.out.printf("%s - %s\n", event.getSummary(), getStart(event));
        });
    }

    private void touchEvents(Set<Event> events) {
        JsonBatchCallback<Event> batchCallback = new JsonBatchCallback<Event>() {
            public void onSuccess(Event event, HttpHeaders responseHeaders) {
            }

            public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                throw new RuntimeException("API Call failed due to: " + e);
            }
        };

        BatchRequest batchRequest = this.service.batch();

        try {
            for (Event event : events) {
                Event patch = new Event();
                patch.setSummary(event.getSummary() + " touch");
                System.out.printf("Touching %s  %s (%s)\n", event.getSummary(), getStart(event),
                        event.getId());
                this.service.events().patch(calendarId, event.getId(), patch).queue(batchRequest,
                        batchCallback);
                patch.setSummary(event.getSummary());
                this.service.events().patch(calendarId, event.getId(), patch).queue(batchRequest,
                        batchCallback);
            }
            if (this.dryRun) {
                System.out.println("* Dry-run requested. Not executing API calls");
            } else {
                rateLimit(events.size() * 2);
                batchRequest.execute();
            }
            // System.out.printf("* Sleeping for %d seconds to avoid rate limit being overrun.\n",
            //         this.requestInterval.getSeconds());
        //     Thread.sleep(this.requestInterval.toMillis());
        // } catch (InterruptedException e) {
        //     Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void deleteEvents(Set<Event> events) {
        JsonBatchCallback<Void> batchCallback = new JsonBatchCallback<Void>() {
            public void onSuccess(Void nothing, HttpHeaders responseHeaders) {
            }

            public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                throw new RuntimeException("API Call failed due to: " + e);
            }
        };

        BatchRequest batchRequest = this.service.batch();

        try {
            for (Event event : events) {
                System.out.printf("Deleting %s  %s (%s)\n", event.getSummary(), getStart(event),
                        event.getId());
                this.service.events().delete(calendarId, event.getId()).queue(batchRequest,
                        batchCallback);
            }
            if (this.dryRun) {
                System.out.println("* Dry-run requested. Not executing API calls");
            } else {
                rateLimit(events.size());
                batchRequest.execute();
            }
            // System.out.printf("* Sleeping for %d seconds to avoid rate limit being overrun.\n",
            // this.requestInterval.getSeconds());
            // Thread.sleep(this.requestInterval.toMillis());
            // } catch (InterruptedException e) {
            // Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void moveEvents(Set<Event> events, String targetCalendarId) {
        JsonBatchCallback<Event> batchCallback = new JsonBatchCallback<Event>() {
            public void onSuccess(Event event, HttpHeaders responseHeaders) {
            }

            public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                throw new RuntimeException("API Call failed due to: " + e);
            }
        };

        BatchRequest batchRequest = this.service.batch();

        try {
            for (Event event : events) {
                System.out.printf("Moving %s  %s (%s)\n", event.getSummary(), getStart(event),
                        event.getId());
                this.service.events().move(this.calendarId, event.getId(), targetCalendarId)
                        .queue(batchRequest, batchCallback);
            }
            if (this.dryRun) {
                System.out.println("* Dry-run requested. Not executing API calls");
            } else {
                rateLimit(events.size());
                batchRequest.execute();
            }
            // System.out.printf("* Sleeping for %d seconds to avoid rate limit being overrun.\n",
            // this.requestInterval.getSeconds());
            // Thread.sleep(this.requestInterval.toMillis());
            // } catch (InterruptedException e) {
            // Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void removeColorFromEvents(Set<Event> events, Pattern patternToRemove) {
        JsonBatchCallback<Event> batchCallback = new JsonBatchCallback<Event>() {
            public void onSuccess(Event event, HttpHeaders responseHeaders) {
            }

            public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders) {
                throw new RuntimeException("API Call failed due to: " + e);
            }
        };

        BatchRequest batchRequest = this.service.batch();

        try {
            for (Event event : events) {
                String existingDescription = event.getDescription();
                if (existingDescription == null) {
                    continue;
                }
                String descriptionWithoutColor =
                        patternToRemove.matcher(existingDescription).replaceFirst("");
                Event patch = new Event();
                patch.setDescription(
                        descriptionWithoutColor.length() != 0 ? descriptionWithoutColor : null);
                System.out.printf("Removing color from %s  %s (%s)\n", event.getSummary(),
                        getStart(event), event.getId());
                this.service.events().patch(calendarId, event.getId(), patch).queue(batchRequest,
                        batchCallback);
            }
            if (this.dryRun) {
                System.out.println("* Dry-run requested. Not executing API calls");
            } else {
                rateLimit(events.size());
                batchRequest.execute();
            }
            // System.out.printf("* Sleeping for %d seconds to avoid rate limit being overrun.\n",
            // this.requestInterval.getSeconds());
            // Thread.sleep(this.requestInterval.toMillis());
            // } catch (InterruptedException e) {
            // Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void rateLimit(int permits) {
        double waitTime = this.rateLimiter.acquire(permits);
        if (waitTime > 0.0) {
            System.out.printf("* API call rate limited by waiting %s\n", waitTime);
        }
    }

    private Set<Event> findEvents(DateRange dateRange, int maxResults,
            SearchOptions searchOptions) {
        Set<Event> found = new HashSet<>();
        Events events;

        try {
            Calendar.Events.List request = this.service.events().list(this.calendarId)
                    .setMaxResults(maxResults).setTimeMin(new DateTime(dateRange.startDate))
                    .setTimeMax(new DateTime(dateRange.endDate)).setPageToken(this.pageToken)
                    .setOrderBy("starttime").setSingleEvents(true);
            if (searchOptions != null) {
                request.setQ(searchOptions.keyword);
            }
            rateLimit(1);
            events = request.execute();
            events.getItems().stream().filter((event) -> filterEvent(event, searchOptions))
                    .forEach((event) -> {
                        found.add(event);
                        System.out.printf("%s (%s) (%s)\n", event.getSummary(), getStart(event),
                                event.getId());
                    });
            this.pageToken = events.getNextPageToken();
            System.out.printf("* Found %s calendar entries.\n", found.size());
            return found;
        } catch (NumberFormatException | IOException e) {
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

    private boolean filterEvent(Event event, SearchOptions searchOptions) {

        if (!searchOptions.processAllDayEvents) {
            if (event.getStart().getDateTime() == null) {
                System.out.printf("Ignoring due to filter - %s  %s (%s)\n", event.getSummary(),
                        getStart(event), event.getId());
                return false;
            }
        }
        if (!searchOptions.processTimedEvents) {
            if (event.getStart().getDateTime() != null) {
                System.out.printf("Ignoring due to filter - %s  %s (%s)\n", event.getSummary(),
                        getStart(event), event.getId());
                return false;
            }
        }
        return true;
    }

}
