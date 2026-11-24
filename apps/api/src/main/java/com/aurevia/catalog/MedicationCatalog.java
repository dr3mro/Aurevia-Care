package com.aurevia.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.DrugCatalog;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;

/**
 * Pure fold over one published, route-bearing medication catalog (RX-01, ADM-01).
 *
 * <p>The effective-window, approval and name/formulation rules are owned by the
 * existing {@link com.aurevia.prescription.DrugCatalog}; this type
 * adds only the approved route and the published catalog version. The same fold
 * therefore drives both the plain and the versioned search.</p>
 */
public final class MedicationCatalog {

    private final List<MedicationCatalogItem> items;
    private final MedicationCatalogVersion version;

    /**
     * Create a catalog from all stored items and the published version.
     *
     * @param items   all catalog items, in any order and including unapproved versions
     * @param version the published catalog version these items belong to
     * @throws ApiException when a required argument is missing
     */
    public MedicationCatalog(List<MedicationCatalogItem> items, MedicationCatalogVersion version) {
        if (items == null) {
            throw new ApiException(422, "INVALID_MEDICATION_CATALOG", "catalog items are required");
        }
        if (version == null) {
            throw new ApiException(422, "INVALID_MEDICATION_CATALOG", "a catalog version is required");
        }
        this.items = List.copyOf(items);
        this.version = version;
    }

    /**
     * Search approved, effective items by generic or trade name and formulation.
     *
     * @param query       case-insensitive name fragment, or {@code null}/blank for all
     * @param formulation exact formulation, or {@code null}/blank for any
     * @param at          evaluation instant
     * @return the published version together with the newest effective item per key
     */
    public VersionedCatalogSearch search(String query, String formulation, Instant at) {
        List<DrugEntry> drugs = items.stream().map(MedicationCatalogItem::drug).toList();
        Map<String, MedicationCatalogItem> byKey = items.stream().collect(Collectors.toMap(
                item -> key(item.drug()), Function.identity(), (first, second) -> first));
        List<MedicationCatalogItem> hits = new DrugCatalog(drugs).search(query, formulation, at).stream()
                .map(hit -> byKey.get(key(hit)))
                .toList();
        return new VersionedCatalogSearch(version, hits);
    }

    private static String key(DrugEntry drug) {
        return drug.entryId() + ":" + drug.version();
    }
}
