package com.aurevia.smartclinic.catalog;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.prescription.PrescriptionContract.DrugEntry;

/**
 * One route-bearing item in a published medication catalog (RX-01).
 *
 * <p>The item reuses the versioned, market-approved drug entry as the
 * single source of truth for identity, strength, formulation, approval and
 * effective window, addressed as
 * {@link com.aurevia.smartclinic.prescription.PrescriptionContract.DrugEntry}.
 * The catalog adds the approved administration route; it never duplicates the
 * drug's approval lifecycle.</p>
 *
 * @return the validated catalog item
 */
public record MedicationCatalogItem(DrugEntry drug, String route) {

    /**
     * Validate the item's drug and route.
     *
     * @throws ApiException when the drug is missing or the route is blank
     */
    public MedicationCatalogItem {
        if (drug == null) {
            throw new ApiException(422, "INVALID_MEDICATION_ITEM", "a catalog item needs a drug entry");
        }
        if (route == null || route.isBlank()) {
            throw new ApiException(422, "INVALID_MEDICATION_ITEM", "a catalog item needs an approved route");
        }
    }
}
