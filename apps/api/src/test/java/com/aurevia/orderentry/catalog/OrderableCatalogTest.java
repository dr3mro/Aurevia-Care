package com.aurevia.orderentry.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** ORDER-01 (LAB-01, RAD-01, XCH-01): the versioned, per-facility orderable catalog fold. */
class OrderableCatalogTest {

    private static final String ORG_A = "org-synthetic-a";
    private static final String ORG_B = "org-synthetic-b";
    private static final String FACILITY = "facility-main";
    private static final String ANNEX = "facility-annex";
    private static final Instant T0 = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-21T00:00:00Z");

    private static OrderableItem item(String org, String facility, String itemId, int version, String code,
                                      String name, OrderableKind kind, String specimen, String priority,
                                      String partner, Instant from, Instant to, OrderableStatus status) {
        return new OrderableItem(org, facility, itemId, version, code, name, kind, specimen,
                "fasting", priority, partner, from, to, status);
    }

    private static OrderableItem lab(String org, String facility, int version, String code, String name,
                                     Instant from, Instant to, OrderableStatus status) {
        return item(org, facility, "item-" + code, version, code, name, OrderableKind.LAB_TEST,
                "whole-blood", "routine", "partner-lab", from, to, status);
    }

    @Test
    void constructorRejectsNullItems() {
        assertEquals(422, assertThrows(ApiException.class, () -> new OrderableCatalog(null)).status());
    }

    @Test
    void constructorRejectsDuplicateItemVersion() {
        OrderableItem first = lab(ORG_A, FACILITY, 1, "CBC", "Complete Blood Count", T1, null,
                OrderableStatus.ACTIVE);
        assertEquals(422, assertThrows(ApiException.class,
                () -> new OrderableCatalog(List.of(first, first))).status());
    }

    @Test
    void constructorRejectsDuplicateCodeVersionAcrossDifferentItems() {
        OrderableItem first = lab(ORG_A, FACILITY, 1, "CBC", "Complete Blood Count", T1, null,
                OrderableStatus.ACTIVE);
        OrderableItem sameCode = item(ORG_A, FACILITY, "item-other", 1, "CBC", "Other name",
                OrderableKind.LAB_TEST, "plasma", "urgent", "partner-lab", T1, null, OrderableStatus.ACTIVE);
        assertEquals(422, assertThrows(ApiException.class,
                () -> new OrderableCatalog(List.of(first, sameCode))).status());
    }

    @Test
    void catalogFreezesItsInputList() {
        List<OrderableItem> input = new ArrayList<>();
        input.add(lab(ORG_A, FACILITY, 1, "CBC", "Complete Blood Count", T1, null, OrderableStatus.ACTIVE));
        OrderableCatalog catalog = new OrderableCatalog(input);
        input.clear();
        assertEquals(1, catalog.search(ORG_A, FACILITY, null, AT).size());
    }

