package com.aurevia.catalog;

import java.util.List;

import com.aurevia.api.ApiException;

/**
 * The result of a versioned medication-catalog search (RX-01).
 *
 * <p>The catalog version is returned with every search so a prescriber can see
 * which market-approved publication produced the hits. The hit list is frozen
 * so a caller cannot rewrite the result after it leaves the domain boundary.</p>
 *
 * @return the validated search result with an immutable hit list
 */
public record VersionedCatalogSearch(MedicationCatalogVersion version, List<MedicationCatalogItem> items) {

    /**
     * Validate the version and freeze the hit list.
     *
     * @throws ApiException when the version or hit list is missing
     */
    public VersionedCatalogSearch {
        if (version == null) {
            throw new ApiException(422, "INVALID_CATALOG_SEARCH", "a search result needs a catalog version");
        }
        if (items == null) {
            throw new ApiException(422, "INVALID_CATALOG_SEARCH", "a search result needs a hit list");
        }
        items = List.copyOf(items);
    }

    /**
     * Return an independent snapshot of the immutable hit list.
     *
     * @return copied hit list
     */
    @Override
    public List<MedicationCatalogItem> items() {
        return List.copyOf(items);
    }
}
