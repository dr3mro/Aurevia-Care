package com.aurevia.events;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for ARC-05 durable delivery.
 *
 * <p>Every record is tenant-owned and carries only non-sensitive delivery
 * metadata: opaque identifiers, server timestamps, stable wire keys and a
 * payload digest. No clinical payload, attachment bytes or credential appears
 * in an outbox event, receipt, audit fact or dead-letter entry. Event and fact
 * identifiers are immutable; ordering is expressed with a per-aggregate
 * version, never with wall-clock arrival order.</p>
 */
public final class DurableEventContract {

    private DurableEventContract() {
    }

    /**
     * Transmission state of one immutable outbox event.
     */
    public enum DeliveryState {
        /** Committed to the outbox, awaiting dispatch. */
        PENDING("pending"),
        /** Handed to the partner channel. */
        SENT("sent"),
        /** Delivery confirmed by the partner. */
        DELIVERED("delivered"),
        /** A transient send attempt failed and a retry may be scheduled. */
        FAILED("failed"),
        /** Retry budget exhausted; visible only through reviewed dead-letter handling. */
        DEAD_LETTERED("dead_lettered");

        private final String wire;

        DeliveryState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored or supplied state key.
         *
         * @param value state key
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static DeliveryState from(String value) {
            for (DeliveryState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown delivery state: " + value);
        }
    }

    /**
     * Outcome recorded when an external event is consumed.
     */
    public enum ProcessingOutcome {
        /** A new event was accepted inside its sender and type namespace. */
        ACCEPTED("accepted"),
        /** The same external event was already processed; no effect was repeated. */
        DUPLICATE("duplicate"),
        /** A later aggregate version was already processed; this change is held. */
        HELD_OUT_OF_ORDER("held_out_of_order"),
        /** Order or patient identifiers could not be confirmed in this tenant. */
        REJECTED("rejected");

        private final String wire;

