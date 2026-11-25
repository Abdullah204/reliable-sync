package io.github.abdullah204.reliablesync.acme;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.abdullah204.foundations.sync.SourceRecord;
import java.time.Instant;

/**
 * A customer as Acme's API returns it. Unknown fields are ignored, so a field added by the vendor
 * does not break the sync (the {@code raw} column still captures it).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AcmeCustomer(String id, String name, String email, String country, Instant createdAt, Instant updatedAt)
        implements SourceRecord {

    @Override
    public String externalId() {
        return id;
    }
}
