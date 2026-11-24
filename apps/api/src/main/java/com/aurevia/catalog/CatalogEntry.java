package com.aurevia.catalog;

/**
 * One effective catalog entry in the active organization (ADM-01).
 *
 * @param family        catalog family, for example {@code diagnosis} or {@code referral_partner}
 * @param code          tenant-local entry code
 * @param version       append-only version number
 * @param value         opaque entry value
 * @param effectiveFrom instant from which the approved entry is effective
 * @param approvedBy    user identifier of the named approver
 * @param approvedAt    approval instant
 * @param note          approval note, or {@code null}
 * @return this record
 */
public record CatalogEntry(String family, String code, int version, String value, String effectiveFrom,
                           String approvedBy, String approvedAt, String note) {
}
