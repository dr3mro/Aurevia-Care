package com.aurevia.it.prescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.DrugCatalog;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Ingredient;

/** RX-01: only approved, effective, versioned catalog entries are searchable. */
class DrugCatalogTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private static DrugEntry entry(String id, int version, String generic, String trade,
                                   String formulation, String substance, String group,
                                   boolean approved, Instant from, Instant to) {
        return new DrugEntry(id, version, generic, trade, formulation, "500", "mg",
                List.of(new Ingredient(substance, group, 1)), approved, from, to,
                "synthetic-market-v1");
    }

    @Test
    void onlyNewestApprovedEffectiveVersionIsSearchable() {
        DrugCatalog catalog = new DrugCatalog(List.of(
                entry("AMOX", 1, "Amoxicillin", "Amoxil", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        true, FROM, TO),
                entry("AMOX", 3, "Amoxicillin", "Amoxil", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        true, FROM, TO),
                entry("AMOX", 4, "Amoxicillin", "Amoxil", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        false, FROM, TO),
                entry("AMOX", 5, "Amoxicillin", "Amoxil", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        true, AT.plusSeconds(1), TO),
                entry("CIPRO", 2, "Ciprofloxacin", "Ciproxin", "tablet", "CIPRO-SUB", "QUINOLONE",
                        true, FROM, TO)));

        List<DrugEntry> hits = catalog.search("amox", null, AT);
        assertEquals(1, hits.size());
        assertEquals(3, hits.get(0).version(), "superseded and unapproved versions are hidden");
        assertEquals("synthetic-market-v1", hits.get(0).approvalProvenance());

        assertTrue(catalog.search("cipro", "tablet", AT).stream()
                .anyMatch(hit -> "CIPRO".equals(hit.entryId())));
        assertTrue(catalog.search("cipro", "capsule", AT).isEmpty(),
                "the formulation filter is exact");
        assertTrue(catalog.search("unknown-substance", null, AT).isEmpty());
    }

    @Test
    void searchMatchesTradeNameAndListsAllEffectiveEntriesForBlankQuery() {
        DrugCatalog catalog = new DrugCatalog(List.of(
                entry("AMOX", 1, "Amoxicillin", "Amoxil", "tablet", "AMOX-SUB", "BETA_LACTAM",
                        true, FROM, null),
                entry("CIPRO", 1, "Ciprofloxacin", "Ciproxin", "tablet", "CIPRO-SUB", "QUINOLONE",
                        true, FROM, TO)));
        assertEquals(1, catalog.search("AMOXIL", null, AT).size(), "trade names match case-insensitively");
        assertEquals(2, catalog.search(null, null, AT).size());
        assertEquals(2, catalog.search("  ", null, AT).size());
        assertTrue(catalog.search("amox", " ", AT).size() == 1, "blank formulation is no filter");
    }

    @Test
    void alternativesAreTaggedSuggestionsAndNeverReplaceTheSelectedEntry() {
        DrugEntry selected = entry("AMOX-500", 1, "Amoxicillin", "Amoxil", "tablet",
                "AMOX-SUB", "BETA_LACTAM", true, FROM, TO);
        DrugCatalog catalog = new DrugCatalog(List.of(
                selected,
                entry("AMOX-250", 1, "Amoxicillin", "Amoxil", "tablet", "AMOX-SUB",
                        "BETA_LACTAM", true, FROM, TO),
                entry("AMPICILLIN", 1, "Ampicillin", "Ampicil", "capsule", "AMP-SUB",
                        "BETA_LACTAM", true, FROM, TO),
                entry("CIPRO", 1, "Ciprofloxacin", "Ciproxin", "tablet", "CIPRO-SUB",
                        "QUINOLONE", true, FROM, TO),
                new DrugEntry("UNMAPPED", 1, "Unmapped", "Unmapped", "tablet", "1", "mg",
                        List.of(new Ingredient(null, null, 0)), true, FROM, TO, "synthetic-market-v1")));

        List<com.aurevia.prescription.PrescriptionContract.Alternative> alternatives =
                catalog.alternativesFor(selected, AT);
        assertEquals(2, alternatives.size());
        assertTrue(alternatives.stream().allMatch(alternative -> alternative.requiresClinicianSelection()));
        assertTrue(alternatives.stream().allMatch(alternative ->
                "suggestion".equals(alternative.kind().wire())));
        assertTrue(alternatives.stream().noneMatch(alternative ->
                "AMOX-500".equals(alternative.entry().entryId())),
                "the selected entry is never offered as its own alternative");
        assertTrue("AMOX-250".equals(alternatives.get(0).entry().entryId())
                || "AMPICILLIN".equals(alternatives.get(0).entry().entryId()));
        assertFalse(alternatives.stream().anyMatch(alternative ->
                "CIPRO".equals(alternative.entry().entryId())),
                "unrelated groups are not alternatives");
    }

    @Test
    void emptyCatalogFailsClosedInsteadOfInventingEntries() {
        DrugCatalog empty = new DrugCatalog(List.of());
        assertTrue(empty.search("amox", null, AT).isEmpty());
        assertTrue(empty.alternativesFor(
                entry("X", 1, "X", "X", "tablet", "X-SUB", "X-GROUP", true, FROM, TO), AT).isEmpty());
        assertTrue(empty.alternativesFor(null, AT).isEmpty());
    }

    @Test
    void duplicateVersionKeepsTheFirstEntry() {
        DrugCatalog catalog = new DrugCatalog(List.of(
                entry("DUPE", 2, "First", "First", "tablet", "S", "G", true, FROM, TO),
                entry("DUPE", 2, "Second", "Second", "tablet", "S", "G", true, FROM, TO)));
        assertEquals(1, catalog.search(null, null, AT).size());
        assertEquals("First", catalog.search(null, null, AT).get(0).genericName(),
                "an equal version does not replace the entry already folded");
    }

    @Test
    void alternativesResolveIdentityFromSubstanceAndIgnoreInactiveVersions() {
        DrugEntry selected = entry("SELECTED", 1, "Selected", "Selected", "tablet",
                "SHARED-SUB", "OTHER-GROUP", true, FROM, TO);
        DrugCatalog catalog = new DrugCatalog(List.of(
                selected,
                entry("SAME-SUBSTANCE", 1, "Different Generic", "Different Trade", "capsule",
                        "SHARED-SUB", "OTHER-GROUP", true, FROM, TO),
                entry("SAME-SUBSTANCE", 2, "Newer", "Newer", "capsule",
                        "SHARED-SUB", "OTHER-GROUP", true, FROM, TO),
                entry("SAME-SUBSTANCE", 2, "Equal Version", "Equal", "capsule",
                        "SHARED-SUB", "OTHER-GROUP", true, FROM, TO),
                entry("INACTIVE", 1, "Inactive", "Inactive", "tablet", "SHARED-SUB", "OTHER-GROUP",
                        false, FROM, TO),
                entry("FUTURE", 1, "Future", "Future", "tablet", "SHARED-SUB", "OTHER-GROUP",
                        true, AT.plusSeconds(1), TO),
                entry("EXPIRED", 1, "Expired", "Expired", "tablet", "SHARED-SUB", "OTHER-GROUP",
                        true, FROM, AT)));
        List<com.aurevia.prescription.PrescriptionContract.Alternative> alternatives =
                catalog.alternativesFor(selected, AT);
        assertEquals(1, alternatives.size());
        assertEquals("SAME-SUBSTANCE", alternatives.get(0).entry().entryId());
        assertEquals(2, alternatives.get(0).entry().version(),
                "only the newest effective version is suggested");
        assertEquals("shared ingredient identity", alternatives.get(0).reason());
    }

    @Test
    void alternativesHandlePartiallyIdentifiedIngredients() {
        DrugEntry noSubstance = new DrugEntry("NO-SUB", 1, "NoSub", "NoSub", "tablet", "1", "mg",
                List.of(new Ingredient(null, "SHARED-GROUP", 1)), true, FROM, TO, "market");
        DrugCatalog withGroup = new DrugCatalog(List.of(noSubstance,
                entry("GROUP-ONLY", 1, "Different", "Different", "tablet", "S", "SHARED-GROUP",
                        true, FROM, TO)));
        assertEquals(1, withGroup.alternativesFor(noSubstance, AT).size());

        DrugEntry noGroup = new DrugEntry("NO-GROUP", 1, "NoGroup", "NoGroup", "tablet", "1", "mg",
                List.of(new Ingredient("SUB-ONLY", null, 0)), true, FROM, TO, "market");
        DrugCatalog substanceOnly = new DrugCatalog(List.of(noGroup,
                entry("OTHER", 1, "Other", "Other", "tablet", "OTHER-SUB", "A-GROUP",
                        true, FROM, TO)));
        assertTrue(substanceOnly.alternativesFor(noGroup, AT).isEmpty(),
                "an unmappable ingredient cannot establish an alternative relation");
    }

    @Test
    void invalidCatalogInputsAreRejected() {
        assertThrows(ApiException.class, () -> new DrugCatalog(null));
        assertThrows(ApiException.class, () -> new DrugEntry("AMOX", 0, "Amoxicillin", "Amoxil",
                "tablet", "500", "mg", List.of(new Ingredient("S", "G", 1)), true, FROM, TO, "market"));
        assertThrows(ApiException.class, () -> new DrugEntry("AMOX", 1, " ", "Amoxil",
                "tablet", "500", "mg", List.of(new Ingredient("S", "G", 1)), true, FROM, TO, "market"));
        assertThrows(ApiException.class, () -> new DrugEntry("AMOX", 1, "Amoxicillin", "Amoxil",
                "tablet", "500", "mg", List.of(new Ingredient("S", "G", 1)), true, FROM, TO, " "));
        assertThrows(ApiException.class, () -> new DrugEntry("AMOX", 1, "Amoxicillin", "Amoxil",
                "tablet", "500", "mg", null, true, FROM, TO, "market"));
        assertThrows(ApiException.class, () -> new Ingredient(" ", "G", 1));
        assertThrows(ApiException.class, () -> new Ingredient("S", "G", 0));
        assertFalse(entry("A", 1, "A", "A", "tablet", "S", "G", false, FROM, TO).effectiveAt(AT));
        assertFalse(entry("A", 1, "A", "A", "tablet", "S", "G", true, AT.plusSeconds(1), TO)
                .effectiveAt(AT));
        assertFalse(entry("A", 1, "A", "A", "tablet", "S", "G", true, FROM, AT).effectiveAt(AT));
        assertTrue(entry("A", 1, "A", "A", "tablet", "S", "G", true, FROM, TO).effectiveAt(AT));
        assertTrue(entry("A", 1, "A", "A", "tablet", "S", "G", true, FROM, null).effectiveAt(AT));
        assertTrue(entry("A", 1, "A", "A", "tablet", "S", "G", true, FROM, AT.plusSeconds(1))
                .effectiveAt(AT));
    }
}
