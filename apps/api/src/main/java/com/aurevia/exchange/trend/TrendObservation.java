package com.aurevia.exchange.trend;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import com.aurevia.api.ApiException;

/**
 * One immutable reported observation with the provenance and display fields
 * required by XCH-07.
 *
 * <p>Effective time is the clinical time and drives trend ordering. Ingestion
 * time is database provenance and never reorders the series. Every displayed
 * value keeps its source unit, the reference range as reported, the source
 * interpretation and the source system/version; the trend engine generates no
 * clinical interpretation of its own.</p>
 *
 * <p>Only a {@code final} revision within an established comparability rule is
 * eligible for the numeric trend. Preliminary, {@code corrected} and
 * {@code entered_in_error} revisions remain visible as exclusions and are never
 * plotted as current values.</p>
 *
 * @return this record
 */
public record TrendObservation(String id, String patientId, String testCode, String testDisplay,
                          String comparabilityGroup, String revisionStatus, BigDecimal value,
                          String unit, Qualifier qualifier, BigDecimal referenceLow,
                          BigDecimal referenceHigh, String referenceUnit, Instant effectiveAt,
                          String interpretation, String qualitativeValue, String sourceSystem,
                          String sourceVersion) {

    /** The only revision state eligible for a current numeric trend. */
    public static final String FINAL = "final";

    private static final Set<String> STATUSES =
            Set.of(FINAL, "preliminary", "corrected", "entered_in_error");

    /**
     * Validate the observation at the trust boundary.
     *
     * @throws ApiException when identity, group, revision, time, value, unit or
     *                      reference range is invalid
     */
    public TrendObservation {
        if (testCode == null || testCode.isBlank()) {
            throw new ApiException(422, "INVALID_TEST_CODE", "testCode is required");
        }
        if (comparabilityGroup == null || comparabilityGroup.isBlank()) {
            throw new ApiException(422, "INVALID_COMPARABILITY_GROUP", "comparabilityGroup is required");
        }
        if (revisionStatus == null || !STATUSES.contains(revisionStatus)) {
            throw new ApiException(422, "INVALID_REVISION_STATUS", "unknown revision status");
        }
        if (effectiveAt == null) {
            throw new ApiException(422, "INVALID_EFFECTIVE_TIME", "effectiveAt is required");
        }
        if (value == null && (qualitativeValue == null || qualitativeValue.isBlank())) {
            throw new ApiException(422, "INVALID_OBSERVATION", "a value or qualitative result is required");
        }
        if (qualifier != Qualifier.NONE && value == null) {
            throw new ApiException(422, "INVALID_QUALIFIER", "a qualified result needs a numeric value");
        }
        if (value != null && (unit == null || unit.isBlank())) {
            throw new ApiException(422, "INVALID_UNIT", "a numeric result needs a source unit");
        }
        if (referenceLow != null && referenceHigh != null
                && referenceLow.compareTo(referenceHigh) > 0) {
            throw new ApiException(422, "INVALID_REFERENCE_RANGE", "reference range is inverted");
        }
        if ((referenceLow != null || referenceHigh != null) && !java.util.Objects.equals(unit, referenceUnit)) {
            throw new ApiException(422, "INVALID_REFERENCE_RANGE_UNIT",
                    "the reference range must use the source unit");
        }
    }
}
