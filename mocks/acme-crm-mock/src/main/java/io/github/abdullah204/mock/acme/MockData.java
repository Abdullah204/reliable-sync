package io.github.abdullah204.mock.acme;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** The mock's in-memory "database". Thread-safe; every method is synchronized (data volume is tiny). */
public final class MockData {

    /** JSON shape of an Acme customer. */
    public record Customer(String id, String name, String email, String country, Instant createdAt, Instant updatedAt) {}

    /** JSON shape of an Acme invoice. */
    public record Invoice(String id, String customerId, String number, BigDecimal amount, String currency,
                          String status, LocalDate dueDate, Instant updatedAt) {}

    static final Comparator<Instant> NATURAL = Comparator.naturalOrder();

    private static final String[] FIRST = {"Amal", "Ben", "Chen", "Dara", "Elif", "Farah", "Gus", "Hana", "Ivo", "Jun"};
    private static final String[] LAST = {"Okafor", "Silva", "Nakamura", "Haddad", "Novak", "Larsen", "Reyes", "Kaur"};
    private static final String[] COUNTRIES = {"US", "GB", "DE", "EG", "AE", "CA", "NL", "SG"};
    private static final String[] CURRENCIES = {"USD", "EUR", "GBP"};
    private static final String[] STATUSES = {"draft", "open", "paid", "void"};

    private final Map<String, Customer> customers = new TreeMap<>();
    private final Map<String, Invoice> invoices = new TreeMap<>();
    private final Random random;
    private int nextCustomer = 1;
    private int nextInvoice = 1;

    private MockData(long seed) {
        this.random = new Random(seed);
    }

    /**
     * Generates a deterministic dataset. Records are spread over time, and every third record shares
     * its predecessor's timestamp, which exercises cursor tie-breaking. Every {@code poisonEvery}-th
     * invoice has an invalid currency, so the sync must dead-letter it (0 disables this).
     */
    public static MockData generate(long seed, int customerCount, int invoiceCount, int poisonEvery) {
        MockData data = new MockData(seed);
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < customerCount; i++) {
            if (i % 3 != 2) {
                t = t.plus(Duration.ofMinutes(1 + data.random.nextInt(30)));
            }
            data.addCustomer(t);
        }
        for (int i = 0; i < invoiceCount; i++) {
            if (i % 3 != 2) {
                t = t.plus(Duration.ofMinutes(1 + data.random.nextInt(30)));
            }
            boolean poison = poisonEvery > 0 && (i + 1) % poisonEvery == 0;
            data.addInvoice(t, poison);
        }
        return data;
    }

    public synchronized Customer addCustomer(Instant at) {
        String id = "cus_%05d".formatted(nextCustomer++);
        String first = FIRST[random.nextInt(FIRST.length)];
        String last = LAST[random.nextInt(LAST.length)];
        Customer c = new Customer(id, first + " " + last,
                (first + "." + last + "." + id.substring(4)).toLowerCase() + "@example.com",
                COUNTRIES[random.nextInt(COUNTRIES.length)], at, at);
        customers.put(id, c);
        return c;
    }

    public synchronized Invoice addInvoice(Instant at, boolean poison) {
        String id = "inv_%06d".formatted(nextInvoice++);
        List<String> customerIds = new ArrayList<>(customers.keySet());
        String customerId = customerIds.isEmpty() ? "cus_00000" : customerIds.get(random.nextInt(customerIds.size()));
        BigDecimal amount = BigDecimal.valueOf(1000 + random.nextInt(500_000), 2).setScale(2, RoundingMode.UNNECESSARY);
        String currency = poison ? "???" : CURRENCIES[random.nextInt(CURRENCIES.length)];
        Invoice inv = new Invoice(id, customerId, "ACME-" + id.substring(4), amount, currency,
                STATUSES[random.nextInt(STATUSES.length)],
                LocalDate.ofInstant(at, ZoneOffset.UTC).plusDays(30), at);
        invoices.put(id, inv);
        return inv;
    }

    public synchronized List<Customer> customers() {
        return List.copyOf(customers.values());
    }

    public synchronized List<Invoice> invoices() {
        return List.copyOf(invoices.values());
    }

    /** Applies {@code change} to an invoice, for example to fix a poison record. The caller sets updatedAt. */
    public synchronized Invoice updateInvoice(String id, UnaryOperator<Invoice> change) {
        Invoice updated = change.apply(invoices.get(id));
        invoices.put(id, updated);
        return updated;
    }

    public synchronized Customer touchCustomer(String id, Instant at) {
        Customer c = customers.get(id);
        Customer updated = new Customer(c.id(), c.name(), c.email(), c.country(), c.createdAt(), at);
        customers.put(id, updated);
        return updated;
    }

    /** Simulates normal CRM activity: edits a few records and creates new ones. Used by the Docker demo. */
    public synchronized void simulateActivity(Instant now) {
        List<String> ids = new ArrayList<>(customers.keySet());
        for (int i = 0; i < 3 && !ids.isEmpty(); i++) {
            touchCustomer(ids.get(random.nextInt(ids.size())), now);
        }
        addCustomer(now);
        addInvoice(now, random.nextInt(25) == 0);
    }

    Random random() {
        return random;
    }
}
