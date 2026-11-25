package io.github.abdullah204.reliablesync.acme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.abdullah204.foundations.sync.SourceRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** An invoice as Acme's API returns it. Money stays {@link BigDecimal} end to end; never a double. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcmeInvoice(String id, String customerId, String number, BigDecimal amount, String currency,
                          String status, LocalDate dueDate, Instant updatedAt) implements SourceRecord {

    @Override
    public String externalId() {
        return id;
    }
}
