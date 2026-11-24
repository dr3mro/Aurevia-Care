package com.aurevia.orderentry.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.aurevia.exchange.result.ResultModel.ResultKind;

/**
 * Acceptance and branch coverage for the single per-order status ledger (LAB-01, LAB-02, RAD-01, XCH-03).
 *
 * <p>All fixtures are synthetic identifiers. The tests drive the normal domain
 * kernel entry points and assert the observable status, linked result, cancel
 * outcome, revision and append-only event trail.</p>
 */
class OrderStatusLedgerTest {

    private static final String ORG = "org-order-status";
    private static final String OTHER_ORG = "org-other-status";
    private static final String SUBJECT = "clinician-1";
    private static final String ORDER = "order-1";
    private static final Set<String> CLINICIAN = Set.of("clinician");
    private static final Set<String> RECEPTION = Set.of("reception");
    private static final Instant AT = Instant.parse("2026-11-21T12:00:00Z");

    private OrderStatusLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new OrderStatusLedger(Clock.fixed(AT, ZoneOffset.UTC));
    }

    private static int status(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }

    private static String code(Runnable action) {
        return assertThrows(ApiException.class, action::run).code();
    }

    private OrderStatusView draft() {
        return ledger.open(ORG, SUBJECT, CLINICIAN, ORDER);
    }

    private OrderStatusView advanceTo(OrderStatusView view, OrderStatus... targets) {
        OrderStatusView current = view;
        for (OrderStatus target : targets) {
            current = ledger.advance(ORG, SUBJECT, CLINICIAN, current.orderId(), target);
        }
        return current;
    }

    @Test
    void wire_keys_and_forward_ranks_are_stable() {
        assertEquals("draft", OrderStatus.DRAFT.wire());
        assertEquals("collected_or_scheduled", OrderStatus.COLLECTED_OR_SCHEDULED.wire());
        assertEquals(0, OrderStatus.DRAFT.rank());
        assertEquals(1, OrderStatus.PLACED.rank());
        assertEquals(2, OrderStatus.DELIVERED.rank());
        assertEquals(3, OrderStatus.ACCEPTED.rank());
        assertEquals(4, OrderStatus.COLLECTED_OR_SCHEDULED.rank());
        assertEquals(5, OrderStatus.RESULTED.rank());
        assertEquals(6, OrderStatus.CANCELLED.rank());
        assertEquals("result_linked", StatusEventKind.RESULT_LINKED.wire());
        assertEquals("cancel_request_denied", StatusEventKind.CANCEL_REQUEST_DENIED.wire());
    }

    @Test
    void open_creates_a_draft_status_and_forbids_a_second_one() {
        OrderStatusView view = draft();

        assertEquals(OrderStatus.DRAFT, view.status());
        assertEquals("draft", view.status().wire());
        assertEquals(1, view.revision());
        assertEquals(ORG, view.organizationId());
        assertEquals(ORDER, view.orderId());
        assertEquals(AT, view.updatedAt());
        assertNull(view.cancelReason());
        assertFalse(view.cancelRequested());
        assertNull(view.linkedResult());
        assertEquals(1, ledger.history(ORG, ORDER).size());
        assertEquals(StatusEventKind.OPENED, ledger.history(ORG, ORDER).get(0).kind());
        assertEquals(SUBJECT, ledger.history(ORG, ORDER).get(0).actor());

        assertEquals(409, status(() -> ledger.open(ORG, SUBJECT, CLINICIAN, ORDER)));
        assertEquals("STATUS_EXISTS", code(() -> ledger.open(ORG, SUBJECT, CLINICIAN, ORDER)));
        assertEquals(1, ledger.trackedOrderCount());
    }

    @Test
    void status_advances_only_forward_through_the_workflow_milestones() {
        OrderStatusView view = advanceTo(draft(), OrderStatus.PLACED, OrderStatus.DELIVERED,
                OrderStatus.ACCEPTED, OrderStatus.COLLECTED_OR_SCHEDULED);

        assertEquals(OrderStatus.COLLECTED_OR_SCHEDULED, view.status());
        assertEquals("collected_or_scheduled", view.status().wire());
        assertEquals(1, view.revision());
        List<StatusEvent> events = ledger.history(ORG, ORDER);
        assertEquals(5, events.size());
        assertEquals(StatusEventKind.OPENED, events.get(0).kind());
        assertEquals(StatusEventKind.PLACED, events.get(1).kind());
        assertEquals(StatusEventKind.DELIVERED, events.get(2).kind());
        assertEquals(StatusEventKind.ACCEPTED, events.get(3).kind());
        assertEquals(StatusEventKind.COLLECTED_OR_SCHEDULED, events.get(4).kind());
        assertEquals("accepted", events.get(3).detail());
        assertEquals(ORDER, events.get(3).orderId());
        assertEquals(ORG, events.get(3).organizationId());
        assertEquals(AT, events.get(3).occurredAt());
    }

    @Test
    void status_order_cannot_go_backwards() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.DELIVERED, OrderStatus.ACCEPTED);

        assertEquals(409, status(() -> ledger.advance(ORG, SUBJECT, CLINICIAN, ORDER, OrderStatus.DELIVERED)));
        assertEquals("STATUS_CANNOT_GO_BACKWARDS",
                code(() -> ledger.advance(ORG, SUBJECT, CLINICIAN, ORDER, OrderStatus.PLACED)));
        assertEquals(OrderStatus.ACCEPTED, ledger.view(ORG, ORDER).status());
        assertEquals(4, ledger.history(ORG, ORDER).size());
    }

    @Test
    void advance_rejects_a_missing_target_and_the_dedicated_cancel_and_result_transitions() {
        draft();

        assertEquals(422, status(() -> ledger.advance(ORG, SUBJECT, CLINICIAN, ORDER, null)));
        assertEquals(422, status(() -> ledger.advance(ORG, SUBJECT, CLINICIAN, ORDER, OrderStatus.CANCELLED)));
        assertEquals(422, status(() -> ledger.advance(ORG, SUBJECT, CLINICIAN, ORDER, OrderStatus.RESULTED)));
        assertEquals("INVALID_TRANSITION",
                code(() -> ledger.advance(ORG, SUBJECT, CLINICIAN, ORDER, OrderStatus.CANCELLED)));
        assertEquals(1, ledger.history(ORG, ORDER).size());
    }

    @Test
    void cancel_before_acceptance_needs_a_reason_and_sets_cancelled() {
        advanceTo(draft(), OrderStatus.PLACED);

        OrderStatusView cancelled = ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "duplicate order");

        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals("duplicate order", cancelled.cancelReason());
        assertFalse(cancelled.cancelRequested());
        List<StatusEvent> events = ledger.history(ORG, ORDER);
        assertEquals(3, events.size());
        assertEquals(StatusEventKind.CANCELLED, events.get(2).kind());
        assertEquals("duplicate order", events.get(2).detail());
        assertEquals(SUBJECT, events.get(2).actor());
    }

    @Test
    void cancel_requires_a_non_blank_reason_and_an_existing_order() {
        draft();

        assertEquals(422, status(() -> ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, null)));
        assertEquals(422, status(() -> ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "  ")));
        assertEquals(404, status(() -> ledger.cancel(ORG, SUBJECT, CLINICIAN, "missing", "reason")));
        assertEquals(OrderStatus.DRAFT, ledger.view(ORG, ORDER).status());
    }

    @Test
    void cancel_after_acceptance_becomes_a_cancel_request_to_the_partner() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.DELIVERED, OrderStatus.ACCEPTED);

        OrderStatusView requested = ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "clinician withdrawal");

        assertEquals(OrderStatus.ACCEPTED, requested.status());
        assertTrue(requested.cancelRequested());
        assertEquals("clinician withdrawal", requested.cancelReason());
        List<StatusEvent> events = ledger.history(ORG, ORDER);
        assertEquals(5, events.size());
        assertEquals(StatusEventKind.CANCEL_REQUESTED, events.get(4).kind());
        assertEquals("clinician withdrawal", events.get(4).detail());
    }

    @Test
    void partner_response_confirms_or_denies_a_post_acceptance_cancel_request() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.ACCEPTED);
        ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "patient withdrew consent");
        OrderStatusView confirmed = ledger.respondToCancelRequest(ORG, SUBJECT, CLINICIAN, ORDER, true,
                "partner confirmed");

        assertEquals(OrderStatus.CANCELLED, confirmed.status());
        assertFalse(confirmed.cancelRequested());
        assertEquals("patient withdrew consent", confirmed.cancelReason());
        assertEquals(StatusEventKind.CANCEL_REQUEST_CONFIRMED,
                ledger.history(ORG, ORDER).get(4).kind());
        assertEquals("partner confirmed", ledger.history(ORG, ORDER).get(4).detail());

        OrderStatusView otherView = ledger.open(ORG, SUBJECT, CLINICIAN, "order-2");
        advanceTo(otherView, OrderStatus.PLACED, OrderStatus.ACCEPTED);
        ledger.cancel(ORG, SUBJECT, CLINICIAN, "order-2", "change of plan");
        OrderStatusView denied = ledger.respondToCancelRequest(ORG, SUBJECT, CLINICIAN, "order-2", false,
                "partner refused");

        assertEquals(OrderStatus.ACCEPTED, denied.status());
        assertFalse(denied.cancelRequested());
        assertEquals(StatusEventKind.CANCEL_REQUEST_DENIED,
                ledger.history(ORG, "order-2").get(4).kind());
    }

    @Test
    void respond_to_cancel_requires_an_outstanding_request_and_non_blank_detail() {
        draft();
        assertEquals(409, status(() -> ledger.respondToCancelRequest(ORG, SUBJECT, CLINICIAN, ORDER, true, "note")));

        advanceTo(ledger.view(ORG, ORDER), OrderStatus.PLACED, OrderStatus.ACCEPTED);
        ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "reason");
        assertEquals(422,
                status(() -> ledger.respondToCancelRequest(ORG, SUBJECT, CLINICIAN, ORDER, true, null)));
        assertEquals(422,
                status(() -> ledger.respondToCancelRequest(ORG, SUBJECT, CLINICIAN, ORDER, true, " ")));
        assertEquals(404,
                status(() -> ledger.respondToCancelRequest(ORG, SUBJECT, CLINICIAN, "missing", true, "note")));
    }

    @Test
    void cancel_is_refused_for_an_already_cancelled_or_resulted_order() {
        draft();
        OrderStatusView cancelled = ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "reason");
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(409, status(() -> ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "again")));
        assertEquals("ALREADY_CANCELLED", code(() -> ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "again")));

        ledger.open(ORG, SUBJECT, CLINICIAN, "order-resulted");
        advanceTo(ledger.view(ORG, "order-resulted"), OrderStatus.PLACED, OrderStatus.ACCEPTED);
        ledger.linkResult(ORG, SUBJECT, CLINICIAN, "order-resulted", "result-1", ResultKind.FINAL);
        assertEquals(409,
                status(() -> ledger.cancel(ORG, SUBJECT, CLINICIAN, "order-resulted", "too late")));
        assertEquals("RESULT_RECEIVED",
                code(() -> ledger.cancel(ORG, SUBJECT, CLINICIAN, "order-resulted", "too late")));
    }

    @Test
    void amend_creates_a_linked_revision_and_notifies_the_partner() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.ACCEPTED);

        OrderStatusView amended = ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 1, "add second test");

        assertEquals(2, amended.revision());
        assertEquals(OrderStatus.ACCEPTED, amended.status());
        List<StatusEvent> events = ledger.history(ORG, ORDER);
        assertEquals(5, events.size());
        assertEquals(StatusEventKind.AMENDED, events.get(3).kind());
        assertEquals("add second test", events.get(3).detail());
        assertEquals(2, events.get(3).revision());
        assertEquals(StatusEventKind.PARTNER_NOTIFIED, events.get(4).kind());
        assertEquals("amend revision 2", events.get(4).detail());
        assertEquals(1, ledger.trackedOrderCount());

        OrderStatusView again = ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 2, "third change");
        assertEquals(3, again.revision());
        assertEquals(7, ledger.history(ORG, ORDER).size());
    }

    @Test
    void amend_rejects_a_stale_revision_blank_detail_and_terminal_statuses() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.ACCEPTED);

        assertEquals(412, status(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 9, "note")));
        assertEquals("STALE_VERSION", code(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 9, "note")));
        assertEquals(422, status(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 1, null)));
        assertEquals(422, status(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 1, " ")));
        assertEquals(404, status(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, "missing", 1, "note")));

        ledger.linkResult(ORG, SUBJECT, CLINICIAN, ORDER, "result-1", ResultKind.FINAL);
        assertEquals(409, status(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 1, "note")));
        assertEquals("CANNOT_AMEND", code(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, ORDER, 1, "note")));

        ledger.open(ORG, SUBJECT, CLINICIAN, "order-cancelled");
        ledger.cancel(ORG, SUBJECT, CLINICIAN, "order-cancelled", "reason");
        assertEquals(409, status(() -> ledger.amend(ORG, SUBJECT, CLINICIAN, "order-cancelled", 1, "note")));
    }

    @Test
    void a_result_links_back_to_its_order_and_shows_in_the_status() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.DELIVERED, OrderStatus.ACCEPTED,
                OrderStatus.COLLECTED_OR_SCHEDULED);

        OrderStatusView resulted = ledger.linkResult(ORG, SUBJECT, CLINICIAN, ORDER, "result-42", ResultKind.FINAL);

        assertEquals(OrderStatus.RESULTED, resulted.status());
        assertEquals("resulted", resulted.status().wire());
        LinkedResult linked = resulted.linkedResult();
        assertEquals("result-42", linked.resultId());
        assertEquals(ResultKind.FINAL, linked.kind());
        assertFalse(linked.critical());
        assertNull(linked.criticalWorkflowReference());
        assertEquals(AT, linked.linkedAt());
        assertEquals(ORDER, ledger.view(ORG, ORDER).orderId());
        List<StatusEvent> events = ledger.history(ORG, ORDER);
        assertEquals(6, events.size());
        assertEquals(StatusEventKind.RESULT_LINKED, events.get(5).kind());
        assertEquals("result-42|final", events.get(5).detail());
    }

    @Test
    void a_critical_result_still_follows_its_separate_critical_result_workflow() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.ACCEPTED);

        OrderStatusView resulted = ledger.linkResult(ORG, SUBJECT, CLINICIAN, ORDER, "critical-7",
                ResultKind.CRITICAL);

        assertEquals(OrderStatus.RESULTED, resulted.status());
        LinkedResult linked = resulted.linkedResult();
        assertEquals(ResultKind.CRITICAL, linked.kind());
        assertTrue(linked.critical());
        assertEquals("critical-7", linked.criticalWorkflowReference());
        // This ledger records only the link. Verification, escalation, read-back and
        // acknowledgement stay with the XCH-05 workflow; no such state is advanced here.
        assertEquals(1, ledger.history(ORG, ORDER).stream()
                .filter(event -> event.kind() == StatusEventKind.RESULT_LINKED).count());
        assertEquals(OrderStatus.RESULTED, ledger.view(ORG, ORDER).status());
    }

    @Test
    void link_result_validates_its_input_and_rejects_terminal_or_missing_orders() {
        draft();
        OrderStatusView cancelled = ledger.cancel(ORG, SUBJECT, CLINICIAN, ORDER, "reason");
        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals(409, status(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, ORDER, "r", ResultKind.FINAL)));
        assertEquals("CANCELLED_ORDER",
                code(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, ORDER, "r", ResultKind.FINAL)));

        ledger.open(ORG, SUBJECT, CLINICIAN, "order-resulted");
        ledger.linkResult(ORG, SUBJECT, CLINICIAN, "order-resulted", "r1", ResultKind.FINAL);
        assertEquals(409, status(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, "order-resulted", "r2",
                ResultKind.FINAL)));
        assertEquals("ALREADY_RESULTED", code(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, "order-resulted",
                "r2", ResultKind.FINAL)));

        assertEquals(422, status(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, "missing", "r", null)));
        assertEquals(422, status(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, "missing", null,
                ResultKind.FINAL)));
        assertEquals(422, status(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, "missing", " ",
                ResultKind.FINAL)));
        assertEquals(404, status(() -> ledger.linkResult(ORG, SUBJECT, CLINICIAN, "missing", "r",
                ResultKind.FINAL)));
    }

    @Test
    void read_sides_are_tenant_scoped_and_history_is_an_immutable_snapshot() {
        advanceTo(draft(), OrderStatus.PLACED, OrderStatus.ACCEPTED);

        assertEquals(OrderStatus.ACCEPTED, ledger.view(ORG, ORDER).status());
        assertEquals(404, status(() -> ledger.view(OTHER_ORG, ORDER)));
        assertTrue(ledger.history(OTHER_ORG, ORDER).isEmpty());
        assertEquals(422, status(() -> ledger.view(" ", ORDER)));
        assertEquals(422, status(() -> ledger.view(ORG, null)));
        assertEquals(422, status(() -> ledger.history(null, ORDER)));
        assertEquals(422, status(() -> ledger.history(ORG, " ")));
        assertEquals(1, ledger.trackedOrderCount());

        List<StatusEvent> snapshot = ledger.history(ORG, ORDER);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(snapshot.get(0)));
        assertFalse(snapshot.isEmpty());
        StatusEvent event = snapshot.get(0);
        assertTrue(event.id() != null && !event.id().isBlank());
        assertNotEquals(event.id(), snapshot.get(1).id());
    }

    @Test
    void every_command_requires_a_complete_authorization_scope() {
        assertEquals(422, status(() -> ledger.open(null, SUBJECT, CLINICIAN, ORDER)));
        assertEquals(422, status(() -> ledger.open("  ", SUBJECT, CLINICIAN, ORDER)));
        assertEquals(401, status(() -> ledger.open(ORG, null, CLINICIAN, ORDER)));
        assertEquals(401, status(() -> ledger.open(ORG, "  ", CLINICIAN, ORDER)));
        assertEquals(403, status(() -> ledger.open(ORG, SUBJECT, null, ORDER)));
        assertEquals(403, status(() -> ledger.open(ORG, SUBJECT, RECEPTION, ORDER)));
        assertEquals("FORBIDDEN", code(() -> ledger.open(ORG, SUBJECT, RECEPTION, ORDER)));

        OrderStatusView view = draft();
        assertEquals(403, status(() -> ledger.advance(ORG, SUBJECT, RECEPTION, ORDER, OrderStatus.PLACED)));
        assertEquals(403, status(() -> ledger.cancel(ORG, SUBJECT, RECEPTION, ORDER, "reason")));
        assertEquals(403, status(() -> ledger.amend(ORG, SUBJECT, RECEPTION, ORDER, 1, "note")));
        assertEquals(403, status(() -> ledger.linkResult(ORG, SUBJECT, RECEPTION, ORDER, "r", ResultKind.FINAL)));
        assertEquals(403, status(() -> ledger.respondToCancelRequest(ORG, SUBJECT, RECEPTION, ORDER, true,
                "note")));
        assertEquals(OrderStatus.DRAFT, view.status());
    }
}
