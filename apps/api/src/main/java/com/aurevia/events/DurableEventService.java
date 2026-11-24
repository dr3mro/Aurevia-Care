package com.aurevia.events;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.api.IfMatch;
import com.aurevia.events.DurableEventContract.AuditAction;
import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.ExternalEvent;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.LinkRequest;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.ProcessingOutcome;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventContract.ReplayRecord;
import com.aurevia.events.DurableEventContract.ReplayRequest;
import com.aurevia.events.DurableEventContract.Resolution;
import com.aurevia.events.DurableEventContract.RetryResult;
import com.aurevia.events.DurableEventContract.ReviewRequest;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-05 durable delivery domain service.
 *
 * <p>A publish commits the outbox event, its append-only audit fact and its
 * idempotency key together. Event ids are server-generated and immutable, and
 * each state change advances a per-aggregate version that callers must match
 * with an {@code If-Match} precondition. External events are deduplicated inside
 * a sender and type namespace; out-of-order changes are held rather than
 * overwriting a later revision, and every processing outcome is recorded.
 * Transient failures retry with bounded backoff before entering a restricted,
 * separately reviewed dead-letter queue. Replays keep the original event
 * identity and require both an authorized role and a reason. External data is
 * linked only after order and patient identifiers are confirmed in the active
 * tenant.</p>
 */
@Service
public class DurableEventService {

    /** Purpose stamped on every ARC-05 audit fact. */
    public static final String PURPOSE = "DELIVERY";

    /** Maximum number of transmission attempts before dead-lettering. */
    public static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_SECONDS = 1L;
    private static final long MAX_BACKOFF_SECONDS = 60L;
    private static final int MAX_REASON_LENGTH = 256;
    private static final String AUDIT_RESOURCE = "DELIVERY";
    private static final String EXHAUSTED_REASON = "retry budget exhausted";

    private final DeliveryStore store;
    private final DeliveryActions actions;
    private final Clock clock;

