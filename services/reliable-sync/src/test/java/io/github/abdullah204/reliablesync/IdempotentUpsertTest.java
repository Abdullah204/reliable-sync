package io.github.abdullah204.reliablesync;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.abdullah204.mock.acme.MockData;
import io.github.abdullah204.foundations.sync.RunResult;
import io.github.abdullah204.foundations.sync.RunStatus;
import io.github.abdullah204.foundations.sync.store.CursorStore;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Requirement: re-running a sync never creates duplicates. */
class IdempotentUpsertTest {

    private SyncHarness h;

    @BeforeEach
    void setUp() {
        h = new SyncHarness(MockData.generate(1, 37, 0, 0), 10, 5);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void firstRunInsertsEverythingAcrossPages() {
        RunResult result = h.engine.run(h.customers());

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.counts().inserted()).isEqualTo(37);
        assertThat(result.counts().pages()).isEqualTo(4);
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(37);
    }

    @Test
    void fullResyncFromScratchProducesNoDuplicatesAndNoWrites() {
        h.engine.run(h.customers());
        h.db.autoCommit(c -> {
            new CursorStore().reset(c, AcmeJobs.SOURCE, "customer");
            return null;
        });

        RunResult again = h.engine.run(h.customers());

        assertThat(again.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(again.counts().fetched()).isEqualTo(37);
        assertThat(again.counts().inserted()).isZero();
        assertThat(again.counts().updated()).isZero();
        assertThat(again.counts().unchanged()).isEqualTo(37);
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(37);
        assertThat(h.count("SELECT count(DISTINCT external_id) FROM acme.customer")).isEqualTo(37);
    }

    @Test
    void incrementalRunPicksUpOnlyChangesAndUpdatesInPlace() {
        h.engine.run(h.customers());
        h.mock.data().touchCustomer("cus_00005", Instant.parse("2030-01-01T00:00:00Z"));
        h.mock.data().addCustomer(Instant.parse("2030-01-01T00:00:00Z"));

        RunResult incremental = h.engine.run(h.customers());

        assertThat(incremental.counts().inserted()).isEqualTo(1);
        assertThat(incremental.counts().updated()).isEqualTo(1);
        assertThat(incremental.counts().fetched()).as("only changed records are fetched").isLessThanOrEqualTo(3);
        assertThat(h.count("SELECT count(*) FROM acme.customer")).isEqualTo(38);
        assertThat(h.string("SELECT source_updated_at::text FROM acme.customer WHERE external_id = 'cus_00005'"))
                .startsWith("2030-01-01");
    }

    @Test
    void staleVersionNeverOverwritesNewerData() throws Exception {
        h.engine.run(h.customers());
        var newer = h.mock.data().touchCustomer("cus_00001", Instant.parse("2030-06-01T00:00:00Z"));
        h.engine.run(h.customers());

        var older = new io.github.abdullah204.reliablesync.acme.AcmeCustomer(newer.id(), "Old Name", newer.email(),
                newer.country(), newer.createdAt(), Instant.parse("2026-01-01T00:00:00Z"));
        var outcome = h.db.inTransaction(c -> h.customers().sink().upsert(c, older));

        assertThat(outcome).isEqualTo(io.github.abdullah204.foundations.sync.UpsertOutcome.UNCHANGED);
        assertThat(h.string("SELECT name FROM acme.customer WHERE external_id = 'cus_00001'"))
                .isEqualTo(newer.name());
    }
}
