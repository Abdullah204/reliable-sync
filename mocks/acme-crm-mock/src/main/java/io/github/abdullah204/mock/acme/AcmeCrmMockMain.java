package io.github.abdullah204.mock.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone entry point (used by docker-compose). All settings are environment variables:
 *
 * <pre>
 * MOCK_PORT                 8081
 * MOCK_API_KEY              demo-key
 * MOCK_CUSTOMERS            250
 * MOCK_INVOICES             600
 * MOCK_POISON_EVERY         40     every Nth invoice has an invalid currency (0 = never)
 * MOCK_FAILURE_RATE         0.05   probability of a random 500/502/503
 * MOCK_RATE_LIMIT_PER_SEC   10     requests per second before 429 (0 = unlimited)
 * MOCK_ACTIVITY_SECONDS     20     simulate CRM edits every N seconds (0 = static data)
 * </pre>
 */
public final class AcmeCrmMockMain {

    private static final Logger log = LoggerFactory.getLogger(AcmeCrmMockMain.class);

    private AcmeCrmMockMain() {}

    public static void main(String[] args) {
        MockData data = MockData.generate(42L, intEnv("MOCK_CUSTOMERS", 250), intEnv("MOCK_INVOICES", 600),
                intEnv("MOCK_POISON_EVERY", 40));
        AcmeCrmMock mock = AcmeCrmMock.start(intEnv("MOCK_PORT", 8081), data, env("MOCK_API_KEY", "demo-key"));
        mock.chaos()
                .randomFailureRate(Double.parseDouble(env("MOCK_FAILURE_RATE", "0.05")))
                .rateLimit(intEnv("MOCK_RATE_LIMIT_PER_SEC", 10), Duration.ofSeconds(1));

        int activitySeconds = intEnv("MOCK_ACTIVITY_SECONDS", 20);
        if (activitySeconds > 0) {
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(() -> data.simulateActivity(Instant.now()),
                    activitySeconds, activitySeconds, TimeUnit.SECONDS);
            log.info("Simulating CRM activity every {}s", activitySeconds);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(mock::close));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String name, int fallback) {
        return Integer.parseInt(env(name, String.valueOf(fallback)));
    }
}
