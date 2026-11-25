package io.github.abdullah204.reliablesync.sink;

import io.github.abdullah204.foundations.sync.RecordCodec;
import io.github.abdullah204.foundations.sync.RecordRejectedException;
import io.github.abdullah204.foundations.sync.RecordSink;
import io.github.abdullah204.foundations.sync.UpsertOutcome;
import io.github.abdullah204.reliablesync.acme.AcmeInvoice;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;

/** Idempotent writer for {@code acme.invoice}. Rejects invoices that would corrupt financial reporting. */
public final class InvoiceSink implements RecordSink<AcmeInvoice> {

    private static final String UPSERT = """
            INSERT INTO acme.invoice
                   (external_id, customer_external_id, number, amount, currency, status, due_date,
                    source_updated_at, content_hash, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (external_id) DO UPDATE
               SET customer_external_id = EXCLUDED.customer_external_id,
                   number = EXCLUDED.number,
                   amount = EXCLUDED.amount,
                   currency = EXCLUDED.currency,
                   status = EXCLUDED.status,
                   due_date = EXCLUDED.due_date,
                   source_updated_at = EXCLUDED.source_updated_at,
                   content_hash = EXCLUDED.content_hash,
                   raw = EXCLUDED.raw,
                   last_synced_at = now()
             WHERE acme.invoice.content_hash IS DISTINCT FROM EXCLUDED.content_hash
               AND acme.invoice.source_updated_at <= EXCLUDED.source_updated_at
            RETURNING (xmax = 0)
            """;

    private final RecordCodec<AcmeInvoice> codec;

    public InvoiceSink(RecordCodec<AcmeInvoice> codec) {
        this.codec = codec;
    }

    @Override
    public UpsertOutcome upsert(Connection connection, AcmeInvoice inv) throws SQLException {
        validate(inv);
        String json = codec.encode(inv);
        try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
            int i = 1;
            ps.setString(i++, inv.id());
            ps.setString(i++, inv.customerId());
            ps.setString(i++, inv.number());
            ps.setBigDecimal(i++, inv.amount());
            ps.setString(i++, inv.currency());
            ps.setString(i++, inv.status());
            ps.setDate(i++, inv.dueDate() == null ? null : Date.valueOf(inv.dueDate()));
            ps.setTimestamp(i++, Timestamp.from(inv.updatedAt()));
            ps.setString(i++, Upserts.sha256(json));
            ps.setString(i, json);
            return Upserts.execute(ps);
        }
    }

    private static void validate(AcmeInvoice inv) {
        if (inv.id() == null || inv.id().isBlank()) {
            throw new RecordRejectedException("invoice has no id");
        }
        if (inv.updatedAt() == null) {
            throw new RecordRejectedException("invoice " + inv.id() + " has no updated_at");
        }
        if (inv.customerId() == null || inv.customerId().isBlank()) {
            throw new RecordRejectedException("invoice " + inv.id() + " has no customer_id");
        }
        if (inv.amount() == null || inv.amount().compareTo(BigDecimal.ZERO) < 0) {
            throw new RecordRejectedException("invoice " + inv.id() + " has invalid amount " + inv.amount());
        }
        try {
            Currency.getInstance(inv.currency() == null ? "" : inv.currency());
        } catch (IllegalArgumentException e) {
            throw new RecordRejectedException(
                    "invoice " + inv.id() + " has unknown currency '" + inv.currency() + "' (expected ISO 4217)");
        }
    }
}
