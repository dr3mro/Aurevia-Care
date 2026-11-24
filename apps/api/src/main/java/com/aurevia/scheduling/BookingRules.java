package com.aurevia.scheduling;

import java.util.Optional;
import java.util.function.Function;

/**
 * Configurable booking rules read from the tenant's approved configuration
 * (ADM-01), with approved defaults when a setting is absent or unusable.
 *
 * <p>Keys: {@code booking.min_notice_minutes} (0-10080),
 * {@code booking.max_advance_days} (1-3650) and {@code booking.hold_minutes}
 * (1-120). A value that is not an integer in range falls back to the default
 * rather than failing the command, so a misconfigured tenant cannot block
 * booking entirely.</p>
 *
 * @param minNoticeMinutes minimum notice before the slot start
 * @param maxAdvanceDays   maximum distance between today and the slot start
 * @param holdMinutes      lifetime of a temporary hold
 * @return this record
 */
public record BookingRules(long minNoticeMinutes, int maxAdvanceDays, int holdMinutes) {

    /** Default keys owned by this slice. */
    public static final String MIN_NOTICE_KEY = "booking.min_notice_minutes";
    /** Maximum advance booking key. */
    public static final String MAX_ADVANCE_KEY = "booking.max_advance_days";
    /** Hold lifetime key. */
    public static final String HOLD_MINUTES_KEY = "booking.hold_minutes";

    private static final long DEFAULT_MIN_NOTICE = 0L;
    private static final int DEFAULT_MAX_ADVANCE = 365;
    private static final int DEFAULT_HOLD = 15;

    /**
     * Approved defaults used when the tenant has no approved setting.
     *
     * @return default rules
     */
    public static BookingRules defaults() {
        return new BookingRules(DEFAULT_MIN_NOTICE, DEFAULT_MAX_ADVANCE, DEFAULT_HOLD);
    }

    /**
     * Read the effective rules through the configuration fold.
     *
     * @param settings function returning the approved value of one key
     * @return resolved rules
     */
    public static BookingRules from(Function<String, Optional<String>> settings) {
        return new BookingRules(
                parse(settings.apply(MIN_NOTICE_KEY).orElse(null), 0, 10080, (int) DEFAULT_MIN_NOTICE),
                parse(settings.apply(MAX_ADVANCE_KEY).orElse(null), 1, 3650, DEFAULT_MAX_ADVANCE),
                parse(settings.apply(HOLD_MINUTES_KEY).orElse(null), 1, 120, DEFAULT_HOLD));
    }

    /**
     * Parse one bounded integer setting, falling back to the approved default.
     *
     * @param raw      configured text, possibly {@code null}
     * @param minimum  smallest accepted value
     * @param maximum  largest accepted value
     * @param fallback value used when the text is absent or out of range
     * @return accepted value or the fallback
     */
    static int parse(String raw, int minimum, int maximum, int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= minimum && value <= maximum ? value : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
