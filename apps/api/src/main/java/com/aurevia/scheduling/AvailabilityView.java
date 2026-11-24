package com.aurevia.scheduling;

/**
 * One weekly availability window in facility local time.
 *
 * @param facilityCode tenant-local facility code
 * @param resourceCode tenant-local resource code
 * @param weekday      ISO-8601 day of week, 1 = Monday
 * @param startLocal   local window start, {@code HH:mm}
 * @param endLocal     local window end, {@code HH:mm}
 * @return this record
 */
public record AvailabilityView(String facilityCode, String resourceCode, int weekday,
                               String startLocal, String endLocal) {
}
