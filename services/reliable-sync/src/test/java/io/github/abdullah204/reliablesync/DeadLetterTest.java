package io.github.abdullah204.reliablesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.abdullah204.mock.acme.MockData;
import io.github.abdullah204.foundations.sync.RunResult;
import io.github.abdullah204.foundations.sync.RunStatus;
import io.github.abdullah204.foundations.sync.alert.Alert;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Requirement: records that keep failing go to a dead-letter table and never block the rest. */
class DeadLetterTest {

    private static final int INVOICES = 40;
    private static final int POISON_EVERY = 10; // inv_000010, 20, 30, 40 have currency "???"
    private static final int MAX_ATTEMPTS = 3;

    private SyncHarness h;

    @BeforeEach
    void setUp() {
        h = new SyncHarness(MockData.generate(4, 5, INVOICES, POISON_EVERY), 15, MAX_ATTEMPTS);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void badRecordsAreDeadLetteredWhileGoodOnesLand() {
        RunResult result = h.engine.run(h.invoices());

        assertThat(result.status()).isEqualTo(RunStatus.PARTIAL);
        assertThat(result.counts().inserted()).isEqualTo(36);
        assertThat(result.counts().deadLettered()).isEqualTo(4);
        assertThat(h.count("SELECT count(*) FROM acme.invoice")).isEqualTo(36);
        assertThat(h.count("SELECT count(*) FROM sync.dead_letter WHERE status = 'PENDING'")).isEqualTo(4);
        assertThat(h.string("SELECT error_message FROM sync.dead_letter WHERE external_id = 'inv_000010'"))
                .contains("unknown currency '???'");
        assertThat(h.string("SELECT payload->>'currency' FROM sync.dead_letter WHERE external_id = 'inv_000010'"))
                .as("full payload kept for replay and debugging").isEqualTo("???");
        assertThat(h.alerts).singleElement()
                .satisfies(a -> assertThat(a.severity()).isEqualTo(Alert.Severity.WARNING));
    }

    @Test
    void deadLettersAreRetriedAndParkedAfterMaxAttempts() {
        h.engine.run(h.invoices());                   // attempt 1
        RunResult second = h.engine.run(h.invoices()); // attempt 2 (replay)

        assertThat(second.status()).as("no new failures, only retries").isEqualTo(RunStatus.SUCCEEDED);
        assertThat(h.count("SELECT min(attempts) FROM sync.dead_letter")).isEqualTo(2);
        assertThat(h.count("SELECT count(*) FROM sync.dead_letter WHERE status = 'PENDING'")).isEqualTo(4);

        h.engine.run(h.invoices());                   // attempt 3 = MAX_ATTEMPTS
        assertThat(h.count("SELECT count(*) FROM sync.dead_letter WHERE status = 'PARKED'")).isEqualTo(4);

        h.engine.run(h.invoices());                   // parked records are left for a human
        assertThat(h.count("SELECT max(attempts) FROM sync.dead_letter")).isEqualTo(MAX_ATTEMPTS);
        assertThat(h.count("SELECT count(*) FROM sync.dead_letter")).as("one row per record, never duplicated")
                .isEqualTo(4);
    }

    @Test
    void fixingTheRecordAtTheSourceResolvesTheDeadLetter() {
        h.engine.run(h.invoices());
        h.mock.data().updateInvoice("inv_000020", inv -> new MockData.Invoice(inv.id(), inv.customerId(),
                inv.number(), inv.amount(), "EUR", inv.status(), inv.dueDate(),
                Instant.parse("2031-01-01T00:00:00Z")));

        RunResult result = h.engine.run(h.invoices());

        assertThat(result.counts().recovered()).isEqualTo(1);
        assertThat(h.string("SELECT status FROM sync.dead_letter WHERE external_id = 'inv_000020'"))
                .isEqualTo("RESOLVED");
        assertThat(h.string("SELECT currency FROM acme.invoice WHERE external_id = 'inv_000020'")).isEqualTo("EUR");
        assertThat(h.count("SELECT count(*) FROM acme.invoice")).isEqualTo(37);
    }
}
