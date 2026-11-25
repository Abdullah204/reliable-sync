package io.github.abdullah204.reliablesync.acme;

import io.github.abdullah204.foundations.sync.Page;
import io.github.abdullah204.foundations.sync.SourceConnector;
import io.github.abdullah204.foundations.sync.SourceRecord;
import java.time.Instant;

/** Adapts one Acme list endpoint to the engine's {@link SourceConnector} contract. */
public final class AcmeConnector<T extends SourceRecord> implements SourceConnector<T> {

    private final AcmeApiClient client;
    private final String resource;
    private final Class<T> type;

    public AcmeConnector(AcmeApiClient client, String resource, Class<T> type) {
        this.client = client;
        this.resource = resource;
        this.type = type;
    }

    @Override
    public Page<T> fetchPage(Instant updatedSince, String pageToken) {
        return client.list(resource, type, updatedSince, pageToken);
    }
}
