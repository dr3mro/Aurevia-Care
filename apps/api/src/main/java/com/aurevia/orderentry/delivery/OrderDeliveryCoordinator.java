package com.aurevia.orderentry.delivery;

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

/**
 * Domain kernel for delivering a placed order to a partner with evidence and a print fallback (XCH-01, XCH-02, XCH-03, PRD-02).
 *
 * <p>The kernel keeps two projections per tenant and order: one current
 * {@link com.aurevia.orderentry.delivery.OrderDelivery} and an
 * append-only list of {@link com.aurevia.orderentry.delivery.DeliveryEvent}
 * facts. Transmission facts (submitted, confirmed, failed, ambiguous, reconciled)
 * are separate from recipient workflow responses (accepted, rejected,
 * clarification) and from the manual and print fallbacks. No delivery fact ever
 * changes a clinical review task. The acting tenant, subject and freshly
 * resolved roles are supplied on every command; acceptance and persistence are
 * wired by a later slice because this story's scope owns only the kernel.</p>
 */
public final class OrderDeliveryCoordinator {

    private static final String ORDERING_ROLE = "clinician";
    private static final int ORDER_VERSION = 1;

    private final Clock clock;
    private final ApprovedChannelRegistry registry;
    private final int retryLimit;
    private final ConcurrentMap<String, OrderDelivery> deliveries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<DeliveryEvent>> events = new ConcurrentHashMap<>();