        ProcessingOutcome(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase outcome key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored processing outcome key.
         *
         * @param value outcome key
         * @return the matching outcome
         * @throws IllegalArgumentException when the key is unknown
         */
        public static ProcessingOutcome from(String value) {
            for (ProcessingOutcome candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown processing outcome: " + value);
        }
    }

    /**
     * The complete ARC-05 action vocabulary.
     */
    public enum AuditAction {
        /** An outbox event was committed with its audit and deduplication key. */
        PUBLISH("publish"),
        /** An event was handed to the partner channel. */
        MARK_SENT("mark_sent"),
        /** Partner delivery was confirmed. */
        MARK_DELIVERED("mark_delivered"),
        /** A send attempt failed. */
        MARK_FAILED("mark_failed"),
        /** A bounded-backoff retry was scheduled. */
        RETRY_SCHEDULED("retry_scheduled"),
        /** An event entered the restricted dead-letter queue. */
        DEAD_LETTER("dead_letter"),
        /** A dead-letter entry was reviewed and resolved or requeued. */
        REVIEW("review"),
        /** An external event was consumed and deduplicated. */
        CONSUME("consume"),
        /** An event was replayed with its original identity. */
        REPLAY("replay"),
        /** An external event was linked to confirmed order and patient identifiers. */
        LINK_EXTERNAL("link_external"),
        /** The audit trail was read; reading audit is itself audited. */
        AUDIT_READ("audit_read");

        private final String wire;

        AuditAction(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase action key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored action key.
         *
         * @param value action key
         * @return the matching action
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AuditAction from(String value) {
            for (AuditAction candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown audit action: " + value);
        }
    }

    /**
     * Server-derived outcome of an audited delivery action.
     */
    public enum Outcome {
        /** The command completed. */
        SUCCESS("success"),
        /** The actor lacked active membership or permission. */
        DENIED("denied"),
        /** The resource did not exist in the active tenant. */
        NOT_FOUND("not_found"),
        /** The command conflicted with current state or an idempotency key. */
        CONFLICT("conflict"),
        /** The supplied aggregate version was stale. */
        STALE("stale"),
        /** A duplicate event or idempotent retry returned the original fact. */
        DUPLICATE("duplicate"),
        /** An out-of-order external event was held for review. */
        HELD("held");

        private final String wire;

        Outcome(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase outcome key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored outcome key.
         *
         * @param value outcome key
         * @return the matching outcome
         * @throws IllegalArgumentException when the key is unknown
         */
        public static Outcome from(String value) {
            for (Outcome candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown audit outcome: " + value);
        }
    }

    /**
     * Resolution chosen by an authorized dead-letter reviewer.
     */
    public enum Resolution {
        /** The event is requeued with a fresh retry budget. */
        REQUEUED("requeued"),
        /** The event is resolved without further delivery. */
        RESOLVED("resolved");

        private final String wire;

        Resolution(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase resolution key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored resolution key.
         *
         * @param value resolution key
         * @return the matching resolution
         * @throws IllegalArgumentException when the key is unknown
         */
        public static Resolution from(String value) {
            for (Resolution candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown resolution: " + value);
        }
    }

    /**
     * Immutable-identity outbox event. State and version advance through new
     * snapshots of the same event id; the id and idempotency key never change.
     *
     * @param eventId          server-generated opaque, immutable event id
     * @param organizationId   owning tenant
     * @param aggregateId      local aggregate the event belongs to
     * @param aggregateVersion per-aggregate version, advanced on each transition
     * @param sender           emitting service or partner namespace member
     * @param eventType        stable event type key
     * @param namespace        deduplication namespace of the sender and type
     * @param payloadDigest    digest of the released snapshot, never the payload
     * @param idempotencyKey   tenant, sender and type scoped retry key
     * @param state            current transmission state
     * @param attempt          number of attempted transmissions
     * @param nextAttemptAt    earliest time a retry may be dispatched
     * @param createdAt        server commit instant
     * @param detail           non-sensitive failure or review detail
     */
    public record OutboxEvent(String eventId, String organizationId, String aggregateId,
                              int aggregateVersion, String sender, String eventType, String namespace,
                              String payloadDigest, String idempotencyKey, DeliveryState state,
                              int attempt, Instant nextAttemptAt, Instant createdAt, String detail) {
    }

    /**
     * Deduplication and processing-outcome receipt for one external event.
     *
     * @param receiptId        opaque receipt id
     * @param organizationId   owning tenant
     * @param externalEventId  partner event id, unique inside the sender and type namespace
     * @param sender           partner sender key
     * @param eventType        partner event type key
     * @param namespace        deduplication namespace
     * @param aggregateId      local aggregate the event is about
     * @param aggregateVersion partner aggregate version used for ordering
     * @param orderId          confirmed order identifier, or {@code null}
     * @param patientId        confirmed patient identifier, or {@code null}
     * @param outcome          recorded processing outcome
     * @param detail           stable non-sensitive outcome detail
     * @param processedAt      server processing instant
     * @param actorId          authenticated subject that consumed the event
     */
    public record ConsumerReceipt(String receiptId, String organizationId, String externalEventId,
                                  String sender, String eventType, String namespace, String aggregateId,
                                  int aggregateVersion, String orderId, String patientId,
                                  ProcessingOutcome outcome, String detail, Instant processedAt,
                                  String actorId) {
    }

    /**
     * An inbound partner event. All identifier fields are validated before use.
     *
     * @param externalEventId partner event id
     * @param sender          partner sender key
     * @param eventType       partner event type key
     * @param namespace       deduplication namespace
     * @param aggregateId     local aggregate the event is about
     * @param aggregateVersion partner aggregate version
     * @param orderId         order identifier to confirm, or {@code null}
     * @param patientId       patient identifier to confirm, or {@code null}
     * @param payloadDigest   digest of the partner payload, never the payload
     */
    public record ExternalEvent(String externalEventId, String sender, String eventType, String namespace,
                                String aggregateId, int aggregateVersion, String orderId, String patientId,
                                String payloadDigest) {
    }

    /**
     * Restricted dead-letter entry with a separate, audited review outcome.
     *
     * @param eventId      immutable original event id
     * @param organizationId owning tenant
     * @param reason       non-sensitive failure reason
     * @param attempt      attempts made before dead-lettering
     * @param failedAt     server dead-letter instant
     * @param reviewer     authorized reviewer subject, or {@code null} before review
     * @param reviewedAt   review instant, or {@code null} before review
     * @param resolution   review resolution, or {@code null} before review
     * @param reviewReason authorized reviewer reason, or {@code null} before review
     */
    public record DeadLetterEntry(String eventId, String organizationId, String reason, int attempt,
                                  Instant failedAt, String reviewer, Instant reviewedAt,
                                  Resolution resolution, String reviewReason) {
    }

    /**
     * Authorization-bearing replay fact. The original event identity is kept.
     *
     * @param replayId         opaque replay id
     * @param organizationId   owning tenant
     * @param originalEventId  immutable event id that was replayed
     * @param reason           authorized replay reason
     * @param authorizedBy     authorizing subject
     * @param replayedAt       server replay instant
     */
    public record ReplayRecord(String replayId, String organizationId, String originalEventId,
                               String reason, String authorizedBy, Instant replayedAt) {
    }

    /**
     * Provenance link created only after order and patient identifiers are confirmed.
     *
     * @param linkId           opaque link id
     * @param organizationId   owning tenant
     * @param externalEventId  consumed external event id
     * @param orderId          confirmed order identifier
     * @param patientId        confirmed patient identifier
     * @param targetAggregateId local chart or encounter aggregate
     * @param linkedBy         authorized linking subject
     * @param linkedAt         server link instant
     */
    public record ExternalLink(String linkId, String organizationId, String externalEventId,
                               String orderId, String patientId, String targetAggregateId,
                               String linkedBy, Instant linkedAt) {
    }

    /**
     * Append-only delivery audit fact.
     *
     * @param id             opaque audit id
     * @param organizationId owning tenant
     * @param actorId        authenticated subject
     * @param purpose        recorded business purpose
     * @param action         audited action
     * @param resourceId     event or resource id, never a clinical payload
     * @param correlationId  request correlation id
     * @param outcome        server-derived outcome
     * @param occurredAt     server instant
     */
    public record DeliveryAuditEvent(String id, String organizationId, String actorId, String purpose,
                                     AuditAction action, String resourceId, String correlationId,
                                     Outcome outcome, Instant occurredAt) {
    }

    /**
     * Command to publish one outbox event.
     *
     * @param aggregateId     local aggregate id
     * @param aggregateVersion caller version of the aggregate
     * @param sender          emitting service or partner key
     * @param eventType       stable event type key
     * @param namespace       deduplication namespace
     * @param payloadDigest   digest of the released snapshot
     * @param idempotencyKey  tenant, sender and type scoped retry key
     */
    public record PublishRequest(String aggregateId, int aggregateVersion, String sender, String eventType,
                                 String namespace, String payloadDigest, String idempotencyKey) {
    }

    /**
     * Result of one retry attempt: either a requeued event or a dead-letter entry.
     *
     * @param event      requeued event, or {@code null} when dead-lettered
     * @param deadLetter dead-letter entry, or {@code null} when requeued
     */
    public record RetryResult(OutboxEvent event, DeadLetterEntry deadLetter) {

        /**
         * Build a requeued result.
         *
         * @param event the requeued event
         * @return a result carrying only the event
         */
        public static RetryResult requeued(OutboxEvent event) {
            return new RetryResult(event, null);
        }

        /**
         * Build a dead-lettered result.
         *
         * @param entry the restricted dead-letter entry
         * @return a result carrying only the dead-letter entry
         */
        public static RetryResult deadLettered(DeadLetterEntry entry) {
            return new RetryResult(null, entry);
        }
    }

    /**
     * Command to review one dead-letter entry.
     *
     * @param eventId    original event id
     * @param resolution chosen resolution wire key
     * @param reason     authorized review reason
     */
    public record ReviewRequest(String eventId, String resolution, String reason) {
    }

    /**
     * Command to replay one event with its original identity.
     *
     * @param eventId original event id
     * @param reason  authorized replay reason
     */
    public record ReplayRequest(String eventId, String reason) {
    }

    /**
     * Command to link consumed external data to confirmed identifiers.
     *
     * @param externalEventId consumed external event id
     * @param orderId         order identifier to confirm
     * @param patientId       patient identifier to confirm
     * @param targetAggregateId local chart or encounter aggregate
     */
    public record LinkRequest(String externalEventId, String orderId, String patientId,
                              String targetAggregateId) {
    }
}
