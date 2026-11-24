package com.aurevia.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-subject request limiter (WEB-04).
 *
 * <p>Counters are local to a single API instance and are kept in fixed
 * one-minute windows keyed by organization and subject. The edge WAF supplies
 * the global limit (WEB-01); this instance-local guard is defence in depth and
 * holds no durable state. Windows older than two minutes are evicted on every
 * call so a high-cardinality key space cannot grow the map without bound.</p>
 */
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration RETENTION = Duration.ofMinutes(2);

    private final Clock clock;
    private final int perMinute;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Create a limiter.
     *
     * @param clock     injected time source so window boundaries can be pinned in tests
     * @param perMinute maximum calls allowed per key within one minute
     */
    public RateLimiter(Clock clock, int perMinute) {
        this.clock = clock;
        this.perMinute = perMinute;
    }

    /**
     * Count one call and report whether the caller must back off.
     *
     * @param key organization-and-subject identity; never logged
     * @return {@code 0} when the call is allowed and counted, otherwise the whole
     *         number of seconds until the current window ends (always positive)
     */
    public synchronized long retryAfterSeconds(String key) {
        Instant now = clock.instant();
        windows.entrySet().removeIf(entry -> expired(entry.getValue().startedAt, now, RETENTION));
        Window window = windows.get(key);
        if (window == null || expired(window.startedAt, now, WINDOW)) {
            window = new Window(now);
            windows.put(key, window);
        }
        if (window.count >= perMinute) {
            long elapsedSeconds = Duration.between(window.startedAt, now).getSeconds();
            return Math.max(1, WINDOW.getSeconds() - elapsedSeconds);
        }
        window.count++;
        return 0L;
    }

    /**
     * Whether a window that began at {@code startedAt} has already lasted {@code after}.
     *
     * @param startedAt window start
     * @param now       current time
     * @param after     minimum age that counts as expired
     * @return {@code true} when the window is at least {@code after} old
     */
    private static boolean expired(Instant startedAt, Instant now, Duration after) {
        return Duration.between(startedAt, now).compareTo(after) >= 0;
    }

    /** Mutable counter for one key inside a single window. */
    private static final class Window {
        private final Instant startedAt;
        private int count;

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
