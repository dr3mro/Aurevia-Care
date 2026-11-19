package com.aurevia.smartclinic.g2.s_g2_07;

/**
 * Incoming comparability policy command (ADM-01, XCH-07).
 *
 * @param testCode        test identity the rule governs
 * @param canonicalUnit   only unit eligible for the numeric trend
 * @param comparabilityGroup assay/method group the rule governs
 * @param trendable       whether clinical trending is permitted
 * @param effectiveFrom   effective instant as ISO-8601 text, or {@code null}
 *                        for the transaction instant
 */
public record ComparabilityRequest(String testCode, String canonicalUnit, String comparabilityGroup,
                                   Boolean trendable, String effectiveFrom) {
}
