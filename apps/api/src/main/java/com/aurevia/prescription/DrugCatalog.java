package com.aurevia.prescription;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionContract.AlternativeKind;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Ingredient;

/**
 * Pure, effective-dated fold over a tenant's versioned drug catalog.
 *
 * <p>Only the newest approved entry whose effective window contains the
 * evaluation instant is visible. A future version never applies early and an
 * empty or unapproved catalog returns nothing. Alternatives are always tagged
 * suggestions; this class never mutates or selects a clinician's medication.</p>
 */
public final class DrugCatalog {

    private final List<DrugEntry> entries;

    /**
     * Create a catalog from all stored versions, in any order.
     *
     * @param entries all catalog entries, including unapproved and superseded versions
     * @throws ApiException when the collection is missing
     */
    public DrugCatalog(List<DrugEntry> entries) {
        if (entries == null) {
            throw new ApiException(422, "INVALID_DRUG_CATALOG", "drug catalog entries are required");
        }
        this.entries = List.copyOf(entries);
    }

    /**
     * Search approved, effective entries by generic or trade name and formulation.
     *
     * @param query       case-insensitive name fragment, or {@code null}/blank for all
     * @param formulation exact formulation, or {@code null}/blank for any
     * @param at          evaluation instant
     * @return newest effective entry per catalog key
     */
    public List<DrugEntry> search(String query, String formulation, Instant at) {
        Map<String, DrugEntry> newest = new LinkedHashMap<>();
        for (DrugEntry entry : entries) {
            if (!entry.effectiveAt(at) || !matchesName(entry, query)
                    || !matchesFormulation(entry, formulation)) {
                continue;
            }
            DrugEntry current = newest.get(entry.entryId());
            if (current == null || entry.version() > current.version()) {
                newest.put(entry.entryId(), entry);
            }
        }
        return newest.values().stream().sorted(Comparator.comparing(DrugEntry::entryId))
                .collect(java.util.stream.Collectors.toUnmodifiableList());
    }

    /**
     * Build tagged suggestions for one selected entry.
     *
     * @param selected the clinician's selected entry, never modified
     * @param at       evaluation instant
     * @return suggestions for review; the selected entry itself is excluded
     */
    public List<Alternative> alternativesFor(DrugEntry selected, Instant at) {
        if (selected == null) {
            return List.of();
        }
        List<Alternative> alternatives = new ArrayList<>();
        for (DrugEntry candidate : effectiveEntries(at)) {
            if (candidate.entryId().equals(selected.entryId())) {
                continue;
            }
            if (candidate.genericName().equalsIgnoreCase(selected.genericName())) {
                alternatives.add(new Alternative(AlternativeKind.SUGGESTION, candidate,
                        "same generic name", candidate.approvalProvenance(), true));
            } else if (sharesIdentity(selected, candidate)) {
                alternatives.add(new Alternative(AlternativeKind.SUGGESTION, candidate,
                        "shared ingredient identity", candidate.approvalProvenance(), true));
            }
        }
        alternatives.sort(Comparator.comparing(alternative -> alternative.entry().entryId()));
        return List.copyOf(alternatives);
    }

    private List<DrugEntry> effectiveEntries(Instant at) {
        Map<String, DrugEntry> newest = new LinkedHashMap<>();
        for (DrugEntry entry : entries) {
            if (!entry.effectiveAt(at)) {
                continue;
            }
            DrugEntry current = newest.get(entry.entryId());
            if (current == null || entry.version() > current.version()) {
                newest.put(entry.entryId(), entry);
            }
        }
        return List.copyOf(newest.values());
    }

    private boolean matchesName(DrugEntry entry, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String fragment = query.toLowerCase(java.util.Locale.ROOT);
        return entry.genericName().toLowerCase(java.util.Locale.ROOT).contains(fragment)
                || entry.tradeName().toLowerCase(java.util.Locale.ROOT).contains(fragment);
    }

    private boolean matchesFormulation(DrugEntry entry, String formulation) {
        return formulation == null || formulation.isBlank()
                || entry.formulation().equalsIgnoreCase(formulation);
    }

    private boolean sharesIdentity(DrugEntry first, DrugEntry second) {
        for (Ingredient left : first.ingredients()) {
            for (Ingredient right : second.ingredients()) {
                if (left.substanceId() != null && left.substanceId().equals(right.substanceId())) {
                    return true;
                }
                if (left.group() != null && left.group().equals(right.group())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Resolve one effective catalog entry by its stable key.
     *
     * @param entryId catalog entry key
     * @param at      evaluation instant
     * @return the newest approved entry, or {@code null} when none is effective
     */
    public DrugEntry effectiveEntry(String entryId, Instant at) {
        DrugEntry found = null;
        for (DrugEntry entry : effectiveEntries(at)) {
            if (entry.entryId().equals(entryId)) {
                found = entry;
            }
        }
        return found;
    }

}
