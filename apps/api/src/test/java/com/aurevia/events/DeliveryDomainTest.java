package com.aurevia.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.events.DurableEventContract.AuditAction;
import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.ProcessingOutcome;
import com.aurevia.events.DurableEventContract.Resolution;

/**
 * Pure contract, input, action-matrix and synthetic-store acceptance for ARC-05.
 */
class DeliveryDomainTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private OutboxEvent event(String organizationId, String id, String key) {
        return new OutboxEvent(id, organizationId, ID, 1, "lab", "result", "partner-lab",
                "a".repeat(64), key, DeliveryState.PENDING, 0, null, NOW, null);
    }

    private DeliveryAuditEvent audit(String organizationId, String id) {
        return new DeliveryAuditEvent(id, organizationId, "worker", "DELIVERY", AuditAction.PUBLISH,
                ID, "corr", Outcome.SUCCESS, NOW);
    }

    @Test
    void deliveryStatesRoundTripAndRejectUnknownValues() {
        for (DeliveryState state : DeliveryState.values()) {
            assertEquals(state, DeliveryState.from(state.wire()));
        }
        assertEquals(DeliveryState.PENDING.wire(), "pending");
        assertThrows(IllegalArgumentException.class, () -> DeliveryState.from("unknown"));
    }

    @Test
    void processingOutcomesRoundTripAndRejectUnknownValues() {
        for (ProcessingOutcome outcome : ProcessingOutcome.values()) {
            assertEquals(outcome, ProcessingOutcome.from(outcome.wire()));
        }
        assertEquals(ProcessingOutcome.HELD_OUT_OF_ORDER.wire(), "held_out_of_order");
        assertThrows(IllegalArgumentException.class, () -> ProcessingOutcome.from("unknown"));
    }

    @Test
    void auditActionsRoundTripAndRejectUnknownValues() {
        for (AuditAction action : AuditAction.values()) {
            assertEquals(action, AuditAction.from(action.wire()));
        }
        assertEquals(AuditAction.AUDIT_READ.wire(), "audit_read");
        assertThrows(IllegalArgumentException.class, () -> AuditAction.from("unknown"));
    }

    @Test
    void outcomesRoundTripAndRejectUnknownValues() {
        for (Outcome outcome : Outcome.values()) {
            assertEquals(outcome, Outcome.from(outcome.wire()));
        }
        assertEquals(Outcome.DENIED.wire(), "denied");
        assertThrows(IllegalArgumentException.class, () -> Outcome.from("unknown"));
    }

    @Test
    void resolutionsRoundTripAndRejectUnknownValues() {
        for (Resolution resolution : Resolution.values()) {
            assertEquals(resolution, Resolution.from(resolution.wire()));
        }
        assertEquals(Resolution.REQUEUED.wire(), "requeued");
        assertThrows(IllegalArgumentException.class, () -> Resolution.from("unknown"));
    }

    @Test
    void inputsValidateIdentifiersDigestsVersionsAndTokens() {
        assertEquals(ID, DeliveryInputs.uuid(ID, "eventId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.uuid(null, "eventId")).status());
        assertEquals("INVALID_EVENTID", assertThrows(ApiException.class,
                () -> DeliveryInputs.uuid("nope", "eventId")).code());
        assertEquals("review", DeliveryInputs.text("  review  ", "reason", 32));
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.text(null, "reason", 32)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.text("  ", "reason", 32)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.text("x".repeat(33), "reason", 32)).status());
        assertEquals(DeliveryInputs.digest("A".repeat(64)), "a".repeat(64));
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.digest(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.digest("abc")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.digest("z".repeat(64))).status());
        assertEquals(7, DeliveryInputs.version(7, "expectedVersion"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.version(0, "expectedVersion")).status());
        assertEquals("key-1", DeliveryInputs.token(" key-1 ", "key"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.token(null, "key")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.token("bad key", "key")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.token("x".repeat(129), "key")).status());
        assertEquals(null, DeliveryInputs.optionalUuid(null, "orderId"));
        assertEquals(ID, DeliveryInputs.optionalUuid(ID, "orderId"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.optionalUuid("bad", "orderId")).status());
        assertNotEquals(DeliveryInputs.newId(), DeliveryInputs.newId());
    }

    @Test
    void inputEnumParsersRejectUnknownWireValues() {
        assertEquals(DeliveryState.FAILED, DeliveryInputs.state("failed"));
        assertEquals(ProcessingOutcome.ACCEPTED, DeliveryInputs.processingOutcome("accepted"));
        assertEquals(AuditAction.REPLAY, DeliveryInputs.action("replay"));
        assertEquals(Outcome.SUCCESS, DeliveryInputs.outcome("success"));
        assertEquals(Resolution.RESOLVED, DeliveryInputs.resolution("resolved"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.state("unknown")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.processingOutcome("unknown")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.action("unknown")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.outcome("unknown")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> DeliveryInputs.resolution("unknown")).status());
    }

    @Test
    void actionMatrixGrantsOnlyBoundedRoles() {
        DeliveryActions actions = new DeliveryActions();
        List<String> roles = List.of("worker", "operator", "integration_admin", "tenant_admin",
                "privacy_auditor", "clinician", "patient");
        List<String> service = List.of("worker", "integration_admin");
        List<String> delivery = List.of("worker", "operator", "integration_admin");
        List<String> admin = List.of("integration_admin", "tenant_admin");
        for (AuditAction action : AuditAction.values()) {
            List<String> expected = switch (action) {
                case PUBLISH, CONSUME -> service;
                case MARK_SENT, MARK_DELIVERED, MARK_FAILED, RETRY_SCHEDULED, DEAD_LETTER -> delivery;
                case REVIEW, REPLAY -> admin;
                case LINK_EXTERNAL -> List.of("clinician", "integration_admin");
                case AUDIT_READ -> List.of("tenant_admin", "privacy_auditor");
            };
            for (String role : roles) {
                assertEquals(expected.contains(role), actions.allows(action, role),
                        action + " / " + role);
            }
        }
    }

    @Test
    void syntheticStoreIsTenantScopedAndPublishesAtomically() {
        InMemoryDeliveryStore store = new InMemoryDeliveryStore();
        OutboxEvent event = event(ORG, ID, "key-1");
        OutboxEvent result = store.publishAtomically(ORG, event, audit(ORG, "audit-1"));
        assertEquals(event, result);
        assertEquals(event, store.outboxEvent(ORG, ID).orElseThrow());
        assertEquals(event, store.byIdempotencyKey(ORG, "lab", "result", "key-1").orElseThrow());
        assertEquals(1, store.auditEvents(ORG).size());
        assertTrue(store.outboxEvent(OTHER_ORG, ID).isEmpty());
        assertTrue(store.byIdempotencyKey(OTHER_ORG, "lab", "result", "key-1").isEmpty());
        assertTrue(store.auditEvents(OTHER_ORG).isEmpty());
        // A repeat publish with the same identity or key returns the first record.
        assertEquals(event, store.publishAtomically(ORG, event, audit(ORG, "audit-2")));
        assertEquals(1, store.auditEvents(ORG).size());
        assertEquals(event, store.publishAtomically(ORG,
                event(ORG, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "key-1"), audit(ORG, "audit-3")));
        assertEquals(1, store.auditEvents(ORG).size());
    }

    @Test
    void syntheticStoreTracksReceiptsOrderingDeadLettersAndReferences() {
        InMemoryDeliveryStore store = new InMemoryDeliveryStore();
        ConsumerReceipt first = new ConsumerReceipt("r1", ORG, "ext-1", "lab", "result", "partner-lab",
                ID, 2, "order", "patient", ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        ConsumerReceipt second = new ConsumerReceipt("r2", ORG, "ext-2", "lab", "result", "partner-lab",
                ID, 5, "order", "patient", ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        ConsumerReceipt lower = new ConsumerReceipt("r3", ORG, "ext-3", "lab", "result", "partner-lab",
                ID, 3, "order", "patient", ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        store.putReceipt(ORG, first);
        store.putReceipt(ORG, second);
        store.putReceipt(ORG, lower);
        assertEquals(first, store.receipt(ORG, "partner-lab", "lab", "result", "ext-1").orElseThrow());
        assertTrue(store.receipt(OTHER_ORG, "partner-lab", "lab", "result", "ext-1").isEmpty());
        assertTrue(store.receipt(ORG, "other", "lab", "result", "ext-1").isEmpty());
        assertEquals(OptionalInt.of(5), store.maxVersion(ORG, "partner-lab", "lab", "result", ID));
        assertEquals(OptionalInt.empty(), store.maxVersion(ORG, "partner-lab", "lab", "result", ID + "x"));

        DeadLetterEntry entry = new DeadLetterEntry(ID, ORG, "reason", 3, NOW, null, null, null, null);
        store.putDeadLetter(ORG, entry);
        assertEquals(entry, store.deadLetter(ORG, ID).orElseThrow());
        assertEquals(List.of(entry), store.deadLetters(ORG));
        assertTrue(store.deadLetters(OTHER_ORG).isEmpty());

        store.addOrder(ORG, "order", "patient");
        store.addPatient(ORG, "patient");
        assertTrue(store.orderExists(ORG, "order"));
        assertFalse(store.orderExists(OTHER_ORG, "order"));
        assertTrue(store.patientExists(ORG, "patient"));
        assertFalse(store.patientExists(OTHER_ORG, "patient"));
    }

    @Test
    void syntheticStoreSkipsForeignAndNonMatchingReceipts() {
        InMemoryDeliveryStore store = new InMemoryDeliveryStore();
        ConsumerReceipt foreign = new ConsumerReceipt("f", OTHER_ORG, "ext-1", "lab", "result",
                "partner-lab", ID, 9, null, null, ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        ConsumerReceipt otherNamespace = new ConsumerReceipt("n", ORG, "ext-1", "lab", "result",
                "other-namespace", ID, 8, null, null, ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        ConsumerReceipt otherSender = new ConsumerReceipt("s", ORG, "ext-1", "radiology", "result",
                "partner-lab", ID, 7, null, null, ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        ConsumerReceipt otherType = new ConsumerReceipt("t", ORG, "ext-1", "lab", "order",
                "partner-lab", ID, 6, null, null, ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        ConsumerReceipt otherAggregate = new ConsumerReceipt("a", ORG, "ext-1", "lab", "result",
                "partner-lab", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", 5, null, null,
                ProcessingOutcome.ACCEPTED, null, NOW, "worker");
        store.putReceipt(OTHER_ORG, foreign);
        store.putReceipt(ORG, otherNamespace);
        store.putReceipt(ORG, otherSender);
        store.putReceipt(ORG, otherType);
        store.putReceipt(ORG, otherAggregate);
        assertEquals(ORG, store.receiptById(ORG, "ext-1").orElseThrow().organizationId());
        assertEquals(foreign, store.receiptById(OTHER_ORG, "ext-1").orElseThrow());
        assertEquals(OptionalInt.empty(),
                store.maxVersion(ORG, "partner-lab", "lab", "result", ID));
        assertEquals(OptionalInt.of(5), store.maxVersion(ORG, "partner-lab", "lab", "result",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
    }

    @Test
    void syntheticStoreTracksMembershipPerTenant() {
        InMemoryDeliveryStore store = new InMemoryDeliveryStore();
        store.addMember(ORG, "worker", "worker", true);
        assertTrue(store.hasActiveMembership(ORG, "worker"));
        assertFalse(store.hasActiveMembership(OTHER_ORG, "worker"));
        assertEquals(List.of("worker"), store.activeRoles(ORG, "worker"));
        assertTrue(store.activeRoles(ORG, "missing").isEmpty());
        store.addMember(ORG, "inactive", "worker", false);
        assertTrue(store.activeRoles(ORG, "inactive").isEmpty());
    }
}
