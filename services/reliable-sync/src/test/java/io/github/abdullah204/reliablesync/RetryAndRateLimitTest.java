package io.github.abdullah204.reliablesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.abdullah204.mock.acme.MockData;
import io.github.abdullah204.foundations.sync.RunResult;
import io.github.abdullah204.foundations.sync.RunStatus;
import io.github.abdullah204.foundations.sync.alert.Alert;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Requirement: retry on 429/5xx with backoff, honor Retry-After, alert when a run fails. */
class RetryAndRateLimitTest {

    private SyncHarness h;

    @BeforeEach
    void setUp() {
        h = new SyncHarness(MockData.generate(3, 25, 0, 0), 10, 5);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void honorsRetryAfterOn429() {
        h.mock.chaos().failNext(1, 429, 7);

        RunResult result = h.engine.run(h.customers());

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(h.sleeps).containsExactly(Duration.ofSeconds(7));
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(25);
    }

    @Test
    void retriesTransientServerErrorsWithBoundedBackoff() {
        h.mock.chaos().failNext(1, 503, null).failNext(1, 502, null).failNext(1, 500, null);

        RunResult result = h.engine.run(h.customers());

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(h.sleeps).hasSize(3);
        assertThat(h.sleeps.get(0)).isLessThanOrEqualTo(Duration.ofMillis(200));
        assertThat(h.sleeps.get(1)).isLessThanOrEqualTo(Duration.ofMillis(400));
        assertThat(h.sleeps.get(2)).isLessThanOrEqualTo(Duration.ofMillis(800));
        assertThat(h.mock.requestLog()).filteredOn(l -> l.startsWith("5")).hasSize(3);
        assertThat(h.alerts).isEmpty();
    }

    @Test
    void survivesRealRateLimiting() {
        // 2 requests per window against 3 pages: the 3rd request gets 429 + Retry-After, and the
        // client must actually wait it out (real sleeping here) before the window lets it through.
        h.mock.chaos().rateLimit(2, Duration.ofMillis(500));
        h.reallySleep = true;

        RunResult result = h.engine.run(h.customers());

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(h.mock.requestLog()).anyMatch(line -> line.startsWith("429"));
        assertThat(h.sleeps).allMatch(d -> d.equals(Duration.ofSeconds(1)), "every wait came from Retry-After");
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(25);
    }

    @Test
    void exhaustedRetriesFailTheRunRecordTheErrorAndAlert() {
        h.mock.chaos().failNext(100, 500, null);

        RunResult result = h.engine.run(h.customers());

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.error()).contains("RetriesExhaustedException").contains("HTTP 500");
        assertThat(h.string("SELECT status FROM sync.run WHERE id = " + result.runId())).isEqualTo("FAILED");
        assertThat(h.string("SELECT error_message FROM sync.run WHERE id = " + result.runId())).contains("HTTP 500");
        assertThat(h.alerts).singleElement().satisfies(alert -> {
            assertThat(alert.severity()).isEqualTo(Alert.Severity.ERROR);
            assertThat(alert.job()).isEqualTo("acme-crm/customer");
            assertThat(alert.runId()).isEqualTo(result.runId());
        });
    }
}
