package com.aurevia.orderentry.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.IdempotencyKey;

/** Placement kernel: delivery modes, draft/placed lifecycle, authorization, tenant scope and idempotency. */
class OrderPlacementServiceTest {

    private static final String ORG = "org-order-place";
    private static final String OTHER_ORG = "org-other-place";
    private static final String SUBJECT = "clinician-1";
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Set<String> CLINICIAN = Set.of("clinician");
    private static final Set<String> RECEPTION = Set.of("reception");

    private OrderPlacementService service;

    @BeforeEach
    void setUp() {
        service = new OrderPlacementService(Clock.fixed(AT, ZoneOffset.UTC));
    }

    private static PlacedOrderRequest request(DeliveryMode mode) {
        return new PlacedOrderRequest("patient-1", "encounter-1", "facility-1",
                List.of(new OrderSelection("item-cbc", 2), new OrderSelection("item-xray", 1)),
                "rule out anaemia", "urgent", "fast for 8 hours", "partner-lab", mode);
    }

    private OrderDraft saved() {
        return service.saveDraft(ORG, SUBJECT, CLINICIAN, request(DeliveryMode.BOTH));
    }

    private static int status(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }

    @Test
    void save_draft_stores_version_one_with_server_identity() {
        OrderDraft draft = saved();
        assertEquals(ORG, draft.organizationId());
        assertEquals(SUBJECT, draft.createdBy());
        assertEquals(AT, draft.createdAt());
        assertEquals(1, draft.version());
        assertTrue(draft.id() != null && !draft.id().isBlank());
        assertEquals(DeliveryMode.BOTH, draft.request().deliveryMode());
    }

    @Test
    void every_command_requires_a_complete_scope() {
        PlacedOrderRequest request = request(DeliveryMode.PRINT);
        assertEquals(422, status(() -> service.saveDraft(null, SUBJECT, CLINICIAN, request)));
        assertEquals(422, status(() -> service.saveDraft("  ", SUBJECT, CLINICIAN, request)));
        assertEquals(401, status(() -> service.saveDraft(ORG, null, CLINICIAN, request)));
        assertEquals(401, status(() -> service.saveDraft(ORG, "  ", CLINICIAN, request)));
    }

    @Test
    void only_a_clinician_with_ordering_privilege_may_act() {
        PlacedOrderRequest request = request(DeliveryMode.PRINT);
        assertEquals(403, status(() -> service.saveDraft(ORG, SUBJECT, null, request)));
        assertEquals(403, status(() -> service.saveDraft(ORG, SUBJECT, RECEPTION, request)));
        ApiException denial = assertThrows(ApiException.class,
                () -> service.saveDraft(ORG, SUBJECT, RECEPTION, request));
        assertEquals("FORBIDDEN", denial.code());
    }

    @Test
    void edit_replaces_content_and_increments_the_version() {
        OrderDraft draft = saved();
        PlacedOrderRequest revised = request(DeliveryMode.ELECTRONIC);
        OrderDraft updated = service.editDraft(ORG, SUBJECT, CLINICIAN, draft.id(), 1, revised);
        assertEquals(2, updated.version());
        assertEquals(draft.id(), updated.id());
        assertEquals(DeliveryMode.ELECTRONIC, updated.request().deliveryMode());
    }

    @Test
    void edit_refuses_a_stale_version() {
        OrderDraft draft = saved();
        ApiException error = assertThrows(ApiException.class,
                () -> service.editDraft(ORG, SUBJECT, CLINICIAN, draft.id(), 2, request(DeliveryMode.PRINT)));
        assertEquals(412, error.status());
        assertEquals("STALE_VERSION", error.code());
    }

    @Test
    void edit_refuses_an_unknown_draft() {
        assertEquals(404, status(() -> service.editDraft(ORG, SUBJECT, CLINICIAN, "missing", 1,
                request(DeliveryMode.PRINT))));
    }

    @Test
    void edit_refuses_another_tenants_draft() {
        OrderDraft draft = saved();
        assertEquals(404, status(() -> service.editDraft(OTHER_ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                request(DeliveryMode.PRINT))));
    }