    @Test
    void searchByCodeFragmentFindsOnlyTheMatchingItem() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "Complete Blood Count", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_A, FACILITY, 1, "BMP", "Basic Metabolic Panel", T1, null, OrderableStatus.ACTIVE)));
        List<OrderableItem> hits = catalog.search(ORG_A, FACILITY, "cb", AT);
        assertEquals(1, hits.size());
        assertEquals("CBC", hits.get(0).code());
    }

    @Test
    void searchByNameFragmentFindsOnlyTheMatchingItem() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "Complete Blood Count", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_A, FACILITY, 1, "BMP", "Basic Metabolic Panel", T1, null, OrderableStatus.ACTIVE)));
        List<OrderableItem> hits = catalog.search(ORG_A, FACILITY, "metabolic", AT);
        assertEquals(1, hits.size());
        assertEquals("BMP", hits.get(0).code());
    }

    @Test
    void blankOrNullQueryReturnsEveryOrderableItemInCodeOrder() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "Complete Blood Count", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_A, FACILITY, 1, "BMP", "Basic Metabolic Panel", T1, null, OrderableStatus.ACTIVE)));
        assertEquals(2, catalog.search(ORG_A, FACILITY, null, AT).size());
        List<OrderableItem> hits = catalog.search(ORG_A, FACILITY, "  ", AT);
        assertEquals(2, hits.size());
        assertEquals("BMP", hits.get(0).code());
        assertEquals("CBC", hits.get(1).code());
    }

    @Test
    void searchWithAnUnknownFragmentReturnsEmpty() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "Complete Blood Count", T1, null, OrderableStatus.ACTIVE)));
        assertTrue(catalog.search(ORG_A, FACILITY, "zzz", AT).isEmpty());
    }

    @Test
    void searchIsScopedToTenantAndFacilityWithIdenticalLocalIds() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "A main", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_A, ANNEX, 1, "CBC", "A annex", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_B, FACILITY, 1, "CBC", "B main", T1, null, OrderableStatus.ACTIVE)));
        assertEquals(1, catalog.search(ORG_A, FACILITY, null, AT).size());
        assertEquals("A main", catalog.search(ORG_A, FACILITY, null, AT).get(0).name());
        assertEquals("A annex", catalog.search(ORG_A, ANNEX, null, AT).get(0).name());
        assertEquals("B main", catalog.search(ORG_B, FACILITY, null, AT).get(0).name());
        assertTrue(catalog.search(ORG_A, "facility-unknown", null, AT).isEmpty());
        assertTrue(catalog.search("org-synthetic-c", FACILITY, null, AT).isEmpty());
    }

    @Test
    void searchExcludesRetiredAndNotYetEffectiveItems() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "Available", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_A, FACILITY, 1, "BMP", "Retired", T1, null, OrderableStatus.RETIRED),
                lab(ORG_A, FACILITY, 1, "LFT", "Future", T2, null, OrderableStatus.ACTIVE)));
        List<OrderableItem> hits = catalog.search(ORG_A, FACILITY, null, AT);
        assertEquals(1, hits.size());
        assertEquals("Available", hits.get(0).name());
    }

    @Test
    void searchFollowsTheEffectiveWindowWhenANewVersionTakesEffect() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "old definition", T1, T2, OrderableStatus.ACTIVE),
                lab(ORG_A, FACILITY, 2, "CBC", "new definition", T2, null, OrderableStatus.ACTIVE)));
        assertEquals(1, catalog.search(ORG_A, FACILITY, "CBC", AT).size());
        assertEquals(1, catalog.search(ORG_A, FACILITY, "CBC", AT).get(0).version());
        List<OrderableItem> after = catalog.search(ORG_A, FACILITY, "CBC", T2);
        assertEquals(1, after.size());
        assertEquals(2, after.get(0).version());
    }

    @Test
    void resolveReturnsTheNewestEffectiveVersionForAnExactCode() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "old", T1, T2, OrderableStatus.ACTIVE),
                lab(ORG_A, FACILITY, 2, "CBC", "new", T2, null, OrderableStatus.ACTIVE)));
        assertEquals(2, catalog.resolve(ORG_A, FACILITY, "CBC", T2).orElseThrow().version());
    }

    @Test
    void resolveReturnsEmptyForRetiredOrUnknownCodes() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "retired", T1, null, OrderableStatus.RETIRED)));
        assertTrue(catalog.resolve(ORG_A, FACILITY, "CBC", AT).isEmpty());
        assertTrue(catalog.resolve(ORG_A, FACILITY, "UNKNOWN", AT).isEmpty());
    }

    @Test
    void resolveIsScopedToTenantFacilityAndEffectiveTime() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "A main", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_B, FACILITY, 1, "CBC", "B main", T1, null, OrderableStatus.ACTIVE)));
        assertEquals("A main", catalog.resolve(ORG_A, FACILITY, "CBC", AT).orElseThrow().name());
        assertEquals("B main", catalog.resolve(ORG_B, FACILITY, "CBC", AT).orElseThrow().name());
        assertTrue(catalog.resolve(ORG_A, ANNEX, "CBC", AT).isEmpty());
        assertTrue(catalog.resolve(ORG_A, FACILITY, "CBC", T0).isEmpty());
    }

    @Test
    void searchAndResolveGuardTheirScope() {
        OrderableCatalog catalog = new OrderableCatalog(List.of());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.search(null, FACILITY, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.search("  ", FACILITY, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.search(ORG_A, null, null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.search(ORG_A, " ", null, AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.search(ORG_A, FACILITY, null, null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.resolve(null, FACILITY, "CBC", AT)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.resolve(ORG_A, FACILITY, null, AT)).status());
    }

    @Test
    void snapshotReturnsTheExactVersionAnOldOrderUsed() {
        OrderableItem old = lab(ORG_A, FACILITY, 1, "CBC", "old definition", T1, T2, OrderableStatus.RETIRED);
        OrderableItem current = lab(ORG_A, FACILITY, 2, "CBC", "new definition", T2, null,
                OrderableStatus.ACTIVE);
        OrderableCatalog catalog = new OrderableCatalog(List.of(old, current));
        OrderableItem snapshot = catalog.snapshot(ORG_A, FACILITY, "item-CBC", 1).orElseThrow();
        assertEquals(1, snapshot.version());
        assertEquals("old definition", snapshot.name());
        assertEquals("whole-blood", snapshot.specimenOrModality());
        assertEquals(OrderableStatus.RETIRED, snapshot.status());
        assertEquals(T2, snapshot.effectiveTo());
    }

    @Test
    void snapshotIsTenantAndFacilityScopedAndRejectsUnknownVersions() {
        OrderableCatalog catalog = new OrderableCatalog(List.of(
                lab(ORG_A, FACILITY, 1, "CBC", "A main", T1, null, OrderableStatus.ACTIVE),
                lab(ORG_B, FACILITY, 1, "CBC", "B main", T1, null, OrderableStatus.ACTIVE)));
        assertEquals("A main", catalog.snapshot(ORG_A, FACILITY, "item-CBC", 1).orElseThrow().name());
        assertEquals("B main", catalog.snapshot(ORG_B, FACILITY, "item-CBC", 1).orElseThrow().name());
        assertTrue(catalog.snapshot("org-synthetic-c", FACILITY, "item-CBC", 1).isEmpty());
        assertTrue(catalog.snapshot(ORG_A, ANNEX, "item-CBC", 1).isEmpty());
        assertTrue(catalog.snapshot(ORG_A, FACILITY, "item-CBC", 9).isEmpty());
        assertTrue(catalog.snapshot(ORG_A, FACILITY, "unknown-item", 1).isEmpty());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.snapshot(ORG_A, FACILITY, "item-CBC", 0)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.snapshot(null, FACILITY, "item-CBC", 1)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> catalog.snapshot(ORG_A, FACILITY, " ", 1)).status());
    }
}
