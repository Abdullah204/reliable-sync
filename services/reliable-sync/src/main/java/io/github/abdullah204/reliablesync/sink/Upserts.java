package io.github.abdullah204.reliablesync.sink;

import io.github.abdullah204.foundations.sync.UpsertOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/** Shared helpers for idempotent upsert statements. */
final class Upserts {

    private Upserts() {}

    /**
     * Executes an {@code INSERT … ON CONFLICT DO UPDATE … WHERE … RETURNING (xmax = 0)} statement.
     * PostgreSQL sets {@code xmax = 0} on freshly inserted rows, so the returned flag separates
     * INSERTED from UPDATED. No returned row means the WHERE guard skipped the update.
     */
    static UpsertOutcome execute(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return UpsertOutcome.UNCHANGED;
            }
            return rs.getBoolean(1) ? UpsertOutcome.INSERTED : UpsertOutcome.UPDATED;
        }
    }

    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
