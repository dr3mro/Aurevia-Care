package com.aurevia.scheduling;

/**
 * Scheduling resource projection.
 *
 * @param id           resource identifier
 * @param facilityCode tenant-local facility code
 * @param kind         {@code clinician} or {@code room}
 * @param code         tenant-local resource code
 * @param displayName  display name
 * @return this record
 */
public record ResourceView(String id, String facilityCode, String kind, String code, String displayName) {
}
