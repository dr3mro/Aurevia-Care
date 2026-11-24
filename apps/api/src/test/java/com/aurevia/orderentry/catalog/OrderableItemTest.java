package com.aurevia.orderentry.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** ORDER-01 (LAB-01, RAD-01, XCH-01): the immutable, versioned orderable catalog item and its rules. */
class OrderableItemTest {

    private static final String ORG = "org-synthetic-a";
    private static final String FACILITY = "facility-main";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant BEFORE = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2027-06-01T00:00:00Z");

    private static OrderableItem item(String org, String facility, String itemId, int version, String code,
                                      String name, OrderableKind kind, String specimenOrModality,
                                      String preparation, String priority, String partner,
                                      Instant from, Instant to, OrderableStatus status) {
        return new OrderableItem(org, facility, itemId, version, code, name, kind, specimenOrModality,
                preparation, priority, partner, from, to, status);
    }

    private static OrderableItem lab(Instant from, Instant to, OrderableStatus status) {
        return item(ORG, FACILITY, "item-cbc", 1, "CBC", "Complete Blood Count", OrderableKind.LAB_TEST,
                "whole-blood", "fasting", "routine", "partner-lab", from, to, status);
    }

    @Test
    void validLabItemKeepsItsIdentityAndNormalizesItsDefaultPriority() {
        OrderableItem value = lab(FROM, TO, OrderableStatus.ACTIVE);
        assertEquals(ORG, value.organizationId());
        assertEquals(FACILITY, value.facilityId());
        assertEquals("item-cbc", value.itemId());
        assertEquals(1, value.version());
        assertEquals("CBC", value.code());
        assertEquals("Complete Blood Count", value.name());
        assertEquals(OrderableKind.LAB_TEST, value.kind());
        assertEquals("whole-blood", value.specimenOrModality());
        assertEquals("fasting", value.preparationInstructions());
        assertEquals("routine", value.defaultPriority());
        assertEquals("partner-lab", value.partnerId());
        assertEquals(FROM, value.effectiveFrom());
        assertEquals(TO, value.effectiveTo());
        assertEquals(OrderableStatus.ACTIVE, value.status());
    }

    @Test
    void priorityIsTrimmedAndLowercasedAtTheBoundary() {
        OrderableItem value = item(ORG, FACILITY, "item-ct", 2, "CTHEAD", "CT Head",
                OrderableKind.IMAGING_STUDY, "ct", null, "  STAT ", "partner-rad", FROM, null,
                OrderableStatus.ACTIVE);
        assertEquals("stat", value.defaultPriority());
        assertNull(value.preparationInstructions());
        assertNull(value.effectiveTo());
    }

    @Test
    void rejectsMissingOrBlankIdentityFields() {
        assertEquals(422, assertThrows(ApiException.class, () -> item(null, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item("  ", FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, null, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, " ", "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, null, 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, " ", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, null, "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, " ", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", null,
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", " ",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, null, null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, " ", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", null, FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", " ", FROM, null, OrderableStatus.ACTIVE)).status());
    }

    @Test
    void rejectsNonPositiveVersionAndMissingEnums() {
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 0, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                null, "s", null, "routine", "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "routine", "p", FROM, null, null)).status());
    }

    @Test
    void rejectsInvalidEffectiveWindowAndPriority() {
        assertEquals(422, assertThrows(ApiException.class, () -> lab(null, TO, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> lab(FROM, FROM, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> lab(FROM, BEFORE, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, null, "p", FROM, null, OrderableStatus.ACTIVE)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "id", 1, "C", "N",
                OrderableKind.LAB_TEST, "s", null, "asap", "p", FROM, null, OrderableStatus.ACTIVE)).status());
    }

    @Test
    void imagingStudyAcceptsAnApprovedModalityAndRejectsAnUnknownOne() {
        OrderableItem ct = item(ORG, FACILITY, "item-ct", 1, "CTHEAD", "CT Head",
                OrderableKind.IMAGING_STUDY, "ct", null, "stat", "partner-rad", FROM, null,
                OrderableStatus.ACTIVE);
        assertEquals("ct", ct.specimenOrModality());
        assertEquals(422, assertThrows(ApiException.class, () -> item(ORG, FACILITY, "item-ct", 1,
                "CTHEAD", "CT Head", OrderableKind.IMAGING_STUDY, "ct-scan", null, "stat", "partner-rad",
                FROM, null, OrderableStatus.ACTIVE)).status());
    }

    @Test
    void effectiveAtHonoursBothOpenAndClosedWindows() {
        OrderableItem closed = lab(FROM, TO, OrderableStatus.ACTIVE);
        assertFalse(closed.effectiveAt(BEFORE));
        assertTrue(closed.effectiveAt(FROM));
        assertTrue(closed.effectiveAt(FROM.plusSeconds(1)));
        assertTrue(closed.effectiveAt(TO.minusNanos(1)));
        assertFalse(closed.effectiveAt(TO));
        assertFalse(closed.effectiveAt(AFTER));

        OrderableItem open = lab(FROM, null, OrderableStatus.ACTIVE);
        assertTrue(open.effectiveAt(AFTER));
    }

    @Test
    void orderableAtRequiresBothActiveStatusAndAnEffectiveWindow() {
        assertFalse(lab(FROM, TO, OrderableStatus.RETIRED).orderableAt(FROM));
        assertFalse(lab(FROM, TO, OrderableStatus.ACTIVE).orderableAt(AFTER));
        assertTrue(lab(FROM, TO, OrderableStatus.ACTIVE).orderableAt(FROM));
    }

    @Test
    void itemIsAValueWithStableEqualityAndBothKindsAreNamed() {
        OrderableItem first = lab(FROM, TO, OrderableStatus.ACTIVE);
        OrderableItem second = lab(FROM, TO, OrderableStatus.ACTIVE);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.toString(), second.toString());
        assertEquals(2, OrderableKind.values().length);
        assertEquals(OrderableKind.LAB_TEST, OrderableKind.valueOf("LAB_TEST"));
        assertEquals(2, OrderableStatus.values().length);
        assertEquals(OrderableStatus.RETIRED, OrderableStatus.valueOf("RETIRED"));
    }
}
