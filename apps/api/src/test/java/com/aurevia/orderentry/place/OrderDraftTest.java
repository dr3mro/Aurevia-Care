package com.aurevia.orderentry.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Draft revisions: a copy-on-write edit keeps identity and provenance. */
class OrderDraftTest {

    @Test
    void revised_increments_version_and_keeps_identity_and_provenance() {
        PlacedOrderRequest first = new PlacedOrderRequest("p", "e", "f",
                List.of(new OrderSelection("i", 1)), "question", "routine", null, "partner",
                DeliveryMode.ELECTRONIC);
        Instant created = Instant.parse("2026-11-21T10:00:00Z");
        OrderDraft draft = new OrderDraft("org", "draft-1", 1, first, "clinician-1", created);
        PlacedOrderRequest second = new PlacedOrderRequest("p", "e", "f",
                List.of(new OrderSelection("i", 2)), "revised question", "stat", null, "partner",
                DeliveryMode.BOTH);

        OrderDraft revised = draft.revised(second);

        assertEquals("org", revised.organizationId());
        assertEquals("draft-1", revised.id());
        assertEquals(2, revised.version());
        assertEquals("clinician-1", revised.createdBy());
        assertEquals(created, revised.createdAt());
        assertEquals(second, revised.request());
        assertNotEquals(first, revised.request());
    }
}
