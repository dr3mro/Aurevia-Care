package com.aurevia.orderentry.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.result.ResultModel.ResultKind;

/**
 * Domain kernel for the single status of a clinical order (LAB-01, LAB-02, RAD-01, XCH-03).
 *
 * <p>One immutable projection and one append-only fact trail are kept per tenant
 * and order. Workflow milestones advance only forward; cancellation needs a
 * reason and is direct until the partner accepts, after which it becomes a
 * cancel request that needs the partner's response; an amend creates a new
 * linked revision and notifies the partner; and a result links back to the order
 * and moves it to resulted while the dedicated critical-result workflow keeps
 * ownership of verification and acknowledgement. The acting tenant, subject and
 * freshly resolved roles are supplied on every command. Storage, HTTP and
 * outbox wiring are owned by a later slice, so this kernel has no framework
 * dependency beyond the domain API exception.</p>
 */
public final class OrderStatusLedger {

    private static final String ORDERING_ROLE = "clinician";

    private final Clock clock;
    private final ConcurrentMap<String, OrderStatusView> statuses = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<StatusEvent>> events = new ConcurrentHashMap<>();

    /**
     * Create the ledger over an injected clock for deterministic server timestamps.
     *
     * @param clock application clock used for every transition and fact
     */
    public OrderStatusLedger(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Open the draft status of a new order in the active tenant.
     *
     * @param organizationId active tenant, never inferred from a request body
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles of the subject
     * @param orderId        tenant-local order identifier
     * @return the initial draft status view
     * @throws ApiException on invalid scope, a missing identifier or an already tracked order
     */
    public OrderStatusView open(String organizationId, String subject, Set<String> roles, String orderId) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        String key = key(organizationId, orderId);
        if (statuses.containsKey(key)) {
            throw new ApiException(409, "STATUS_EXISTS", "an order status already exists");
        }
        Instant now = clock.instant();
        OrderStatusView view = new OrderStatusView(organizationId, orderId, 1, OrderStatus.DRAFT, now,
                null, false, null);
        statuses.put(key, view);
        append(key, view, StatusEventKind.OPENED, "draft opened", subject, now);
        return view;
    }

