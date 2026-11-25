package io.github.abdullah204.reliablesync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.abdullah204.mock.acme.MockData;
import io.github.abdullah204.foundations.sync.Page;
import io.github.abdullah204.foundations.sync.RunResult;
import io.github.abdullah204.foundations.sync.RunStatus;
import io.github.abdullah204.foundations.sync.SourceConnector;
import io.github.abdullah204.foundations.sync.SyncJob;
import io.github.abdullah204.reliablesync.acme.AcmeCustomer;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Requirement: after a crash the next run resumes from the persisted cursor instead of starting over. */
class ResumeAfterCrashTest {

    /**
     * Stands in for {@code kill -9}: an {@link Error} is not caught by the engine, so the run row is
     * left RUNNING and nothing after the last commit is written, as in a real process death.
     */
    static final class ProcessDied extends Error {
        ProcessDied() {
            super("simulated process death");
        }
    }

    private SyncHarness h;

    @BeforeEach
    void setUp() {
        h = new SyncHarness(MockData.generate(2, 50, 0, 0), 10, 5);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void resumesFromLastCommittedPageAfterProcessDeath() {
        SyncJob<AcmeCustomer> real = h.customers();
        SyncJob<AcmeCustomer> diesOnThirdPage = withConnector(real, dieOnFetch(real.connector(), 3));

        assertThatThrownBy(() -> h.engine.run(diesOnThirdPage)).isInstanceOf(ProcessDied.class);

        // Two pages (20 records) were committed together with their cursor; nothing else.
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(20);
        assertThat(h.string("SELECT status FROM sync.run ORDER BY id DESC LIMIT 1")).isEqualTo("RUNNING");
        String watermark = h.string("SELECT watermark::text FROM sync.cursor WHERE object_type = 'customer'");
        assertThat(watermark).isNotNull();

        int requestsBefore = h.mock.requestCount();
        RunResult resumed = h.engine.run(real);

        assertThat(resumed.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(50);
        assertThat(h.count("SELECT count(DISTINCT external_id) FROM acme.customer")).isEqualTo(50);
        assertThat(resumed.counts().inserted()).isEqualTo(30);
        assertThat(resumed.counts().fetched()).as("did not start over").isLessThan(50);
        assertThat(h.mock.requestLog().get(requestsBefore)).contains("updated_since=");
        assertThat(h.string("SELECT status FROM sync.run ORDER BY id LIMIT 1")).isEqualTo("ABANDONED");
    }

    @Test
    void failedRunKeepsCommittedProgressAndNextRunCompletes() {
        SyncJob<AcmeCustomer> real = h.customers();
        AtomicInteger calls = new AtomicInteger();
        SourceConnector<AcmeCustomer> flaky = (since, token) -> {
            if (calls.incrementAndGet() == 4) {
                throw new IllegalStateException("source went away");
            }
            return real.connector().fetchPage(since, token);
        };

        RunResult failed = h.engine.run(withConnector(real, flaky));
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.counts().inserted()).isEqualTo(30);
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(30);

        RunResult next = h.engine.run(real);
        assertThat(next.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(next.counts().inserted()).isEqualTo(20);
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(50);
    }

    @Test
    void recordsSharingTheWatermarkTimestampAreNotLostOrDuplicated() {
        // Every third generated record shares its predecessor's timestamp; page size 10 guarantees
        // some page boundaries fall inside a tie. Interrupt after every page to stress resumption.
        SyncJob<AcmeCustomer> real = h.customers();
        for (int i = 0; i < 10; i++) {
            try {
                h.engine.run(withConnector(real, dieOnFetch(real.connector(), 2)));
            } catch (ProcessDied expected) {
                // keep crashing after one page
            }
        }
        h.engine.run(real);

        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(50);
        assertThat(h.count("SELECT count(DISTINCT external_id) FROM acme.customer")).isEqualTo(50);
    }

    private static SourceConnector<AcmeCustomer> dieOnFetch(SourceConnector<AcmeCustomer> delegate, int fetchNumber) {
        AtomicInteger calls = new AtomicInteger();
        return (Instant since, String token) -> {
            if (calls.incrementAndGet() == fetchNumber) {
                throw new ProcessDied();
            }
            Page<AcmeCustomer> page = delegate.fetchPage(since, token);
            return page;
        };
    }

    private static SyncJob<AcmeCustomer> withConnector(SyncJob<AcmeCustomer> job, SourceConnector<AcmeCustomer> c) {
        return new SyncJob<>(job.source(), job.objectType(), c, job.sink(), job.codec());
    }
}
