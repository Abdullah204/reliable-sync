package io.github.abdullah204.mock.acme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An embeddable fake of a typical SaaS CRM API, so the sync can be demonstrated and tested
 * offline.
 *
 * <pre>
 * GET /v1/customers?updated_since=2026-01-01T00:00:00Z&amp;limit=50&amp;cursor=...
 * GET /v1/invoices?...
 * Authorization: Bearer &lt;api key&gt;
 *
 * 200 {"data": [...], "next_cursor": "...", "has_more": true}
 * </pre>
 *
 * <p>Results are ordered by {@code (updated_at, id)}, and {@code updated_since} is inclusive,
 * which matches the behaviour of most real CRM and accounting APIs.
 */
public final class AcmeCrmMock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AcmeCrmMock.class);
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final MockData data;
    private final Chaos chaos;
    private final String apiKey;
    private final ObjectMapper json;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<String> requestLog = java.util.Collections.synchronizedList(new ArrayList<>());

    private AcmeCrmMock(HttpServer server, MockData data, String apiKey, Clock clock, long seed) {
        this.server = server;
        this.data = data;
        this.apiKey = apiKey;
        this.chaos = new Chaos(clock, seed);
        this.json = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /** Starts on {@code port} (0 picks a free port). */
    public static AcmeCrmMock start(int port, MockData data, String apiKey) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            AcmeCrmMock mock = new AcmeCrmMock(server, data, apiKey, Clock.systemUTC(), 1234L);
            server.createContext("/v1/customers", ex -> mock.handle(ex, MockData.Customer.class));
            server.createContext("/v1/invoices", ex -> mock.handle(ex, MockData.Invoice.class));
            server.createContext("/health", ex -> mock.respond(ex, 200, Map.of("status", "ok"), null));
            server.setExecutor(mock.executor);
            server.start();
            log.info("Acme CRM mock listening on port {}", mock.port());
            return mock;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start mock on port " + port, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI baseUri() {
        return URI.create("http://localhost:" + port());
    }

    public Chaos chaos() {
        return chaos;
    }

    public MockData data() {
        return data;
    }

    /** Total requests received, including ones answered with an injected fault. */
    public int requestCount() {
        return requestCount.get();
    }

    /** {@code "<status> <path>?<query>"} for every request, oldest first. */
    public List<String> requestLog() {
        synchronized (requestLog) {
            return List.copyOf(requestLog);
        }
    }

    private <T> void handle(HttpExchange exchange, Class<T> type) throws IOException {
        requestCount.incrementAndGet();
        String target = exchange.getRequestURI().getPath()
                + Optional.ofNullable(exchange.getRequestURI().getRawQuery()).map(q -> "?" + q).orElse("");
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, error("method_not_allowed", "Only GET is supported"), null, target);
                return;
            }
            if (!("Bearer " + apiKey).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, error("unauthorized", "Missing or invalid API key"), null, target);
                return;
            }
            Optional<Chaos.Fault> fault = chaos.next();
            if (fault.isPresent()) {
                Chaos.Fault f = fault.get();
                String code = f.status() == 429 ? "rate_limited" : "internal_error";
                respond(exchange, f.status(), error(code, "Injected fault"), f.retryAfterSeconds(), target);
                return;
            }
            Map<String, String> params = query(exchange.getRequestURI());
            respond(exchange, 200, page(type, params), null, target);
        } catch (BadRequest e) {
            respond(exchange, 400, error("invalid_request", e.getMessage()), null, target);
        } catch (RuntimeException e) {
            log.error("Mock failed handling {}", target, e);
            respond(exchange, 500, error("internal_error", "Unexpected mock error"), null, target);
        }
    }

    private Map<String, Object> page(Class<?> type, Map<String, String> params) {
        Instant since = parseInstant(params.get("updated_since"));
        int limit = parseLimit(params.get("limit"));
        Position after = Position.decode(params.get("cursor"));

        List<Position> all = type == MockData.Customer.class
                ? positions(data.customers(), c -> new Position(c.updatedAt(), c.id(), c))
                : positions(data.invoices(), i -> new Position(i.updatedAt(), i.id(), i));

        List<Position> matching = all.stream()
                .filter(p -> since == null || !p.updatedAt().isBefore(since))
                .filter(p -> after == null || Position.ORDER.compare(p, after) > 0)
                .sorted(Position.ORDER)
                .toList();

        List<Position> slice = matching.subList(0, Math.min(limit, matching.size()));
        boolean hasMore = matching.size() > slice.size();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", slice.stream().map(Position::item).toList());
        body.put("next_cursor", hasMore ? slice.getLast().encode() : null);
        body.put("has_more", hasMore);
        return body;
    }

    private static <T> List<Position> positions(List<T> items, Function<T, Position> toPosition) {
        return items.stream().map(toPosition).toList();
    }

    /** Sort key of a record, also used as the opaque pagination cursor. */
    private record Position(Instant updatedAt, String id, Object item) {

        static final Comparator<Position> ORDER =
                Comparator.comparing(Position::updatedAt).thenComparing(Position::id);

        String encode() {
            String raw = updatedAt + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                int bar = raw.indexOf('|');
                return new Position(Instant.parse(raw.substring(0, bar)), raw.substring(bar + 1), null);
            } catch (IllegalArgumentException | DateTimeParseException | StringIndexOutOfBoundsException e) {
                throw new BadRequest("Malformed cursor");
            }
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadRequest("updated_since must be ISO-8601, e.g. 2026-01-01T00:00:00Z");
        }
    }

    private static int parseLimit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new BadRequest("limit must be between 1 and " + MAX_LIMIT);
            }
            return limit;
        } catch (NumberFormatException e) {
            throw new BadRequest("limit must be an integer");
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }

    private void respond(HttpExchange exchange, int status, Object body, Integer retryAfter) throws IOException {
        respond(exchange, status, body, retryAfter, exchange.getRequestURI().getPath());
    }

    private void respond(HttpExchange exchange, int status, Object body, Integer retryAfter, String target)
            throws IOException {
        requestLog.add(status + " " + target);
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", String.valueOf(retryAfter));
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }
}
