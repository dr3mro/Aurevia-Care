package com.aurevia.lab.result;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.aurevia.api.ApiException;

/**
 * A documented, versioned conversion between two source units of one test
 * identity (LAB-02, XCH-07).
 *
 * <p>The engine never invents a clinical factor. Every conversion must name the
 * approved document that establishes it, carry an explicit version and declare
 * the rounding scale used for the converted value. A conversion is strictly
 * directional: {@code valueInToUnit = valueInFromUnit * factor}. The reverse
 * direction needs its own documented entry, so an undocumented back-conversion
 * can never happen by accident. The original observation is not changed; a
 * caller that joins two units keeps each source value and unit visible beside
 * the converted value.</p>
 *
 * @return this record
 */
public record LabUnitConversion(String testCode, String fromUnit, String toUnit, BigDecimal factor,
                                int scale, int version, String documentation) {

    /**
     * Validate the documented conversion at the trust boundary.
     *
     * @throws ApiException when a unit, identity, factor, scale, version or
     *                      documentation reference is missing or unsound
     */
    public LabUnitConversion {
        if (testCode == null || testCode.isBlank()) {
            throw new ApiException(422, "INVALID_CONVERSION", "a conversion needs a test code");
        }
        if (fromUnit == null || fromUnit.isBlank()) {
            throw new ApiException(422, "INVALID_CONVERSION", "a conversion needs a source unit");
        }
        if (toUnit == null || toUnit.isBlank()) {
            throw new ApiException(422, "INVALID_CONVERSION", "a conversion needs a target unit");
        }
        if (fromUnit.equals(toUnit)) {
            throw new ApiException(422, "INVALID_CONVERSION", "a conversion needs two different units");
        }
        if (factor == null || factor.signum() <= 0) {
            throw new ApiException(422, "INVALID_CONVERSION", "a conversion factor must be positive");
        }
        if (scale < 0) {
            throw new ApiException(422, "INVALID_CONVERSION", "a conversion scale cannot be negative");
        }
        if (version < 1) {
            throw new ApiException(422, "INVALID_CONVERSION", "a conversion version must be positive");
        }
        if (documentation == null || documentation.isBlank()) {
            throw new ApiException(422, "INVALID_CONVERSION",
                    "a conversion must name the approved document that establishes it");
        }
    }

    /**
     * Whether this documented conversion applies to an exact source and target.
     *
     * @param code       test identity to match
     * @param sourceUnit reported source unit
     * @param targetUnit requested target unit
     * @return {@code true} only when all three identities match
     */
    public boolean appliesTo(String code, String sourceUnit, String targetUnit) {
        return testCode.equals(code) && fromUnit.equals(sourceUnit) && toUnit.equals(targetUnit);
    }

    /**
     * Convert one measured value using this document's factor and scale.
     *
     * @param value source value in {@code fromUnit}
     * @return the value expressed in {@code toUnit} at the documented scale
     */
    public BigDecimal convert(BigDecimal value) {
        return value.multiply(factor).setScale(scale, RoundingMode.HALF_UP);
    }
}
