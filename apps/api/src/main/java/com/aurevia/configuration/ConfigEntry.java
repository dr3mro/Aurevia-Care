package com.aurevia.configuration;

/**
 * One immutable version of a tenant configuration key, with its approval facts.
 *
 * @param key           tenant-local configuration key
 * @param version       monotonic version allocated by the database
 * @param value         opaque configuration value
 * @param status        {@code pending} before approval, {@code approved} afterwards
 * @param effectiveFrom instant from which an approved version may be effective
 * @param createdBy     user identifier of the author, derived from the actor
 * @param approvedBy    user identifier of the named approver, or {@code null}
 * @param approvedAt    approval instant, or {@code null}
 * @param note          approval note, or {@code null}
 * @return this record
 */
public record ConfigEntry(String key, int version, String value, String status, String effectiveFrom,
                          String createdBy, String approvedBy, String approvedAt, String note) {
}
