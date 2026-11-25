package io.github.abdullah204.reliablesync;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.abdullah204.mock.acme.AcmeCrmMock;
import io.github.abdullah204.mock.acme.MockData;
import io.github.abdullah204.foundations.jdbc.Database;
import io.github.abdullah204.foundations.http.ResilientHttpClient;
import io.github.abdullah204.foundations.commons.retry.RetryPolicy;
import io.github.abdullah204.foundations.commons.retry.Sleeper;
import io.github.abdullah204.foundations.sync.SyncEngine;
import io.github.abdullah204.foundations.sync.SyncJob;
import io.github.abdullah204.foundations.sync.SyncSchema;
import io.github.abdullah204.foundations.sync.SyncSettings;
import io.github.abdullah204.foundations.sync.alert.Alert;
import io.github.abdullah204.foundations.testing.PostgresFixture;
import io.github.abdullah204.reliablesync.acme.AcmeApiClient;
import io.github.abdullah204.reliablesync.acme.AcmeCustomer;
import io.github.abdullah204.reliablesync.acme.AcmeInvoice;
import java.net.http.HttpClient;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wires a real PostgreSQL (Testcontainers), the embedded Acme mock and the real engine. The only
 * fakes are the sleeper, which records delays instead of waiting, and the alerter, which captures
 * alerts.
 */
final class SyncHarness implements AutoCloseable {

    static final String API_KEY = "test-key";

    final Database db;
    final AcmeCrmMock mock;
    final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    /** Off by default so backoff tests run instantly; on for tests that depend on wall-clock windows. */
    volatile boolean reallySleep;
    final List<Alert> alerts = new CopyOnWriteArrayList<>();
    final ObjectMapper mapper = AcmeJobs.mapper();
    final AcmeApiClient client;
    final SyncEngine engine;

    SyncHarness(MockData data, int pageSize, int dlqMaxAttempts) {
        db = PostgresFixture.freshDatabase();
        SyncSchema.migrate(db);
        AcmeJobs.migrate(db);
        mock = AcmeCrmMock.start(0, data, API_KEY);
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(200), Duration.ofSeconds(10), Duration.ofSeconds(60));
        Sleeper recordingSleeper = delay -> {
            sleeps.add(delay);
            if (reallySleep) {
                Thread.sleep(delay);
            }
        };
        var http = new ResilientHttpClient(HttpClient.newHttpClient(), policy, recordingSleeper, Clock.systemUTC(),
                new Random(3));
        client = new AcmeApiClient(mock.baseUri(), API_KEY, pageSize, http, mapper);
        engine = new SyncEngine(db, alerts::add, new SyncSettings(dlqMaxAttempts, true), Clock.systemUTC());
    }

    SyncJob<AcmeCustomer> customers() {
        return AcmeJobs.customers(client, mapper);
    }

    SyncJob<AcmeInvoice> invoices() {
        return AcmeJobs.invoices(client, mapper);
    }

    long count(String sql) {
        return db.autoCommit(c -> {
            try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    String string(String sql) {
        return db.autoCommit(c -> {
            try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getString(1) : null;
            }
        });
    }

    @Override
    public void close() {
        mock.close();
        db.close();
    }
}