    /**
     * Create the durable delivery service.
     *
     * @param store   tenant-scoped delivery store
     * @param actions bounded action matrix
     * @param clock   application clock for deterministic server instants
     */
    public DurableEventService(DeliveryStore store, DeliveryActions actions, Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * Compute the bounded exponential backoff for an attempt number.
     *
     * @param attempt zero-based attempt number
     * @return seconds to wait, capped at {@value #MAX_BACKOFF_SECONDS}
     */
    public static long backoffSeconds(int attempt) {
        return Math.min(BASE_BACKOFF_SECONDS << attempt, MAX_BACKOFF_SECONDS);
    }

    /**
     * Commit one outbox event with its audit fact and idempotency key.
     *
     * <p>A repeated key with the same payload digest returns the original event;
     * the same key with a different digest is a conflict, never a second effect.</p>
     *
     * @param request validated publish command
     * @return the committed or originally committed event
     * @throws ApiException on invalid input, missing context, denial or key conflict
     */
    public OutboxEvent publish(PublishRequest request) {
        String aggregateId = DeliveryInputs.uuid(request.aggregateId(), "aggregateId");
        int version = DeliveryInputs.version(request.aggregateVersion(), "aggregateVersion");
        String sender = DeliveryInputs.token(request.sender(), "sender");
        String eventType = DeliveryInputs.token(request.eventType(), "eventType");
        String namespace = DeliveryInputs.token(request.namespace(), "namespace");
        String digest = DeliveryInputs.digest(request.payloadDigest());
        String key = DeliveryInputs.token(request.idempotencyKey(), "idempotencyKey");
        TenantContext context = requireContext();
        authorize(context, AuditAction.PUBLISH, aggregateId);

        Optional<OutboxEvent> existing = store.byIdempotencyKey(context.organizationId(), sender, eventType, key);
        if (existing.isPresent()) {
            OutboxEvent first = existing.orElseThrow();
            if (!first.payloadDigest().equals(digest)) {
                throw deny(context, AuditAction.PUBLISH, first.eventId(), 409, "IDEMPOTENCY_CONFLICT");
            }
            append(context, AuditAction.PUBLISH, first.eventId(), Outcome.DUPLICATE);
            return first;
        }

        OutboxEvent event = new OutboxEvent(DeliveryInputs.newId(), context.organizationId(), aggregateId,
                version, sender, eventType, namespace, digest, key, DeliveryState.PENDING, 0, null,
                clock.instant(), null);
        return store.publishAtomically(context.organizationId(), event,
                audit(context, AuditAction.PUBLISH, event.eventId(), Outcome.SUCCESS));
    }

    /**
     * Mark a pending event as handed to the partner channel.
     *
     * @param eventId event id
     * @param ifMatch expected aggregate version precondition
     * @return the new event snapshot
     * @throws ApiException on invalid input, missing context, denial, concealment or stale version
     */
    public OutboxEvent markSent(String eventId, String ifMatch) {
        return transition(eventId, ifMatch, DeliveryState.SENT, null, AuditAction.MARK_SENT);
    }

    /**
     * Mark a sent event as delivered.
     *
     * @param eventId event id
     * @param ifMatch expected aggregate version precondition
     * @return the new event snapshot
     * @throws ApiException on invalid input, missing context, denial, concealment or stale version
     */
    public OutboxEvent markDelivered(String eventId, String ifMatch) {
        return transition(eventId, ifMatch, DeliveryState.DELIVERED, null, AuditAction.MARK_DELIVERED);
    }

    /**
     * Record a failed transmission attempt.
     *
     * @param eventId event id
     * @param ifMatch expected aggregate version precondition
     * @param reason  non-sensitive failure reason
     * @return the new event snapshot
     * @throws ApiException on invalid input, missing context, denial, concealment or stale version
     */
    public OutboxEvent markFailed(String eventId, String ifMatch, String reason) {
        String detail = DeliveryInputs.text(reason, "reason", MAX_REASON_LENGTH);
        return transition(eventId, ifMatch, DeliveryState.FAILED, detail, AuditAction.MARK_FAILED);
    }

    /**
     * Schedule a bounded-backoff retry, or dead-letter when the budget is spent.
     *
     * @param eventId event id
     * @param ifMatch expected aggregate version precondition
     * @return the requeued event, or the dead-letter entry when exhausted
     * @throws ApiException on invalid input, missing context, denial, concealment or stale version
     */
    public RetryResult retry(String eventId, String ifMatch) {
        String id = DeliveryInputs.uuid(eventId, "eventId");
        int expectedVersion = IfMatch.parse(ifMatch);
        TenantContext context = requireContext();
        authorize(context, AuditAction.RETRY_SCHEDULED, id);
        OutboxEvent current = requireEvent(context, id, AuditAction.RETRY_SCHEDULED);
        requireVersion(context, current, expectedVersion, AuditAction.RETRY_SCHEDULED);
        if (current.state() != DeliveryState.FAILED) {
            throw deny(context, AuditAction.RETRY_SCHEDULED, id, 409, "NOT_FAILED");
        }
        int nextAttempt = current.attempt() + 1;
        if (nextAttempt > MAX_ATTEMPTS) {
            DeadLetterEntry entry = deadLetterFact(context, current, EXHAUSTED_REASON);
            store.putDeadLetter(context.organizationId(), entry);
            store.putOutboxEvent(context.organizationId(), snapshot(current, DeliveryState.DEAD_LETTERED,
                    current.attempt(), null, current.detail(), current.aggregateVersion() + 1));
            append(context, AuditAction.DEAD_LETTER, id, Outcome.SUCCESS);
            return RetryResult.deadLettered(entry);
        }
        OutboxEvent requeued = snapshot(current, DeliveryState.PENDING, nextAttempt,
                clock.instant().plusSeconds(backoffSeconds(nextAttempt)), null, current.aggregateVersion() + 1);
        store.putOutboxEvent(context.organizationId(), requeued);
        append(context, AuditAction.RETRY_SCHEDULED, id, Outcome.SUCCESS);
        return RetryResult.requeued(requeued);
    }

    /**
     * Move a failed event into the restricted dead-letter queue.
     *
     * @param eventId event id
     * @param reason  non-sensitive dead-letter reason
     * @return the created dead-letter entry
     * @throws ApiException on invalid input, missing context, denial, concealment or wrong state
     */
    public DeadLetterEntry deadLetter(String eventId, String reason) {
        String id = DeliveryInputs.uuid(eventId, "eventId");
        String detail = DeliveryInputs.text(reason, "reason", MAX_REASON_LENGTH);
        TenantContext context = requireContext();
        authorize(context, AuditAction.DEAD_LETTER, id);
        OutboxEvent current = requireEvent(context, id, AuditAction.DEAD_LETTER);
        if (current.state() != DeliveryState.FAILED) {
            throw deny(context, AuditAction.DEAD_LETTER, id, 409, "NOT_FAILED");
        }
        DeadLetterEntry entry = deadLetterFact(context, current, detail);
        store.putDeadLetter(context.organizationId(), entry);
        store.putOutboxEvent(context.organizationId(), snapshot(current, DeliveryState.DEAD_LETTERED,
                current.attempt(), null, current.detail(), current.aggregateVersion() + 1));
        append(context, AuditAction.DEAD_LETTER, id, Outcome.SUCCESS);
        return entry;
    }

    /**
     * Review a dead-letter entry with an authorized resolution and reason.
     *
     * @param request validated review command
     * @return the reviewed entry with author, instant, reason and resolution
     * @throws ApiException on invalid input, missing context, denial, concealment or repeat review
     */
    public DeadLetterEntry reviewDeadLetter(ReviewRequest request) {
        String id = DeliveryInputs.uuid(request.eventId(), "eventId");
        Resolution resolution = DeliveryInputs.resolution(request.resolution());
        String reason = DeliveryInputs.text(request.reason(), "reason", MAX_REASON_LENGTH);
        TenantContext context = requireContext();
        authorize(context, AuditAction.REVIEW, id);
        DeadLetterEntry current = store.deadLetter(context.organizationId(), id)
                .orElseThrow(() -> deny(context, AuditAction.REVIEW, id, 404, "NOT_FOUND"));
        if (current.reviewer() != null) {
            throw deny(context, AuditAction.REVIEW, id, 409, "ALREADY_REVIEWED");
        }
        DeadLetterEntry reviewed = new DeadLetterEntry(current.eventId(), current.organizationId(),
                current.reason(), current.attempt(), current.failedAt(), context.subject(),
                clock.instant(), resolution, reason);
        store.putDeadLetter(context.organizationId(), reviewed);
        if (resolution == Resolution.REQUEUED) {
            OutboxEvent event = requireEvent(context, id, AuditAction.REVIEW);
            store.putOutboxEvent(context.organizationId(), snapshot(event, DeliveryState.PENDING, 0,
                    null, null, event.aggregateVersion() + 1));
        }
        append(context, AuditAction.REVIEW, id, Outcome.SUCCESS);
        return reviewed;
    }

    /**
     * Consume one external event, deduplicating it inside its sender and type
     * namespace and recording the processing outcome.
     *
     * @param event validated external event
     * @return the accepted, duplicate, held or rejected receipt
     * @throws ApiException on invalid input, missing context or denial
     */
    public ConsumerReceipt consume(ExternalEvent event) {
        String externalId = DeliveryInputs.token(event.externalEventId(), "externalEventId");
        String sender = DeliveryInputs.token(event.sender(), "sender");
        String eventType = DeliveryInputs.token(event.eventType(), "eventType");
        String namespace = DeliveryInputs.token(event.namespace(), "namespace");
        String aggregateId = DeliveryInputs.uuid(event.aggregateId(), "aggregateId");
        int version = DeliveryInputs.version(event.aggregateVersion(), "aggregateVersion");
        String orderId = DeliveryInputs.optionalUuid(event.orderId(), "orderId");
        String patientId = DeliveryInputs.optionalUuid(event.patientId(), "patientId");
        DeliveryInputs.digest(event.payloadDigest());
        TenantContext context = requireContext();
        authorize(context, AuditAction.CONSUME, externalId);

        Optional<ConsumerReceipt> known = store.receipt(context.organizationId(), namespace, sender,
                eventType, externalId);
        if (known.isPresent()) {
            append(context, AuditAction.CONSUME, externalId, Outcome.DUPLICATE);
            return known.orElseThrow();
        }

        OptionalInt highest = store.maxVersion(context.organizationId(), namespace, sender, eventType, aggregateId);
        if (highest.isPresent() && highest.getAsInt() >= version) {
            return recordReceipt(context, externalId, namespace, sender, eventType, aggregateId, version,
                    orderId, patientId, ProcessingOutcome.HELD_OUT_OF_ORDER, "out_of_order", Outcome.HELD);
        }
        if (orderId != null && !store.orderExists(context.organizationId(), orderId)) {
            return recordReceipt(context, externalId, namespace, sender, eventType, aggregateId, version,
                    orderId, patientId, ProcessingOutcome.REJECTED, "order_not_found", Outcome.NOT_FOUND);
        }
        if (patientId != null && !store.patientExists(context.organizationId(), patientId)) {
            return recordReceipt(context, externalId, namespace, sender, eventType, aggregateId, version,
                    orderId, patientId, ProcessingOutcome.REJECTED, "patient_not_found", Outcome.NOT_FOUND);
        }
        return recordReceipt(context, externalId, namespace, sender, eventType, aggregateId, version,
                orderId, patientId, ProcessingOutcome.ACCEPTED, null, Outcome.SUCCESS);
    }

    /**
     * Replay an event with its original immutable identity, reason and authorization.
     *
     * @param request validated replay command
     * @return the authorization-bearing replay fact
     * @throws ApiException on invalid input, missing context, denial or concealment
     */
    public ReplayRecord replay(ReplayRequest request) {
        String id = DeliveryInputs.uuid(request.eventId(), "eventId");
        String reason = DeliveryInputs.text(request.reason(), "reason", MAX_REASON_LENGTH);
        TenantContext context = requireContext();
        authorize(context, AuditAction.REPLAY, id);
        requireEvent(context, id, AuditAction.REPLAY);
        ReplayRecord replay = new ReplayRecord(DeliveryInputs.newId(), context.organizationId(), id,
                reason, context.subject(), clock.instant());
        store.putReplay(context.organizationId(), replay);
        append(context, AuditAction.REPLAY, id, Outcome.SUCCESS);
        return replay;
    }

    /**
     * Link consumed external data only after confirming order and patient
     * identifiers and an accepted receipt for the exact external event.
     *
     * @param request validated link command
     * @return the provenance link fact
     * @throws ApiException on invalid input, missing context, denial or concealment
     */
    public ExternalLink linkExternal(LinkRequest request) {
        String externalId = DeliveryInputs.token(request.externalEventId(), "externalEventId");
        String orderId = DeliveryInputs.uuid(request.orderId(), "orderId");
        String patientId = DeliveryInputs.uuid(request.patientId(), "patientId");
        String target = DeliveryInputs.uuid(request.targetAggregateId(), "targetAggregateId");
        TenantContext context = requireContext();
        authorize(context, AuditAction.LINK_EXTERNAL, externalId);
        ConsumerReceipt receipt = store.receiptById(context.organizationId(), externalId)
                .orElseThrow(() -> deny(context, AuditAction.LINK_EXTERNAL, externalId, 404, "NOT_FOUND"));
        boolean confirmed = receipt.outcome() == ProcessingOutcome.ACCEPTED
                && orderId.equals(receipt.orderId())
                && patientId.equals(receipt.patientId())
                && store.orderExists(context.organizationId(), orderId)
                && store.patientExists(context.organizationId(), patientId);
        if (!confirmed) {
            throw deny(context, AuditAction.LINK_EXTERNAL, externalId, 404, "NOT_FOUND");
        }
        ExternalLink link = new ExternalLink(DeliveryInputs.newId(), context.organizationId(), externalId,
                orderId, patientId, target, context.subject(), clock.instant());
        store.putLink(context.organizationId(), link);
        append(context, AuditAction.LINK_EXTERNAL, externalId, Outcome.SUCCESS);
        return link;
    }

    /**
     * Read the restricted dead-letter queue; the read itself is audited.
     *
     * @return dead-letter entries in insertion order
     * @throws ApiException on missing context or denial
     */
    public List<DeadLetterEntry> deadLetters() {
        TenantContext context = requireContext();
        authorize(context, AuditAction.REVIEW, AUDIT_RESOURCE);
        append(context, AuditAction.REVIEW, AUDIT_RESOURCE, Outcome.SUCCESS);
        return store.deadLetters(context.organizationId());
    }

    /**
     * Read the tenant's audit trail; reading audit is itself audited.
     *
     * @return audit facts in insertion order
     * @throws ApiException on missing context or denial
     */
    public List<DeliveryAuditEvent> auditTrail() {
        TenantContext context = requireContext();
        authorize(context, AuditAction.AUDIT_READ, AUDIT_RESOURCE);
        append(context, AuditAction.AUDIT_READ, AUDIT_RESOURCE, Outcome.SUCCESS);
        return store.auditEvents(context.organizationId());
    }

    private ConsumerReceipt recordReceipt(TenantContext context, String externalId, String namespace,
                                          String sender, String eventType, String aggregateId, int version,
                                          String orderId, String patientId, ProcessingOutcome outcome,
                                          String detail, Outcome auditOutcome) {
        ConsumerReceipt receipt = new ConsumerReceipt(DeliveryInputs.newId(), context.organizationId(),
                externalId, sender, eventType, namespace, aggregateId, version, orderId, patientId,
                outcome, detail, clock.instant(), context.subject());
        store.putReceipt(context.organizationId(), receipt);
        append(context, AuditAction.CONSUME, receipt.externalEventId(), auditOutcome);
        return receipt;
    }

    private OutboxEvent transition(String eventId, String ifMatch, DeliveryState target, String detail,
                                   AuditAction action) {
        String id = DeliveryInputs.uuid(eventId, "eventId");
        int expectedVersion = IfMatch.parse(ifMatch);
        TenantContext context = requireContext();
        authorize(context, action, id);
        OutboxEvent current = requireEvent(context, id, action);
        requireVersion(context, current, expectedVersion, action);
        if (!allowedTransition(current.state(), target)) {
            throw deny(context, action, id, 409, "ILLEGAL_TRANSITION");
        }
        OutboxEvent advanced = snapshot(current, target, current.attempt(), current.nextAttemptAt(),
                detail == null ? current.detail() : detail, current.aggregateVersion() + 1);
        store.putOutboxEvent(context.organizationId(), advanced);
        append(context, action, id, Outcome.SUCCESS);
        return advanced;
    }

    private boolean allowedTransition(DeliveryState current, DeliveryState target) {
        return switch (current) {
            case PENDING -> target == DeliveryState.SENT || target == DeliveryState.FAILED;
            case SENT -> target == DeliveryState.DELIVERED || target == DeliveryState.FAILED;
            case DELIVERED -> target == DeliveryState.FAILED;
            case FAILED, DEAD_LETTERED -> false;
        };
    }

    private OutboxEvent snapshot(OutboxEvent current, DeliveryState state, int attempt,
                                 java.time.Instant nextAttemptAt, String detail, int version) {
        return new OutboxEvent(current.eventId(), current.organizationId(), current.aggregateId(), version,
                current.sender(), current.eventType(), current.namespace(), current.payloadDigest(),
                current.idempotencyKey(), state, attempt, nextAttemptAt, current.createdAt(), detail);
    }

    private DeadLetterEntry deadLetterFact(TenantContext context, OutboxEvent event, String reason) {
        return new DeadLetterEntry(event.eventId(), context.organizationId(), reason, event.attempt(),
                clock.instant(), null, null, null, null);
    }

    private OutboxEvent requireEvent(TenantContext context, String id, AuditAction action) {
        return store.outboxEvent(context.organizationId(), id)
                .orElseThrow(() -> deny(context, action, id, 404, "NOT_FOUND"));
    }

    private void requireVersion(TenantContext context, OutboxEvent current, int expected, AuditAction action) {
        if (current.aggregateVersion() != expected) {
            throw deny(context, action, current.eventId(), 412, "STALE_VERSION");
        }
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private void authorize(TenantContext context, AuditAction action, String resourceId) {
        if (!store.hasActiveMembership(context.organizationId(), context.subject())) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN");
        }
        boolean permitted = store.activeRoles(context.organizationId(), context.subject()).stream()
                .anyMatch(role -> actions.allows(action, role));
        if (!permitted) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN");
        }
    }

    private ApiException deny(TenantContext context, AuditAction action, String resourceId,
                              int status, String code) {
        Outcome outcome = switch (status) {
            case 404 -> Outcome.NOT_FOUND;
            case 409 -> Outcome.CONFLICT;
            case 412 -> Outcome.STALE;
            default -> Outcome.DENIED;
        };
        append(context, action, resourceId, outcome);
        return new ApiException(status, code, code);
    }

    private DeliveryAuditEvent audit(TenantContext context, AuditAction action, String resourceId,
                                     Outcome outcome) {
        return new DeliveryAuditEvent(DeliveryInputs.newId(), context.organizationId(), context.subject(),
                PURPOSE, action, resourceId, context.correlationId(), outcome, clock.instant());
    }

    private void append(TenantContext context, AuditAction action, String resourceId, Outcome outcome) {
        store.appendAudit(context.organizationId(), audit(context, action, resourceId, outcome));
    }
}
