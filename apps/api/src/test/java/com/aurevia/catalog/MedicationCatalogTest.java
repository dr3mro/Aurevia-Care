package com.aurevia.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Ingredient;

/** BUILD-09 (RX-01): the versioned, route-aware medication catalog fold and its value types. */
class MedicationCatalogTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-19T00:00:00Z");

    private static DrugEntry drug(String id, int version, String generic, String trade, String formulation,
                                  boolean approved, Instant from, Instant to) {
        return new DrugEntry(id, version, generic, trade, formulation, "500", "mg",
                List.of(new Ingredient("SUB-" + id, null, 0)), approved, from, to, "synthetic-market-v1");
    }

    private static MedicationCatalogVersion version() {
        return new MedicationCatalogVersion(1, "synthetic-market", FROM, "synthetic-market-v1");
    }

    private static MedicationCatalogItem item(String id, int version, String generic, String trade,
                                              String formulation, String route, boolean approved,
                                              Instant from, Instant to) {
        return new MedicationCatalogItem(drug(id, version, generic, trade, formulation, approved, from, to), route);
    }

    @Test
    void catalogVersionValidatesItsIdentityAndProvenance() {
        MedicationCatalogVersion value = version();
        assertEquals(1, value.version());
        assertEquals("synthetic-market", value.market());
        assertEquals(FROM, value.publishedAt());
        assertEquals("synthetic-market-v1", value.provenance());

        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogVersion(0, "synthetic-market", FROM, "p")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogVersion(1, null, FROM, "p")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogVersion(1, "  ", FROM, "p")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogVersion(1, "synthetic-market", null, "p")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogVersion(1, "synthetic-market", FROM, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogVersion(1, "synthetic-market", FROM, " ")).status());
    }

    @Test
    void medicationItemValidatesItsDrugAndRoute() {
        MedicationCatalogItem value = item("A", 1, "Generic", "Trade", "tablet", "oral", true, FROM, TO);
        assertEquals("A", value.drug().entryId());
        assertEquals("oral", value.route());

        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogItem(null, "oral")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogItem(drug("A", 1, "G", "T", "tablet", true, FROM, TO), null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalogItem(drug("A", 1, "G", "T", "tablet", true, FROM, TO), " ")).status());
    }

    @Test
    void searchResultFreezesItsItemsAgainstCallerMutation() {
        List<MedicationCatalogItem> mutable = new ArrayList<>();
        mutable.add(item("A", 1, "Generic", "Trade", "tablet", "oral", true, FROM, TO));
        VersionedCatalogSearch result = new VersionedCatalogSearch(version(), mutable);
        mutable.clear();
        assertEquals(1, result.items().size());
        assertEquals(version(), result.version());
        assertThrows(UnsupportedOperationException.class, () -> result.items().clear());
        assertEquals(422, assertThrows(ApiException.class, () -> new VersionedCatalogSearch(null, List.of())).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new VersionedCatalogSearch(version(), null)).status());
    }

    @Test
    void catalogRejectsMissingItemsAndVersion() {
        assertEquals(422, assertThrows(ApiException.class, () -> new MedicationCatalog(null, version())).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new MedicationCatalog(List.of(), null)).status());
    }

    @Test
    void searchReturnsOnlyNewestApprovedEffectiveItemsWithTheirRoute() {
        MedicationCatalog catalog = new MedicationCatalog(List.of(
                item("AMOX", 1, "Amoxicillin", "Amoxil", "tablet", "oral", true, FROM, TO),
                item("AMOX", 3, "Amoxicillin", "Amoxil", "tablet", "oral", true, FROM, TO),
                item("AMOX", 4, "Amoxicillin", "Amoxil", "tablet", "oral", false, FROM, TO),
                item("AMOX", 5, "Amoxicillin", "Amoxil", "tablet", "oral", true, AT.plusSeconds(1), TO),
                item("AMOX", 6, "Amoxicillin", "Amoxil", "tablet", "oral", true, FROM, AT),
                item("CIPRO", 2, "Ciprofloxacin", "Ciproxin", "capsule", "oral", true, FROM, null)), version());

        VersionedCatalogSearch result = catalog.search("amox", null, AT);
        assertEquals(1, result.version().version());
        assertEquals(1, result.items().size());
        assertEquals(3, result.items().get(0).drug().version(), "superseded, unapproved, future and retired are hidden");
        assertEquals("oral", result.items().get(0).route());

        assertEquals(1, catalog.search("CIPROXIN", "capsule", AT).items().size(), "trade name and formulation match");
        assertTrue(catalog.search("amox", "capsule", AT).items().isEmpty(), "formulation is an exact filter");
        assertTrue(catalog.search("nothing", null, AT).items().isEmpty());
        assertEquals(2, catalog.search(null, null, AT).items().size(), "blank query and formulation mean any");
        assertEquals(2, catalog.search("  ", " ", AT).items().size());
    }

    @Test
    void equalVersionKeepsTheFirstFoldedItem() {
        MedicationCatalog catalog = new MedicationCatalog(List.of(
                item("DUPE", 2, "First", "First", "tablet", "oral", true, FROM, TO),
                item("DUPE", 2, "Second", "Second", "tablet", "oral", true, FROM, TO)), version());
        List<MedicationCatalogItem> items = catalog.search(null, null, AT).items();
        assertEquals(1, items.size());
        assertEquals("First", items.get(0).drug().genericName());
    }

    @Test
    void emptyCatalogStillReturnsTheVersionItWasBuiltFrom() {
        MedicationCatalog catalog = new MedicationCatalog(List.of(), version());
        assertTrue(catalog.search("amox", null, AT).items().isEmpty());
        assertEquals(1, catalog.search("amox", null, AT).version().version());
    }
}
