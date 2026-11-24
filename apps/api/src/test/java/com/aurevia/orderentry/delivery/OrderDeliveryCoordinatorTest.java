package com.aurevia.orderentry.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/**
 * Acceptance and branch coverage for the order-delivery kernel (XCH-01, XCH-02, XCH-03, PRD-02).
 */
class OrderDeliveryCoordinatorTest {

    private static final String ORG = "org-order-delivery";
    private static final String OTHER_ORG = "org-other-delivery";
    private static final String SUBJECT = "clinician-1";
    private static final String ORDER = "order-1";
    private static final String PARTNER = "partner-lab";
    private static final String PAYLOAD = "digest-abc";
    private static final Set<String> CLINICIAN = Set.of("clinician");
    private static final Set<String> RECEPTION = Set.of("reception");
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    private ApprovedChannelRegistry registry;
    private OrderDeliveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        registry = new ApprovedChannelRegistry(Map.of());
        coordinator = new OrderDeliveryCoordinator(Clock.fixed(AT, ZoneOffset.UTC), registry, 3);
    }

    private static final class FakeChannel implements PartnerChannel {

        private final Deque<SendOutcome> outcomes = new ArrayDeque<>();
        private int calls;

        FakeChannel(SendOutcome... outcomes) {
            this.outcomes.addAll(Arrays.asList(outcomes));
        }

        @Override
        public SendOutcome send(SendRequest request) {
            calls++;
            if (outcomes.isEmpty()) {
                throw new IllegalStateException("unexpected send: no queued outcome");
            }
            return outcomes.removeFirst();
        }

        int calls() {
            return calls;
        }
    }

    private FakeChannel approved(SendOutcome... outcomes) {
        FakeChannel channel = new FakeChannel(outcomes);
        registry.register(PARTNER, channel);
        return channel;
    }

    private static int status(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }

    private static ManualDelivery evidence() {
        return new ManualDelivery("nurse-7", AT.minusSeconds(300), "supervisor-9", AT.minusSeconds(60),
                "handover-receipt-42", "CLINICAL_CARE");
    }

    @Test
    void confirmed_delivery_records_submitted_then_delivery_confirmed_separately() {
        FakeChannel channel = approved(SendOutcome.DELIVERED);

        OrderDelivery delivery = coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        assertEquals(DeliveryStatus.DELIVERED, delivery.status());
        assertEquals(DeliveryRoute.ELECTRONIC, delivery.route());
        assertEquals(1, delivery.attempts());
        assertEquals(AT, delivery.updatedAt());
        assertEquals(1, delivery.orderVersion());
        assertTrue(delivery.submissionId() != null && !delivery.submissionId().isBlank());
        assertEquals(1, channel.calls());
        List<DeliveryEvent> events = coordinator.history(ORG, ORDER);
        assertEquals(2, events.size());
        DeliveryEvent submitted = events.get(0);
        assertEquals(DeliveryEventKind.SUBMITTED, submitted.kind());
        assertEquals(PARTNER, submitted.detail());
        assertEquals(SUBJECT, submitted.actor());
        assertEquals(1, submitted.attempt());
        assertEquals(AT, submitted.occurredAt());
        assertEquals(1, submitted.orderVersion());
        assertEquals(ORG, submitted.organizationId());
        assertEquals(ORDER, submitted.orderId());
        assertEquals(DeliveryEventKind.DELIVERY_CONFIRMED, events.get(1).kind());
        assertNotEquals(submitted.id(), events.get(1).id());
        assertTrue(coordinator.delivery(ORG, ORDER).isPresent());
    }

    @Test
    void submit_through_a_partner_without_an_approved_interface_is_rejected_before_sending() {
        ApiException error = assertThrows(ApiException.class,
                () -> coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, "no-interface", PAYLOAD));

        assertEquals(422, error.status());
        assertEquals("NO_APPROVED_INTERFACE", error.code());
        assertTrue(coordinator.history(ORG, ORDER).isEmpty());
        assertTrue(coordinator.delivery(ORG, ORDER).isEmpty());
    }

    @Test
    void a_transient_failure_below_the_retry_limit_stays_pending_reuses_the_submission_and_can_be_retried() {
        FakeChannel channel = approved(SendOutcome.FAILED, SendOutcome.DELIVERED);

        OrderDelivery first = coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);
        assertEquals(DeliveryStatus.PENDING, first.status());
        assertEquals(1, first.attempts());
        assertEquals(2, coordinator.history(ORG, ORDER).size());
        assertEquals(DeliveryEventKind.DELIVERY_FAILED, coordinator.history(ORG, ORDER).get(1).kind());

        OrderDelivery second = coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);
        assertEquals(DeliveryStatus.DELIVERED, second.status());
        assertEquals(2, second.attempts());
        assertEquals(first.submissionId(), second.submissionId());
        assertEquals(2, channel.calls());
        assertEquals(4, coordinator.history(ORG, ORDER).size());
    }

    @Test
    void exhausting_the_retry_limit_marks_needs_action_retains_failures_and_blocks_resend() {
        FakeChannel channel = approved(SendOutcome.FAILED, SendOutcome.FAILED);
        OrderDeliveryCoordinator limited =
                new OrderDeliveryCoordinator(Clock.fixed(AT, ZoneOffset.UTC), registry, 2);

        assertEquals(DeliveryStatus.PENDING,
                limited.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD).status());
        OrderDelivery exhausted = limited.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);
        assertEquals(DeliveryStatus.NEEDS_ACTION, exhausted.status());
        assertEquals(2, exhausted.attempts());
        List<DeliveryEvent> events = limited.history(ORG, ORDER);
        assertEquals(4, events.size());
        assertEquals(2, channel.calls());
        assertEquals(1, events.get(1).attempt());
        assertEquals(2, events.get(3).attempt());

        ApiException blocked = assertThrows(ApiException.class,
                () -> limited.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD));
        assertEquals(409, blocked.status());
        assertEquals("RETRY_EXHAUSTED", blocked.code());
        assertEquals(2, channel.calls());
    }

    @Test
    void an_ambiguous_send_is_not_resent_blindly() {
        FakeChannel channel = approved(SendOutcome.AMBIGUOUS);

        OrderDelivery ambiguous = coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);
        assertEquals(DeliveryStatus.AMBIGUOUS, ambiguous.status());
        assertEquals(1, channel.calls());
        assertEquals(2, coordinator.history(ORG, ORDER).size());
        assertEquals(DeliveryEventKind.AMBIGUOUS_SEND, coordinator.history(ORG, ORDER).get(1).kind());

        ApiException blocked = assertThrows(ApiException.class,
                () -> coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD));
        assertEquals(409, blocked.status());
        assertEquals("AMBIGUOUS_REQUIRES_RECONCILIATION", blocked.code());
        assertEquals(1, channel.calls());
        assertEquals(2, coordinator.history(ORG, ORDER).size());
    }

    @Test
    void an_ambiguous_send_is_reconciled_before_it_may_be_resent() {
        FakeChannel channel = approved(SendOutcome.AMBIGUOUS, SendOutcome.DELIVERED);
        OrderDelivery first = coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        OrderDelivery reconciled =
                coordinator.reconcileAmbiguous(ORG, SUBJECT, CLINICIAN, ORDER, "verified with partner");

        assertEquals(DeliveryStatus.PENDING, reconciled.status());
        List<DeliveryEvent> events = coordinator.history(ORG, ORDER);
        assertEquals(3, events.size());
        assertEquals(DeliveryEventKind.RECONCILED, events.get(2).kind());
        assertEquals("verified with partner", events.get(2).detail());
        assertEquals(SUBJECT, events.get(2).actor());

        OrderDelivery resent = coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);
        assertEquals(DeliveryStatus.DELIVERED, resent.status());
        assertEquals(first.submissionId(), resent.submissionId());
        assertEquals(2, channel.calls());
    }

    @Test
    void reconcile_requires_an_ambiguous_order_and_non_blank_evidence() {
        approved(SendOutcome.DELIVERED);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        assertEquals(409, status(() -> coordinator.reconcileAmbiguous(ORG, SUBJECT, CLINICIAN, ORDER, "note")));
        assertEquals(422, status(() -> coordinator.reconcileAmbiguous(ORG, SUBJECT, CLINICIAN, ORDER, null)));
        assertEquals(422, status(() -> coordinator.reconcileAmbiguous(ORG, SUBJECT, CLINICIAN, ORDER, "  ")));
        assertEquals(404, status(() -> coordinator.reconcileAmbiguous(ORG, SUBJECT, CLINICIAN, "missing", "note")));
    }

    @Test
    void failure_then_switch_to_print_keeps_one_order_and_retains_the_failed_attempt() {
        approved(SendOutcome.FAILED);
        OrderDeliveryCoordinator limited = new OrderDeliveryCoordinator(Clock.fixed(AT, ZoneOffset.UTC),
                registry, 1);
        assertEquals(DeliveryStatus.NEEDS_ACTION,
                limited.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD).status());

        OrderDelivery print = limited.switchToPrint(ORG, SUBJECT, CLINICIAN, ORDER);

        assertEquals(DeliveryRoute.PRINT, print.route());
        assertEquals(DeliveryStatus.PRINT, print.status());
        assertEquals(ORDER, print.orderId());
        assertEquals(1, limited.trackedOrderCount());
        List<DeliveryEvent> events = limited.history(ORG, ORDER);
        assertEquals(3, events.size());
        assertEquals(DeliveryEventKind.DELIVERY_FAILED, events.get(1).kind());
        assertEquals(DeliveryEventKind.SWITCHED_TO_PRINT, events.get(2).kind());
        assertEquals(SUBJECT, events.get(2).actor());
    }

    @Test
    void switching_to_print_twice_is_idempotent() {
        approved(SendOutcome.FAILED);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);
        OrderDelivery first = coordinator.switchToPrint(ORG, SUBJECT, CLINICIAN, ORDER);
        OrderDelivery second = coordinator.switchToPrint(ORG, SUBJECT, CLINICIAN, ORDER);

        assertEquals(first, second);
        assertEquals(1, coordinator.trackedOrderCount());
        assertEquals(3, coordinator.history(ORG, ORDER).size());
    }

    @Test
    void switch_to_print_requires_an_existing_delivery() {
        assertEquals(404, status(() -> coordinator.switchToPrint(ORG, SUBJECT, CLINICIAN, "missing")));
    }

    @Test
    void switch_to_print_is_refused_while_ambiguous_or_after_a_closed_delivery() {
        FakeChannel ambiguous = approved(SendOutcome.AMBIGUOUS);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);
        assertEquals(409, status(() -> coordinator.switchToPrint(ORG, SUBJECT, CLINICIAN, ORDER)));
        assertEquals(1, ambiguous.calls());

        FakeChannel delivered = new FakeChannel(SendOutcome.DELIVERED, SendOutcome.DELIVERED);
        registry.register("partner-2", delivered);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, "order-2", "partner-2", PAYLOAD);
        assertEquals(409, status(() -> coordinator.submit(ORG, SUBJECT, CLINICIAN, "order-2", "partner-2", PAYLOAD)));
        assertEquals(1, delivered.calls());
        assertEquals(409, status(() -> coordinator.switchToPrint(ORG, SUBJECT, CLINICIAN, "order-2")));
        assertEquals(DeliveryStatus.ACCEPTED,
                coordinator.accept(ORG, SUBJECT, CLINICIAN, "order-2").status());
        assertEquals(409, status(() -> coordinator.switchToPrint(ORG, SUBJECT, CLINICIAN, "order-2")));
    }

    @Test
    void partner_acceptance_rejection_and_clarification_are_separate_delivery_events() {
        approved(SendOutcome.DELIVERED);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        assertEquals(DeliveryStatus.ACCEPTED,
                coordinator.accept(ORG, SUBJECT, CLINICIAN, ORDER).status());
        OrderDelivery rejected = coordinator.reject(ORG, SUBJECT, CLINICIAN, ORDER, "wrong specimen");
        assertEquals(DeliveryStatus.REJECTED, rejected.status());
        OrderDelivery clarified =
                coordinator.requestClarification(ORG, SUBJECT, CLINICIAN, ORDER, "needs preparation");
        assertEquals(DeliveryStatus.CLARIFICATION, clarified.status());

        List<DeliveryEvent> events = coordinator.history(ORG, ORDER);
        assertEquals(5, events.size());
        assertEquals(DeliveryEventKind.DELIVERY_CONFIRMED, events.get(1).kind());
        assertEquals(DeliveryEventKind.ACCEPTED, events.get(2).kind());
        assertEquals(SUBJECT, events.get(2).actor());
        assertEquals("accepted", events.get(2).detail());
        assertEquals(DeliveryEventKind.REJECTED, events.get(3).kind());
        assertEquals("wrong specimen", events.get(3).detail());
        assertEquals(DeliveryEventKind.CLARIFICATION_ASKED, events.get(4).kind());
        assertEquals("needs preparation", events.get(4).detail());
    }

    @Test
    void rejection_and_clarification_require_a_reason() {
        assertEquals(422, status(() -> coordinator.reject(ORG, SUBJECT, CLINICIAN, ORDER, null)));
        assertEquals(422, status(() -> coordinator.reject(ORG, SUBJECT, CLINICIAN, ORDER, " ")));
        assertEquals(422, status(() -> coordinator.requestClarification(ORG, SUBJECT, CLINICIAN, ORDER, null)));
        assertEquals(422, status(() -> coordinator.requestClarification(ORG, SUBJECT, CLINICIAN, ORDER, "")));
    }

    @Test
    void accept_requires_an_existing_delivery() {
        assertEquals(404, status(() -> coordinator.accept(ORG, SUBJECT, CLINICIAN, "missing")));
    }

    @Test
    void manual_delivery_records_deliverer_verifier_time_and_verification_evidence() {
        ManualDelivery evidence = evidence();

        OrderDelivery delivery = coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER, evidence);

        assertEquals(DeliveryStatus.MANUAL, delivery.status());
        assertEquals(DeliveryRoute.MANUAL, delivery.route());
        List<DeliveryEvent> events = coordinator.history(ORG, ORDER);
        assertEquals(1, events.size());
        DeliveryEvent event = events.get(0);
        assertEquals(DeliveryEventKind.MANUAL_DELIVERY, event.kind());
        assertEquals("nurse-7", event.actor());
        assertEquals(AT.minusSeconds(300), event.occurredAt());
        assertEquals("supervisor-9", event.verifiedBy());
        assertEquals(AT.minusSeconds(60), event.verifiedAt());
        assertEquals("handover-receipt-42|CLINICAL_CARE", event.detail());
        assertEquals(0, event.attempt());
    }

    @Test
    void manual_delivery_can_follow_an_exhausted_failure_without_a_new_send() {
        FakeChannel channel = approved(SendOutcome.FAILED);
        OrderDeliveryCoordinator limited = new OrderDeliveryCoordinator(Clock.fixed(AT, ZoneOffset.UTC),
                registry, 1);
        limited.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        OrderDelivery manual = limited.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER, evidence());

        assertEquals(DeliveryStatus.MANUAL, manual.status());
        assertEquals(DeliveryRoute.MANUAL, manual.route());
        assertEquals(1, limited.trackedOrderCount());
        assertEquals(1, channel.calls());
        assertEquals(3, limited.history(ORG, ORDER).size());
    }

    @Test
    void manual_delivery_is_refused_after_electronic_delivery() {
        approved(SendOutcome.DELIVERED);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        ApiException error = assertThrows(ApiException.class,
                () -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER, evidence()));
        assertEquals(409, error.status());
        assertEquals("DELIVERY_CLOSED", error.code());
        assertEquals(2, coordinator.history(ORG, ORDER).size());
    }

    @Test
    void manual_delivery_requires_separate_who_when_artifact_purpose_and_verifier() {
        ManualDelivery valid = evidence();
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER, null)));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery(null, AT, "supervisor-9", AT, "receipt", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("  ", AT, "supervisor-9", AT, "receipt", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", AT, null, AT, "receipt", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", AT, " ", AT, "receipt", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", AT, "supervisor-9", AT, null, "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", AT, "supervisor-9", AT, " ", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", AT, "supervisor-9", AT, "receipt", null))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", null, "supervisor-9", AT, "receipt", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", AT, "supervisor-9", null, "receipt", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, ORDER,
                new ManualDelivery("nurse-7", AT, "nurse-7", AT, "receipt", "CARE"))));
        assertEquals(422, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, CLINICIAN, " ", valid)));
        assertTrue(coordinator.history(ORG, ORDER).isEmpty());
    }

    @Test
    void every_command_requires_a_complete_authorization_scope() {
        assertEquals(422, status(() -> coordinator.submit(null, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD)));
        assertEquals(422, status(() -> coordinator.submit("  ", SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD)));
        assertEquals(401, status(() -> coordinator.submit(ORG, null, CLINICIAN, ORDER, PARTNER, PAYLOAD)));
        assertEquals(401, status(() -> coordinator.submit(ORG, "  ", CLINICIAN, ORDER, PARTNER, PAYLOAD)));
        assertEquals(403, status(() -> coordinator.submit(ORG, SUBJECT, null, ORDER, PARTNER, PAYLOAD)));
        assertEquals(403, status(() -> coordinator.submit(ORG, SUBJECT, RECEPTION, ORDER, PARTNER, PAYLOAD)));
        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> coordinator.submit(ORG, SUBJECT, RECEPTION, ORDER, PARTNER, PAYLOAD)).code());
        assertEquals(403, status(() -> coordinator.accept(ORG, SUBJECT, RECEPTION, ORDER)));
        assertEquals(403, status(() -> coordinator.recordManualDelivery(ORG, SUBJECT, null, ORDER, evidence())));
        assertEquals(403, status(() -> coordinator.switchToPrint(ORG, SUBJECT, RECEPTION, ORDER)));
    }

    @Test
    void submit_validates_every_required_input_before_sending() {
        FakeChannel channel = approved(SendOutcome.DELIVERED);
        assertEquals(422, status(() -> coordinator.submit(ORG, SUBJECT, CLINICIAN, null, PARTNER, PAYLOAD)));
        assertEquals(422, status(() -> coordinator.submit(ORG, SUBJECT, CLINICIAN, " ", PARTNER, PAYLOAD)));
        assertEquals(422, status(() -> coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, null, PAYLOAD)));
        assertEquals(422, status(() -> coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, "  ", PAYLOAD)));
        assertEquals(422, status(() -> coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, null)));
        assertEquals(422, status(() -> coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, " ")));
        assertEquals(0, channel.calls());
        assertTrue(coordinator.history(ORG, ORDER).isEmpty());
    }

    @Test
    void deliveries_and_history_are_confined_to_the_active_tenant() {
        approved(SendOutcome.DELIVERED);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        assertEquals(2, coordinator.history(ORG, ORDER).size());
        assertTrue(coordinator.history(OTHER_ORG, ORDER).isEmpty());
        assertTrue(coordinator.delivery(OTHER_ORG, ORDER).isEmpty());
        assertEquals(422, status(() -> coordinator.history(null, ORDER)));
        assertEquals(422, status(() -> coordinator.delivery(ORG, " ")));
        assertEquals(404, status(() -> coordinator.accept(OTHER_ORG, SUBJECT, CLINICIAN, ORDER)));
        assertEquals(404, status(() -> coordinator.switchToPrint(OTHER_ORG, SUBJECT, CLINICIAN, ORDER)));
        assertEquals(1, coordinator.trackedOrderCount());
    }

    @Test
    void history_is_an_immutable_snapshot() {
        approved(SendOutcome.DELIVERED);
        coordinator.submit(ORG, SUBJECT, CLINICIAN, ORDER, PARTNER, PAYLOAD);

        List<DeliveryEvent> snapshot = coordinator.history(ORG, ORDER);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(snapshot.get(0)));
        assertFalse(snapshot.isEmpty());
    }

    @Test
    void constructor_rejects_a_non_positive_retry_limit() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrderDeliveryCoordinator(Clock.fixed(AT, ZoneOffset.UTC), registry, 0));
    }
}
