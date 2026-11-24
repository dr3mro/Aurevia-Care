package com.aurevia.scheduling.followup;

import java.util.Locale;

/**
 * One approved follow-up interval for a visit type (CLN-06).
 *
 * <p>The value comes from the tenant's approved configuration fold; a missing,
 * blank, non-numeric or out-of-range value yields no rule rather than an
 * invented clinical interval. A site must approve the interval before a
 * follow-up date can be suggested from it.</p>
 *
 * @param visitType    tenant-local visit type code
 * @param intervalDays approved number of civil days after the encounter
 * @param configKey    configuration key the value was read from
 * @return this record
 */
public record FollowUpRule(String visitType, int intervalDays, String configKey) {

    /** Largest accepted interval in days. */
    public static final int MAX_INTERVAL_DAYS = 3650;

    /**
     * Configuration key for one visit type.
     *
     * @param visitType tenant-local visit type code
     * @return a bounded lowercase configuration key
     */
    public static String configKey(String visitType) {
        return "followup.interval." + visitType.toLowerCase(Locale.ROOT);
    }

    /**
     * Parse an approved interval value.
     *
     * @param visitType tenant-local visit type code
     * @param raw       configured value, possibly absent or malformed
     * @return the rule, or {@code null} when no safe interval is configured
     */
    public static FollowUpRule from(String visitType, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int days = Integer.parseInt(raw.trim());
            return days >= 1 && days <= MAX_INTERVAL_DAYS
                    ? new FollowUpRule(visitType, days, configKey(visitType)) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
