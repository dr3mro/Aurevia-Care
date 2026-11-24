package com.aurevia.scheduling;

/**
 * Facility reference needed by scheduling: identity, tenant-local code and the
 * IANA time zone that owns availability windows.
 *
 * @param id       facility identifier
 * @param code     tenant-local facility code
 * @param timeZone IANA time zone name
 * @return this record
 */
public record FacilityInfo(String id, String code, String timeZone) {
}
