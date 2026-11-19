package com.aurevia.smartclinic.tenancy;

/**
 * Tenant-owned facility projection returned by the API.
 *
 * @param organizationId owning organization identifier
 * @param code           tenant-local facility code
 * @param name           display name
 */
public record Facility(String organizationId, String code, String name) {
}