    /**
     * Create the kernel over an injected clock and approved-channel registry.
     *
     * @param clock      application clock for all server timestamps
     * @param registry   partner interfaces approved for electronic delivery
     * @param retryLimit maximum number of electronic send attempts, at least one
     * @throws IllegalArgumentException when the retry limit is below one
     */
    public OrderDeliveryCoordinator(Clock clock, ApprovedChannelRegistry registry, int retryLimit) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (retryLimit < 1) {
            throw new IllegalArgumentException("retryLimit must be at least 1");
        }
        this.retryLimit = retryLimit;
    }

    /**
     * Hand an order to the approved partner channel and record the outcome as delivery events.
     *
     * <p>A retry reuses the original submission identifier and never creates a
     * second order. A send whose outcome is unknown blocks all resends until it
     * is reconciled, and an exhausted retry budget requires clinician action.</p>
     *
     * @param organizationId active tenant
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local placed-order identifier
     * @param partnerCode    approved partner interface key
     * @param payloadDigest  digest of the released snapshot
     * @return the updated delivery projection
     * @throws ApiException on invalid scope, an unapproved partner, an illegal state or invalid input
     */
    public OrderDelivery submit(String organizationId, String subject, Set<String> roles, String orderId,
                                String partnerCode, String payloadDigest) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        requireText(partnerCode, "partnerCode");
        requireText(payloadDigest, "payloadDigest");
        PartnerChannel channel = registry.channel(partnerCode);
        String key = key(organizationId, orderId);
        Instant now = clock.instant();
        OrderDelivery base = deliveries.get(key);
        if (base == null) {
            base = new OrderDelivery(organizationId, orderId, DeliveryRoute.ELECTRONIC, DeliveryStatus.PENDING,
                    UUID.randomUUID().toString(), 0, ORDER_VERSION, now);
        }
        DeliveryStatus current = base.status();
        if (current == DeliveryStatus.AMBIGUOUS) {
            throw new ApiException(409, "AMBIGUOUS_REQUIRES_RECONCILIATION",
                    "an ambiguous send must be reconciled before any resend");
        }
        if (current == DeliveryStatus.NEEDS_ACTION) {
            throw new ApiException(409, "RETRY_EXHAUSTED", "the retry budget is exhausted; choose a fallback");
        }
        if (current != DeliveryStatus.PENDING) {
            throw new ApiException(409, "DELIVERY_CLOSED", "this order is no longer awaiting delivery");
        }

        int attempt = base.attempts() + 1;
        append(key, base, DeliveryEventKind.SUBMITTED, attempt, subject, partnerCode, null, null, now);
        SendOutcome outcome = channel.send(new SendRequest(organizationId, orderId, base.submissionId(),
                partnerCode, payloadDigest));
        DeliveryStatus next = switch (outcome) {
            case DELIVERED -> {
                append(key, base, DeliveryEventKind.DELIVERY_CONFIRMED, attempt, subject, partnerCode,
                        null, null, now);
                yield DeliveryStatus.DELIVERED;
            }
            case FAILED -> {
                append(key, base, DeliveryEventKind.DELIVERY_FAILED, attempt, subject, "send failed",
                        null, null, now);
                yield attempt >= retryLimit ? DeliveryStatus.NEEDS_ACTION : DeliveryStatus.PENDING;
            }
            case AMBIGUOUS -> {
                append(key, base, DeliveryEventKind.AMBIGUOUS_SEND, attempt, subject, "outcome unknown",
                        null, null, now);
                yield DeliveryStatus.AMBIGUOUS;
            }
        };
        OrderDelivery updated = new OrderDelivery(organizationId, orderId, DeliveryRoute.ELECTRONIC, next,
                base.submissionId(), attempt, ORDER_VERSION, now);
        deliveries.put(key, updated);
        return updated;
    }

    /**
     * Record the recipient's acceptance of an order.
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor recording the response
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @return the updated delivery projection
     */
    public OrderDelivery accept(String organizationId, String subject, Set<String> roles, String orderId) {
        return respond(organizationId, subject, roles, orderId, "accepted",
                DeliveryEventKind.ACCEPTED, DeliveryStatus.ACCEPTED);
    }

    /**
     * Record the recipient's rejection with a reason.
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor recording the response
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param reason         non-sensitive rejection reason
     * @return the updated delivery projection
     */
    public OrderDelivery reject(String organizationId, String subject, Set<String> roles, String orderId,
                                String reason) {
        return respond(organizationId, subject, roles, orderId, reason,
                DeliveryEventKind.REJECTED, DeliveryStatus.REJECTED);
    }

    /**
     * Record the recipient's clarification request.
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor recording the response
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param reason         non-sensitive clarification detail
     * @return the updated delivery projection
     */
    public OrderDelivery requestClarification(String organizationId, String subject, Set<String> roles,
                                              String orderId, String reason) {
        return respond(organizationId, subject, roles, orderId, reason,
                DeliveryEventKind.CLARIFICATION_ASKED, DeliveryStatus.CLARIFICATION);
    }

    private OrderDelivery respond(String organizationId, String subject, Set<String> roles, String orderId,
                                  String detail, DeliveryEventKind kind, DeliveryStatus status) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        requireText(detail, "detail");
        String key = key(organizationId, orderId);
        OrderDelivery current = requireExisting(organizationId, orderId);
        Instant now = clock.instant();
        append(key, current, kind, 0, subject, detail, null, null, now);
        OrderDelivery updated = new OrderDelivery(organizationId, orderId, current.route(), status,
                current.submissionId(), current.attempts(), ORDER_VERSION, now);
        deliveries.put(key, updated);
        return updated;
    }

    /**
     * Record an approved manual handover with separate delivery and verification evidence.
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor recording the handover
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param evidence       who delivered and verified, when, and the retained evidence
     * @return the updated delivery projection
     * @throws ApiException on invalid scope, missing evidence or a closed delivery
     */
    public OrderDelivery recordManualDelivery(String organizationId, String subject, Set<String> roles,
                                              String orderId, ManualDelivery evidence) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        if (evidence == null) {
            throw new ApiException(422, "INVALID_EVIDENCE", "manual delivery evidence is required");
        }
        requireText(evidence.deliveredBy(), "deliveredBy");
        requireText(evidence.verifiedBy(), "verifiedBy");
        requireText(evidence.verificationArtifact(), "verificationArtifact");
        requireText(evidence.purpose(), "purpose");
        if (evidence.deliveredAt() == null || evidence.verifiedAt() == null) {
            throw new ApiException(422, "INVALID_EVIDENCE", "delivery and verification times are required");
        }
        if (evidence.deliveredBy().equals(evidence.verifiedBy())) {
            throw new ApiException(422, "SAME_DELIVERER_AND_VERIFIER", "the verifier must differ from the deliverer");
        }
        String key = key(organizationId, orderId);
        Instant now = clock.instant();
        OrderDelivery base = deliveries.get(key);
        if (base == null) {
            base = new OrderDelivery(organizationId, orderId, DeliveryRoute.MANUAL, DeliveryStatus.PENDING,
                    null, 0, ORDER_VERSION, now);
        }
        if (base.status() == DeliveryStatus.DELIVERED) {
            throw new ApiException(409, "DELIVERY_CLOSED", "an electronically delivered order cannot become manual");
        }
        String detail = evidence.verificationArtifact() + "|" + evidence.purpose();
        append(key, base, DeliveryEventKind.MANUAL_DELIVERY, 0, evidence.deliveredBy(), detail,
                evidence.verifiedBy(), evidence.verifiedAt(), evidence.deliveredAt());
        OrderDelivery updated = new OrderDelivery(organizationId, orderId, DeliveryRoute.MANUAL,
                DeliveryStatus.MANUAL, base.submissionId(), base.attempts(), ORDER_VERSION, now);
        deliveries.put(key, updated);
        return updated;
    }

    /**
     * Reconcile an ambiguous send and allow exactly one subsequent resend of the same order.
     *
     * @param organizationId active tenant
     * @param subject        authenticated actor reconciling the send
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @param detail         recorded reconciliation evidence
     * @return the updated delivery projection
     */
    public OrderDelivery reconcileAmbiguous(String organizationId, String subject, Set<String> roles,
                                            String orderId, String detail) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        requireText(detail, "detail");
        String key = key(organizationId, orderId);
        OrderDelivery current = requireExisting(organizationId, orderId);
        if (current.status() != DeliveryStatus.AMBIGUOUS) {
            throw new ApiException(409, "NOT_AMBIGUOUS", "only an ambiguous send can be reconciled");
        }
        Instant now = clock.instant();
        append(key, current, DeliveryEventKind.RECONCILED, current.attempts(), subject, detail, null, null, now);
        OrderDelivery updated = new OrderDelivery(organizationId, orderId, current.route(), DeliveryStatus.PENDING,
                current.submissionId(), current.attempts(), ORDER_VERSION, now);
        deliveries.put(key, updated);
        return updated;
    }

    /**
     * Switch an order to the printed fallback without creating a second order or re-sending it.
     *
     * @param organizationId active tenant
     * @param subject        authenticated clinician subject
     * @param roles          freshly resolved active roles
     * @param orderId        tenant-local order identifier
     * @return the updated delivery projection
     */
    public OrderDelivery switchToPrint(String organizationId, String subject, Set<String> roles, String orderId) {
        requireScope(organizationId, subject, roles);
        requireText(orderId, "orderId");
        String key = key(organizationId, orderId);
        OrderDelivery current = requireExisting(organizationId, orderId);
        if (current.route() == DeliveryRoute.PRINT) {
            return current;
        }
        if (current.status() == DeliveryStatus.AMBIGUOUS) {
            throw new ApiException(409, "AMBIGUOUS_REQUIRES_RECONCILIATION",
                    "an ambiguous send must be reconciled before choosing another channel");
        }
        if (current.status() == DeliveryStatus.DELIVERED || current.status() == DeliveryStatus.ACCEPTED) {
            throw new ApiException(409, "DELIVERY_CLOSED", "a delivered or accepted order cannot switch to print");
        }
        Instant now = clock.instant();
        append(key, current, DeliveryEventKind.SWITCHED_TO_PRINT, current.attempts(), subject,
                "print fallback", null, null, now);
        OrderDelivery updated = new OrderDelivery(organizationId, orderId, DeliveryRoute.PRINT,
                DeliveryStatus.PRINT, current.submissionId(), current.attempts(), ORDER_VERSION, now);
        deliveries.put(key, updated);
        return updated;
    }

    /**
     * Read the immutable delivery facts of one order in the active tenant.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return an immutable snapshot, empty when the order has no delivery facts
     */
    public List<DeliveryEvent> history(String organizationId, String orderId) {
        requireText(organizationId, "organizationId");
        requireText(orderId, "orderId");
        return List.copyOf(events.getOrDefault(key(organizationId, orderId), List.of()));
    }

    /**
     * Read the current delivery projection of one order in the active tenant.
     *
     * @param organizationId active tenant
     * @param orderId        tenant-local order identifier
     * @return the projection, or empty when no delivery has been started
     */
    public java.util.Optional<OrderDelivery> delivery(String organizationId, String orderId) {
        requireText(organizationId, "organizationId");
        requireText(orderId, "orderId");
        return java.util.Optional.ofNullable(deliveries.get(key(organizationId, orderId)));
    }

    /**
     * Count the delivery projections currently tracked, for duplicate-order regression assertions.
     *
     * @return number of tenant-scoped orders with a delivery projection
     */
    public int trackedOrderCount() {
        return deliveries.size();
    }

    private void append(String key, OrderDelivery base, DeliveryEventKind kind, int attempt, String actor,
                        String detail, String verifiedBy, Instant verifiedAt, Instant occurredAt) {
        DeliveryEvent event = new DeliveryEvent(UUID.randomUUID().toString(), base.organizationId(),
                base.orderId(), ORDER_VERSION, kind, attempt, actor, detail, verifiedBy, verifiedAt, occurredAt);
        events.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(event);
    }

    private OrderDelivery requireExisting(String organizationId, String orderId) {
        OrderDelivery current = deliveries.get(key(organizationId, orderId));
        if (current == null) {
            throw new ApiException(404, "NOT_FOUND", "order delivery not found");
        }
        return current;
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
