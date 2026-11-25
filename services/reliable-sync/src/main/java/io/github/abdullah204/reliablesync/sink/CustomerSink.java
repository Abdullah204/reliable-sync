package io.github.abdullah204.reliablesync.sink;

import io.github.abdullah204.foundations.sync.RecordCodec;
import io.github.abdullah204.foundations.sync.RecordRejectedException;
import io.github.abdullah204.foundations.sync.RecordSink;
import io.github.abdullah204.foundations.sync.UpsertOutcome;
import io.github.abdullah204.reliablesync.acme.AcmeCustomer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

/** Idempotent writer for {@code acme.customer}. */
public final class CustomerSink implements RecordSink<AcmeCustomer> {

    // The WHERE clause skips writes that would change nothing (same hash) and stale writes (an older
    // version arriving after a newer one, for example when a dead letter is replayed late).
    private static final String UPSERT = """
            INSERT INTO acme.customer
                   (external_id, name, email, country, source_created_at, source_updated_at, content_hash, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (external_id) DO UPDATE
               SET name = EXCLUDED.name,
                   email = EXCLUDED.email,
                   country = EXCLUDED.country,
                   source_created_at = EXCLUDED.source_created_at,
                   source_updated_at = EXCLUDED.source_updated_at,
                   content_hash = EXCLUDED.content_hash,
                   raw = EXCLUDED.raw,
                   last_synced_at = now()
             WHERE acme.customer.content_hash IS DISTINCT FROM EXCLUDED.content_hash
               AND acme.customer.source_updated_at <= EXCLUDED.source_updated_at
            RETURNING (xmax = 0)
            """;

    private final RecordCodec<AcmeCustomer> codec;

    public CustomerSink(RecordCodec<AcmeCustomer> codec) {
        this.codec = codec;
    }

    @Override
    public UpsertOutcome upsert(Connection connection, AcmeCustomer c) throws SQLException {
        validate(c);
        String json = codec.encode(c);
        try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
            int i = 1;
            ps.setString(i++, c.id());
            ps.setString(i++, c.name().strip());
            ps.setString(i++, c.email() == null ? null : c.email().strip().toLowerCase());
            ps.setString(i++, c.country());
            ps.setTimestamp(i++, c.createdAt() == null ? null : Timestamp.from(c.createdAt()));
            ps.setTimestamp(i++, Timestamp.from(c.updatedAt()));
            ps.setString(i++, Upserts.sha256(json));
            ps.setString(i, json);
            return Upserts.execute(ps);
        }
    }

    private static void validate(AcmeCustomer c) {
        if (c.id() == null || c.id().isBlank()) {
            throw new RecordRejectedException("customer has no id");
        }
        if (c.updatedAt() == null) {
            throw new RecordRejectedException("customer " + c.id() + " has no updated_at");
        }
        if (c.name() == null || c.name().isBlank()) {
            throw new RecordRejectedException("customer " + c.id() + " has no name");
        }
        if (c.country() != null && c.country().length() != 2) {
            throw new RecordRejectedException("customer " + c.id() + " has invalid country '" + c.country() + "'");
        }
    }
}
