package io.github.abdullah204.reliablesync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.abdullah204.foundations.jdbc.Database;
import io.github.abdullah204.foundations.http.ResilientHttpClient;
import io.github.abdullah204.foundations.commons.retry.RetryPolicy;
import io.github.abdullah204.foundations.sync.RunResult;
import io.github.abdullah204.foundations.sync.RunStatus;
import io.github.abdullah204.foundations.sync.SyncAlreadyRunningException;
import io.github.abdullah204.foundations.sync.SyncEngine;
import io.github.abdullah204.foundations.sync.SyncJob;
import io.github.abdullah204.foundations.sync.SyncSchema;
import io.github.abdullah204.foundations.sync.SyncSettings;
import io.github.abdullah204.foundations.sync.alert.Alerter;
import io.github.abdullah204.foundations.sync.alert.LoggingAlerter;
import io.github.abdullah204.foundations.sync.alert.WebhookAlerter;
import io.github.abdullah204.foundations.sync.report.RunReport;
import io.github.abdullah204.reliablesync.acme.AcmeApiClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command-line entry point.
 *
 * <pre>
 * reliable-sync sync     # migrate, then sync all object types (once, or every SYNC_INTERVAL_SECONDS)
 * reliable-sync report   # print recent runs and open dead letters
 * </pre>
 *
 * Exit code: 0 when every job succeeded or was partial, 1 when any job failed (cron-friendly).
 */
public final class ReliableSyncMain {

    private static final Logger log = LoggerFactory.getLogger(ReliableSyncMain.class);

    private ReliableSyncMain() {}

    public static void main(String[] args) throws InterruptedException {
        String command = args.length == 0 ? "sync" : args[0];
        ReliableSyncConfig config = ReliableSyncConfig.fromEnvironment(System.getenv());

        try (Database database = Database.connect(config.db())) {
            SyncSchema.migrate(database);
            AcmeJobs.migrate(database);

            switch (command) {
                case "sync" -> System.exit(sync(config, database));
                case "report" -> {
                    RunReport report = new RunReport(database);
                    System.out.println("Recent sync runs\n" + report.recentRuns(20));
                    System.out.println("Open dead letters\n" + report.openDeadLetters(20));
                }
                default -> {
                    System.err.println("Unknown command '" + command + "'. Use: sync | report");
                    System.exit(2);
                }
            }
        }
    }

    private static int sync(ReliableSyncConfig config, Database database) throws InterruptedException {
        ObjectMapper mapper = AcmeJobs.mapper();
        AcmeApiClient client = new AcmeApiClient(config.acmeBaseUrl(), config.acmeApiKey(), config.pageSize(),
                ResilientHttpClient.create(RetryPolicy.defaults()), mapper);

        List<Alerter> alerters = new ArrayList<>(List.of(new LoggingAlerter()));
        config.alertWebhook().ifPresent(url -> alerters.add(WebhookAlerter.to(url)));
        SyncSettings settings = new SyncSettings(config.deadLetterMaxAttempts(), true);
        SyncEngine engine = new SyncEngine(database, Alerter.all(alerters), settings, Clock.systemUTC());
        List<SyncJob<?>> jobs = AcmeJobs.all(client, mapper);

        while (true) {
            boolean anyFailed = false;
            for (SyncJob<?> job : jobs) {
                try {
                    RunResult result = engine.run(job);
                    anyFailed |= result.status() == RunStatus.FAILED;
                } catch (SyncAlreadyRunningException e) {
                    log.warn(e.getMessage());
                }
            }
            if (config.interval().isZero()) {
                return anyFailed ? 1 : 0;
            }
            log.info("Next sync in {}s", config.interval().toSeconds());
            Thread.sleep(config.interval());
        }
    }
}
