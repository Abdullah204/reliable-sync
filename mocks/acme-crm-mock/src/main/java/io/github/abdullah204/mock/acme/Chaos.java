package io.github.abdullah204.mock.acme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Random;

/**
 * Failure injection for the mock. Three independent mechanisms:
 * <ul>
 *   <li><b>Scripted faults</b>, for deterministic tests: "the next 2 requests return 503".
 *   <li><b>Random 5xx</b> at a configurable rate, for demos and soak testing.
 *   <li><b>Rate limit</b>: a fixed-window limit that answers 429 with an accurate {@code Retry-After}.
 * </ul>
 */
public final class Chaos {

    /** A response the mock should send instead of real data. */
    public record Fault(int status, Integer retryAfterSeconds) {}

    private static final int[] RANDOM_STATUSES = {500, 502, 503};

    private final Clock clock;
    private final Random random;
    private final Deque<Fault> scripted = new ArrayDeque<>();
    private double randomFailureRate;
    private int limitPerWindow;
    private Duration window = Duration.ofSeconds(1);
    private Instant windowStart = Instant.EPOCH;
    private int usedInWindow;

    Chaos(Clock clock, long seed) {
        this.clock = clock;
        this.random = new Random(seed);
    }

    /** The next {@code count} requests fail with {@code status} (and Retry-After, if non-null). */
    public synchronized Chaos failNext(int count, int status, Integer retryAfterSeconds) {
        for (int i = 0; i < count; i++) {
            scripted.add(new Fault(status, retryAfterSeconds));
        }
        return this;
    }

    public synchronized Chaos randomFailureRate(double rate) {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException("rate must be within [0, 1]");
        }
        this.randomFailureRate = rate;
        return this;
    }

    /** Allow at most {@code requests} per {@code window}; 0 disables limiting. */
    public synchronized Chaos rateLimit(int requests, Duration window) {
        this.limitPerWindow = requests;
        this.window = window;
        this.usedInWindow = 0;
        this.windowStart = Instant.EPOCH;
        return this;
    }

    public synchronized void reset() {
        scripted.clear();
        randomFailureRate = 0;
        limitPerWindow = 0;
    }

    /** Decides the fate of one incoming request. Empty means "serve it normally". */
    synchronized Optional<Fault> next() {
        if (limitPerWindow > 0) {
            Instant now = clock.instant();
            if (!now.isBefore(windowStart.plus(window))) {
                windowStart = now;
                usedInWindow = 0;
            }
            if (usedInWindow >= limitPerWindow) {
                long millisLeft = Duration.between(now, windowStart.plus(window)).toMillis();
                int seconds = (int) Math.max(1, (millisLeft + 999) / 1000);
                return Optional.of(new Fault(429, seconds));
            }
            usedInWindow++;
        }
        if (!scripted.isEmpty()) {
            return Optional.of(scripted.poll());
        }
        if (randomFailureRate > 0 && random.nextDouble() < randomFailureRate) {
            return Optional.of(new Fault(RANDOM_STATUSES[random.nextInt(RANDOM_STATUSES.length)], null));
        }
        return Optional.empty();
    }
}
