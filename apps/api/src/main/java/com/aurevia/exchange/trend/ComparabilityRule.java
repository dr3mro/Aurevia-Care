package com.aurevia.exchange.trend;

import com.aurevia.api.ApiException;

/**
 * A versioned, effective-dated policy declaring when results of a test may be
 * trended together (XCH-07, ADM-01).
 *
 * <p>Clinical threshold and trendable policy values are owned by the site
 * medical director; this slice stores the mechanism and its audit trail. It
 * never invents a clinical rule.</p>
 *
 * @param testCode           test identity the rule governs
 * @param canonicalUnit      the only unit eligible for the numeric trend
 * @param comparabilityGroup assay or method group the rule governs
 * @param trendable          whether clinical trending is permitted
 * @param version            positive policy version assigned by the database
 * @return this record
 */
public record ComparabilityRule(String testCode, String canonicalUnit, String comparabilityGroup,
                                boolean trendable, int version) {

    /**
     * Validate the rule at the trust boundary.
     *
     * @param testCode           test identity the rule governs
     * @param canonicalUnit      the only unit eligible for the numeric trend
     * @param comparabilityGroup assay or method group the rule governs
     * @param trendable          whether clinical trending is permitted
     * @param version            positive policy version assigned by the database
     * @throws ApiException when the identity, unit, group or version is invalid
     */
    public ComparabilityRule {
        if (testCode == null || testCode.isBlank()) {
            throw new ApiException(422, "INVALID_TEST_CODE", "testCode is required");
        }
        if (canonicalUnit == null || canonicalUnit.isBlank()) {
            throw new ApiException(422, "INVALID_UNIT", "canonicalUnit is required");
        }
        if (comparabilityGroup == null || comparabilityGroup.isBlank()) {
            throw new ApiException(422, "INVALID_COMPARABILITY_GROUP", "comparabilityGroup is required");
        }
        if (version < 1) {
            throw new ApiException(422, "INVALID_VERSION", "version must be positive");
        }
    }
}
