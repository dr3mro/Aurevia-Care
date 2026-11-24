package com.aurevia.tenancy;

/**
 * Tenant-owned facility projection returned by the API.
 *
 * @param organizationId owning organization identifier
 * @param code           tenant-local facility code
 * @param name           display name
 * @param timeZone       IANA time zone that owns availability windows and display
 * @return this record
 */
public record Facility(String organizationId, String code, String name, String timeZone) {
}