    @Test
    void place_snapshots_each_delivery_mode_and_the_placer() {
        for (DeliveryMode mode : DeliveryMode.values()) {
            OrderDraft draft = service.saveDraft(ORG, SUBJECT, CLINICIAN, request(mode));
            PlacedOrder placed = service.place(ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                    IdempotencyKey.parse("key-" + mode.name()));
            assertEquals(mode, placed.request().deliveryMode());
            assertEquals(mode.electronic(), placed.request().deliveryMode().electronic());
            assertEquals(mode.print(), placed.request().deliveryMode().print());
            assertEquals(SUBJECT, placed.placedBy());
            assertEquals(AT, placed.placedAt());
            assertEquals(1, placed.version());
            assertEquals(ORG, placed.organizationId());
            assertEquals("key-" + mode.name(), placed.idempotencyKey());
            assertFalse(placed.payload().isBlank());
        }
    }

    @Test
    void placing_consumes_the_draft_so_a_placed_order_is_immutable() {
        OrderDraft draft = saved();
        service.place(ORG, SUBJECT, CLINICIAN, draft.id(), 1, IdempotencyKey.parse("key-immutable"));
        assertEquals(404, status(() -> service.editDraft(ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                request(DeliveryMode.PRINT))));
        assertEquals(404, status(() -> service.place(ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                IdempotencyKey.parse("key-second"))));
    }

    @Test
    void a_retried_placement_returns_the_same_order() {
        OrderDraft draft = saved();
        PlacedOrder first = service.place(ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                IdempotencyKey.parse("retry-key"));
        PlacedOrder retry = service.place(ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                IdempotencyKey.parse("retry-key"));
        assertEquals(first.id(), retry.id());
        assertEquals(first, retry);
        assertEquals(first, service.get(ORG, SUBJECT, CLINICIAN, first.id()));
    }

    @Test
    void reusing_a_key_for_a_different_draft_conflicts() {
        OrderDraft first = saved();
        OrderDraft second = saved();
        service.place(ORG, SUBJECT, CLINICIAN, first.id(), 1, IdempotencyKey.parse("shared-key"));
        ApiException error = assertThrows(ApiException.class,
                () -> service.place(ORG, SUBJECT, CLINICIAN, second.id(), 1,
                        IdempotencyKey.parse("shared-key")));
        assertEquals(409, error.status());
        assertEquals("IDEMPOTENCY_CONFLICT", error.code());
        assertNotEquals(first.id(), second.id());
    }

    @Test
    void place_refuses_a_stale_version() {
        OrderDraft draft = saved();
        assertEquals(412, status(() -> service.place(ORG, SUBJECT, CLINICIAN, draft.id(), 7,
                IdempotencyKey.parse("stale-key"))));
    }

    @Test
    void place_refuses_an_unknown_or_foreign_draft() {
        assertEquals(404, status(() -> service.place(ORG, SUBJECT, CLINICIAN, "missing", 1,
                IdempotencyKey.parse("missing-key"))));
        OrderDraft draft = saved();
        assertEquals(404, status(() -> service.place(OTHER_ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                IdempotencyKey.parse("foreign-key"))));
    }

    @Test
    void place_refuses_a_subject_without_ordering_privilege() {
        OrderDraft draft = saved();
        assertEquals(403, status(() -> service.place(ORG, SUBJECT, RECEPTION, draft.id(), 1,
                IdempotencyKey.parse("role-key"))));
    }

    @Test
    void get_returns_a_placed_order_in_its_tenant_only() {
        OrderDraft draft = saved();
        PlacedOrder placed = service.place(ORG, SUBJECT, CLINICIAN, draft.id(), 1,
                IdempotencyKey.parse("get-key"));
        assertEquals(placed, service.get(ORG, SUBJECT, CLINICIAN, placed.id()));
        assertEquals(404, status(() -> service.get(OTHER_ORG, SUBJECT, CLINICIAN, placed.id())));
        assertEquals(404, status(() -> service.get(ORG, SUBJECT, CLINICIAN, "missing")));
        assertEquals(403, status(() -> service.get(ORG, SUBJECT, RECEPTION, placed.id())));
    }
}
