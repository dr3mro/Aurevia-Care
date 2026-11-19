package com.aurevia.smartclinic.tenancy;

/** Tenant-owned facility projection returned by the API. */
public record Facility(String organizationId, String code, String name) {
}
