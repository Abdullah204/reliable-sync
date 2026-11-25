package io.github.abdullah204.reliablesync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.abdullah204.foundations.jdbc.Database;
import io.github.abdullah204.foundations.sync.RecordCodec;
import io.github.abdullah204.foundations.sync.SyncJob;
import io.github.abdullah204.reliablesync.acme.AcmeApiClient;
import io.github.abdullah204.reliablesync.acme.AcmeConnector;
import io.github.abdullah204.reliablesync.acme.AcmeCustomer;
import io.github.abdullah204.reliablesync.acme.AcmeInvoice;
import io.github.abdullah204.reliablesync.sink.CustomerSink;
import io.github.abdullah204.reliablesync.sink.InvoiceSink;
import java.util.List;

/** Wires Acme's endpoints to their sinks. Adding an object type means adding one job here. */
public final class AcmeJobs {

    public static final String SOURCE = "acme-crm";
    public static final String SCHEMA = "acme";

    private AcmeJobs() {}

    /** JSON mapper matching Acme's snake_case wire format. */
    public static ObjectMapper mapper() {
        return RecordCodec.defaultMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public static void migrate(Database database) {
        database.migrate(SCHEMA, "db/migration/acme");
    }

    public static SyncJob<AcmeCustomer> customers(AcmeApiClient client, ObjectMapper mapper) {
        RecordCodec<AcmeCustomer> codec = RecordCodec.json(mapper, AcmeCustomer.class);
        return new SyncJob<>(SOURCE, "customer",
                new AcmeConnector<>(client, "customers", AcmeCustomer.class), new CustomerSink(codec), codec);
    }

    public static SyncJob<AcmeInvoice> invoices(AcmeApiClient client, ObjectMapper mapper) {
        RecordCodec<AcmeInvoice> codec = RecordCodec.json(mapper, AcmeInvoice.class);
        return new SyncJob<>(SOURCE, "invoice",
                new AcmeConnector<>(client, "invoices", AcmeInvoice.class), new InvoiceSink(codec), codec);
    }

    /** All jobs, in dependency-friendly order (customers before invoices). */
    public static List<SyncJob<?>> all(AcmeApiClient client, ObjectMapper mapper) {
        return List.of(customers(client, mapper), invoices(client, mapper));
    }
}
