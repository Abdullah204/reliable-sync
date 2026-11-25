package io.github.abdullah204.reliablesync;

import io.github.abdullah204.foundations.jdbc.DbConfig;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Runtime configuration, read from environment variables (twelve-factor style; secrets never in code).
 *
 * <pre>
 * DB_URL                 jdbc:postgresql://localhost:5432/sync
 * DB_USER / DB_PASSWORD  sync / sync
 * ACME_BASE_URL          http://localhost:8081
 * ACME_API_KEY           demo-key
 * ACME_PAGE_SIZE         100
 * SYNC_INTERVAL_SECONDS  0       0 = run once and exit; otherwise loop forever
 * DLQ_MAX_ATTEMPTS       5
 * ALERT_WEBHOOK_URL      (unset)  optional Slack/Teams/generic webhook for failure alerts
 * </pre>
 */
public record ReliableSyncConfig(
        DbConfig db,
        URI acmeBaseUrl,
        String acmeApiKey,
        int pageSize,
        Duration interval,
        int deadLetterMaxAttempts,
        Optional<URI> alertWebhook) {

    public static ReliableSyncConfig fromEnvironment(Map<String, String> env) {
        return new ReliableSyncConfig(
                new DbConfig(
                        get(env, "DB_URL", "jdbc:postgresql://localhost:5432/sync"),
                        get(env, "DB_USER", "sync"),
                        get(env, "DB_PASSWORD", "sync")),
                URI.create(get(env, "ACME_BASE_URL", "http://localhost:8081")),
                get(env, "ACME_API_KEY", "demo-key"),
                Integer.parseInt(get(env, "ACME_PAGE_SIZE", "100")),
                Duration.ofSeconds(Long.parseLong(get(env, "SYNC_INTERVAL_SECONDS", "0"))),
                Integer.parseInt(get(env, "DLQ_MAX_ATTEMPTS", "5")),
                Optional.ofNullable(env.get("ALERT_WEBHOOK_URL")).filter(s -> !s.isBlank()).map(URI::create));
    }

    private static String get(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
