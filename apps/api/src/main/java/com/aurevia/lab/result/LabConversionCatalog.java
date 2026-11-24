package com.aurevia.lab.result;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.aurevia.api.ApiException;

/**
 * Approved, documented conversions available to the laboratory comparability
 * kernel (LAB-02, XCH-07).
 *
 * <p>This is a pure value catalog: the caller supplies only conversions already
 * approved by the site, and the catalog invents no factor of its own. Lookup is
 * exact on test identity and direction, and the highest approved version wins
 * so a superseded document cannot win a tie. A missing entry is returned as
 * empty; it is never a reason to guess a conversion.</p>
 */
public final class LabConversionCatalog {

    private final List<LabUnitConversion> conversions;

    /**
     * Create a catalog from approved documented conversions.
     *
     * @param conversions approved conversions, possibly empty
     * @throws ApiException when the collection is missing
     */
    public LabConversionCatalog(List<LabUnitConversion> conversions) {
        if (conversions == null) {
            throw new ApiException(422, "INVALID_CONVERSION_CATALOG", "conversion collection is required");
        }
        this.conversions = List.copyOf(conversions);
    }

    /**
     * Return an empty catalog, used when a site has approved no conversions.
     *
     * @return a catalog that resolves nothing
     */
    public static LabConversionCatalog none() {
        return new LabConversionCatalog(List.of());
    }

    /**
     * Resolve the newest documented conversion for one exact direction.
     *
     * @param testCode  test identity
     * @param fromUnit  reported source unit
     * @param toUnit    requested target unit
     * @return the effective conversion, or empty when none is documented
     */
    public Optional<LabUnitConversion> find(String testCode, String fromUnit, String toUnit) {
        return conversions.stream()
                .filter(conversion -> conversion.appliesTo(testCode, fromUnit, toUnit))
                .max(Comparator.comparingInt(LabUnitConversion::version));
    }
}