    /**
     * Record a forward workflow milestone on an order.
     *
     * <p>Cancellation and results have their own commands and cannot be forced
     * through this generic transition. A milestone may not move an order back to
     * an earlier rank.</p>
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor recording the milestone
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param target         later workflow status to enter
     * @return the updated status view
     * @throws ApiException on invalid scope, input, a dedicated transition or a backwards step
     */
    public OrderStatusView advance(String organizationId, String subject, Set<String> roles, String orderId,
                                   OrderStatus target) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        if (target == null) {
            throw new ApiException(422, "INVALID_TRANSITION", "a target status is required");
        }
        OrderStatusView current = requireExisting(organizationId, orderId);
        if (target == OrderStatus.CANCELLED || target == OrderStatus.RESULTED) {
            throw new ApiException(422, "INVALID_TRANSITION", "use cancel or linkResult for this transition");
        }
        if (!target.isAfter(current.status())) {
            throw new ApiException(409, "STATUS_CANNOT_GO_BACKWARDS", "order status cannot go backwards");
        }
        String key = key(organizationId, orderId);
        Instant now = clock.instant();
        OrderStatusView updated = new OrderStatusView(organizationId, orderId, current.revision(), target, now,
                current.cancelReason(), current.cancelRequested(), current.linkedResult());
        statuses.put(key, updated);
        append(key, updated, target.eventKind(), target.wire(), subject, now);
        return updated;
    }

    /**
     * Cancel an order with a required reason.
     *
     * <p>Before partner acceptance the order moves directly to cancelled. After
     * acceptance the status stays unchanged and a cancel request is recorded for
     * the partner; only the partner's response through
     * {@link com.aurevia.orderentry.status.OrderStatusLedger#respondToCancelRequest}
     * can then cancel the order.</p>
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor requesting the cancellation
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param reason         required non-sensitive cancellation reason
     * @return the updated status view
     * @throws ApiException on invalid scope, input, a terminal order or a missing order
     */
    public OrderStatusView cancel(String organizationId, String subject, Set<String> roles, String orderId,
                                  String reason) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        requireText(reason, "reason");
        OrderStatusView current = requireExisting(organizationId, orderId);
        if (current.status() == OrderStatus.CANCELLED) {
            throw new ApiException(409, "ALREADY_CANCELLED", "the order is already cancelled");
        }
        if (current.status() == OrderStatus.RESULTED) {
            throw new ApiException(409, "RESULT_RECEIVED", "a resulted order cannot be cancelled");
        }
        String key = key(organizationId, orderId);
        Instant now = clock.instant();
        OrderStatusView updated;
        if (current.status().isAfterAcceptance()) {
            updated = new OrderStatusView(organizationId, orderId, current.revision(), current.status(), now,
                    reason, true, current.linkedResult());
            append(key, updated, StatusEventKind.CANCEL_REQUESTED, reason, subject, now);
        } else {
            updated = new OrderStatusView(organizationId, orderId, current.revision(), OrderStatus.CANCELLED,
                    now, reason, false, current.linkedResult());
            append(key, updated, StatusEventKind.CANCELLED, reason, subject, now);
        }
        statuses.put(key, updated);
        return updated;
    }

    /**
     * Record the partner's response to a pending post-acceptance cancel request.
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor recording the partner response
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param partnerApproved whether the partner agreed to cancel the order
     * @param detail         non-sensitive response evidence
     * @return the updated status view
     * @throws ApiException on invalid scope, input, no pending request or a missing order
     */
    public OrderStatusView respondToCancelRequest(String organizationId, String subject, Set<String> roles,
                                                   String orderId, boolean partnerApproved, String detail) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        requireText(detail, "detail");
        OrderStatusView current = requireExisting(organizationId, orderId);
        if (!current.cancelRequested()) {
            throw new ApiException(409, "NO_CANCEL_REQUEST", "no cancellation request awaits a partner response");
        }
        String key = key(organizationId, orderId);
        Instant now = clock.instant();
        OrderStatus status = partnerApproved ? OrderStatus.CANCELLED : current.status();
        StatusEventKind kind = partnerApproved
                ? StatusEventKind.CANCEL_REQUEST_CONFIRMED
                : StatusEventKind.CANCEL_REQUEST_DENIED;
        OrderStatusView updated = new OrderStatusView(organizationId, orderId, current.revision(), status, now,
                current.cancelReason(), false, current.linkedResult());
        statuses.put(key, updated);
        append(key, updated, kind, detail, subject, now);
        return updated;
    }

    /**
     * Amend a placed order into a new linked revision and notify the partner.
     *
     * <p>The caller supplies the revision it last read as an {@code If-Match}
     * precondition; a stale revision is refused. An amend never changes the
     * current status backwards and is not allowed once the order is resulted or
     * cancelled.</p>
     *
     * @param organizationId active tenant
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param expectedRevision revision the caller last read
     * @param changeDetail   non-sensitive description of the amendment
     * @return the updated view at the new revision
     * @throws ApiException on invalid scope, input, a stale revision, a terminal order or a missing order
     */
    public OrderStatusView amend(String organizationId, String subject, Set<String> roles, String orderId,
                                 int expectedRevision, String changeDetail) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        requireText(changeDetail, "changeDetail");
        OrderStatusView current = requireExisting(organizationId, orderId);
        if (current.revision() != expectedRevision) {
            throw new ApiException(412, "STALE_VERSION", "the order revision changed since it was read");
        }
        if (current.status().isTerminal()) {
            throw new ApiException(409, "CANNOT_AMEND", "a resulted or cancelled order cannot be amended");
        }
        String key = key(organizationId, orderId);
        Instant now = clock.instant();
        int revised = current.revision() + 1;
        OrderStatusView updated = new OrderStatusView(organizationId, orderId, revised, current.status(), now,
                current.cancelReason(), current.cancelRequested(), current.linkedResult());
        statuses.put(key, updated);
        append(key, updated, StatusEventKind.AMENDED, changeDetail, subject, now);
        append(key, updated, StatusEventKind.PARTNER_NOTIFIED, "amend revision " + revised, subject, now);
        return updated;
    }

    /**
     * Link a result back to its order and move the order to resulted.
     *
     * <p>The exchange result disposition is reused unchanged. A critical result
     * is only linked here; verification, escalation, read-back and clinical
     * acknowledgement remain owned by the XCH-05 critical-result workflow and
     * are never advanced by this command.</p>
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor linking the result
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param resultId       opaque tenant-local result identifier
     * @param kind           exchange result disposition
     * @return the updated status view carrying the linked result
     * @throws ApiException on invalid scope, input, a terminal order or a missing order
     */
    public OrderStatusView linkResult(String organizationId, String subject, Set<String> roles, String orderId,
                                      String resultId, ResultKind kind) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        requireText(resultId, "resultId");
        if (kind == null) {
            throw new ApiException(422, "INVALID_RESULT_KIND", "a result kind is required");
        }
        OrderStatusView current = requireExisting(organizationId, orderId);
        if (current.status() == OrderStatus.CANCELLED) {
            throw new ApiException(409, "CANCELLED_ORDER", "a result cannot be linked to a cancelled order");
        }
        if (current.status() == OrderStatus.RESULTED) {
            throw new ApiException(409, "ALREADY_RESULTED", "a result is already linked to this order");
        }
        String key = key(organizationId, orderId);
        Instant now = clock.instant();
        LinkedResult linked = new LinkedResult(resultId, kind, kind.isCritical(),
                kind.isCritical() ? resultId : null, now);
        OrderStatusView updated = new OrderStatusView(organizationId, orderId, current.revision(),
                OrderStatus.RESULTED, now, current.cancelReason(), current.cancelRequested(), linked);
        statuses.put(key, updated);
        append(key, updated, StatusEventKind.RESULT_LINKED, resultId + "|" + kind.wire(), subject, now);
        return updated;
    }

    /**
     * Read the current status view of one order in the active tenant.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return the immutable status view
     * @throws ApiException on invalid input or an invisible order
     */
    public OrderStatusView view(String organizationId, String orderId) {
        requireText(organizationId, "organizationId");
        requireText(orderId, "orderId");
        return requireExisting(organizationId, orderId);
    }

    /**
     * Read the immutable append-only status trail of one order.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return an immutable snapshot, empty when the order has no facts
     * @throws ApiException on invalid input
     */
    public List<StatusEvent> history(String organizationId, String orderId) {
        requireText(organizationId, "organizationId");
        requireText(orderId, "orderId");
        return List.copyOf(events.getOrDefault(key(organizationId, orderId), List.of()));
    }

    /**
     * Count the orders with a tracked status, for duplicate-order regression assertions.
     *
     * @return number of tenant-scoped orders with a status view
     */
    public int trackedOrderCount() {
        return statuses.size();
    }

    private OrderStatusView requireExisting(String organizationId, String orderId) {
        OrderStatusView view = statuses.get(key(organizationId, orderId));
        if (view == null) {
            throw new ApiException(404, "NOT_FOUND", "order status not found");
        }
        return view;
    }

    private void append(String key, OrderStatusView base, StatusEventKind kind, String detail, String actor,
                        Instant occurredAt) {
        StatusEvent event = new StatusEvent(UUID.randomUUID().toString(), base.organizationId(), base.orderId(),
                base.revision(), kind, detail, actor, occurredAt);
        events.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(event);
    }

    private static void requireScope(String organizationId, String subject, Set<String> roles) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new ApiException(422, "INVALID_ORGANIZATION", "an active organization is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication is required");
        }
        if (roles == null || !roles.contains(ORDERING_ROLE)) {
            throw new ApiException(403, "FORBIDDEN", "a clinician ordering privilege is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_INPUT", field + " is required");
        }
    }

    private static String key(String organizationId, String orderId) {
        return organizationId + '\u001f' + orderId;
    }
}
