package com.aurevia.exchange.trend;

import java.math.BigDecimal;

/**
 * Incoming observation command (XCH-01/ARC-01 provenance fields included).
 *
 * @param patientId        tenant-local patient identifier
 * @param testCode         test identity
 * @param testDisplay      human-readable test name
 * @param comparabilityGroup assay/method group
 * @param revisionStatus   final, preliminary, corrected or entered_in_error
 * @param value            numeric value, or {@code null}
 * @param unit             source unit
 * @param qualifier        wire qualifier
 * @param referenceLow     lower reference bound
 * @param referenceHigh    upper reference bound
 * @param referenceUnit    reference range unit
 * @param effectiveAt      clinical effective instant as ISO-8601 text
 * @param interpretation   source interpretation
 * @param qualitativeValue coded/text result
 * @param sourceSystem     originating system
 * @param sourceVersion    originating revision/version
 * @return this record
 */
public record ObservationRequest(String patientId, String testCode, String testDisplay,
                                 String comparabilityGroup, String revisionStatus,
                                 BigDecimal value, String unit, String qualifier,
                                 BigDecimal referenceLow, BigDecimal referenceHigh,
                                 String referenceUnit, String effectiveAt, String interpretation,
                                 String qualitativeValue, String sourceSystem, String sourceVersion) {
}
