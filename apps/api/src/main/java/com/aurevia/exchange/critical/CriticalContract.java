package com.aurevia.exchange.critical;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the XCH-05 critical-result workflow.
 *
 * <p>The types here carry no persistence or transport concerns. Each clinically
 * significant fact (verification, assignment, escalation attempt, read-back,
 * acknowledgment, follow-up and patient release) is a separate immutable event;
 * delivery or patient release never advances the clinical acknowledgment state.</p>
 */
public final class CriticalContract {

    private CriticalContract() {
    }

    /**
     * Clinical state of a critical result. Only the three forward transitions are
     * legal; a no-op is allowed so metadata assignment does not need a state move.
     */
    public enum State {
        /** Registered but not yet verified and owned. */
        UNVERIFIED("unverified"),
        /** Verified, owned and due; awaiting a human acknowledgment. */
        AWAITING_ACK("awaiting_ack"),
        /** An authorized clinician acknowledged the exact revision. */
        ACKNOWLEDGED("acknowledged"),
        /** Follow-up recorded and the alert closed. */
        CLOSED("closed");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         */
        State(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase state key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored state key.
         *
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static State from(String value) {
            for (State candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown critical-result state: " + value);
        }

        /**
         * Decide whether a transition is permitted.
         *
         * @return {@code true} for the three forward transitions or a no-op
         */
        public boolean canTransitionTo(State next) {
            if (this == next) {
                return true;
            }
            return (this == UNVERIFIED && next == AWAITING_ACK)
                    || (this == AWAITING_ACK && next == ACKNOWLEDGED)
                    || (this == ACKNOWLEDGED && next == CLOSED);
        }
    }

    /**
     * Append-only event vocabulary. Each type is a distinct clinical or
     * operational fact and is never merged into another event.
     */
    public enum EventType {
        /** The critical finding was registered. */
        REGISTERED("registered"),
        /** A responsible owner, due time and approved escalation policy were recorded. */
        OWNER_ASSIGNED("owner_assigned"),
        /** An authorized verifier confirmed the finding before any alerting. */
        VERIFIED("verified"),
        /** One escalation notification attempt, delivered or failed. */
        ESCALATION_ATTEMPT("escalation_attempt"),
        /** The receiver read the critical value back verbatim. */
        READ_BACK("read_back"),
        /** An authorized clinician acknowledged the exact revision. */
        ACKNOWLEDGED("acknowledged"),
        /** Clinical follow-up recorded after acknowledgment. */
        FOLLOW_UP("follow_up"),
        /** Patient release recorded; deliberately does not acknowledge the alert. */
        PATIENT_RELEASED("patient_released"),
        /** No active, approved backup recipient was available. */
        ROSTER_GAP("roster_gap");

        private final String wire;

        /**
         * Create an event type with its stable wire key.
         *
         */
        EventType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase event key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored event key.
         *
         * @return the matching event type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static EventType from(String value) {
            for (EventType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown critical-result event: " + value);
        }
    }

    /**
     * Current, mutable projection of one critical result.
     *
     * @return the immutable value
     */
    public record Result(String id, int version, String patientId, String sourceSystem, String sourceId,
                         String orderReference, String accession, String resultSummary,
                         String observedValue, String unit, boolean readBackRequired, State state,
                         String verifiedBySubject, Instant verifiedAt, String ownerSubject,
                         Instant dueAt, String primaryChannel, String backupChannel,
                         String backupRecipient, Instant createdAt) {
    }

    /**
     * One immutable fact in a critical-result trail.
     *
     * @return the immutable value
     */
    public record Event(String id, String criticalResultId, int criticalResultVersion,
                        EventType eventType, String actorId, String recipient, String channel,
                        String outcome, String detail, Instant occurredAt) {
    }

    /**
     * One result together with its complete event trail.
     *
     * @return the immutable value
     */
    public record View(Result result, List<Event> events) {
        /**
         * Defensively copy the event trail.
         *
         */
        public View {
            events = List.copyOf(events);
        }
    }

    /**
     * Operational report of overdue alerts, roster gaps and failed notifications.
     *
     * @return the immutable value
     */
    public record Report(List<Result> overdue, List<Event> rosterGaps, List<Event> failedNotifications) {
        /**
         * Defensively copy every reported list.
         *
         */
        public Report {
            overdue = List.copyOf(overdue);
            rosterGaps = List.copyOf(rosterGaps);
            failedNotifications = List.copyOf(failedNotifications);
        }
    }

    /**
     * Register a critical finding.
     *
     * @param patientId        owning patient
     * @param sourceSystem     source system name
     * @param sourceId         source identifier
     * @param orderReference   originating order reference
     * @param accession        source accession
     * @param resultSummary    synthetic narrative
     * @param observedValue    observed value
     * @param unit             observation unit
     * @param readBackRequired whether a read-back is required
     * @return the registration command
     * @return the immutable value
     */
    public record RegisterRequest(String patientId, String sourceSystem, String sourceId,
                                  String orderReference, String accession, String resultSummary,
                                  String observedValue, String unit, Boolean readBackRequired) {
    }

    /**
     * Assign a responsible owner, due time and approved escalation policy.
     *
     * @param ownerSubject responsible clinician subject
     * @param dueAt        ISO-8601 acknowledgment deadline
     * @return the assignment command
     * @return the immutable value
     */
    public record AssignRequest(String ownerSubject, String dueAt) {
    }

    /**
     * Record the verifier's confirmation.
     *
     * @param note non-sensitive verification note
     * @return the verification command
     * @return the immutable value
     */
    public record VerifyRequest(String note) {
    }

    /**
     * Record one escalation notification attempt.
     *
     * @param channel    channel used
     * @param recipient  notified recipient
     * @param outcome    {@code delivered} or {@code failed}
     * @param detail     non-sensitive detail
     * @param systemDown whether the primary channel is unavailable
     * @return the escalation command
     * @return the immutable value
     */
    public record EscalateRequest(String channel, String recipient, String outcome,
                                  String detail, Boolean systemDown) {
    }

    /**
     * Record a verbatim read-back of the critical value.
     *
     * @param repeatedValue value repeated by the receiver
     * @return the read-back command
     * @return the immutable value
     */
    public record ReadBackRequest(String repeatedValue) {
    }

    /**
     * Acknowledge the alert as an authorized clinician.
     *
     * @param note non-sensitive acknowledgment note
     * @return the acknowledgment command
     * @return the immutable value
     */
    public record AcknowledgeRequest(String note) {
    }

    /**
     * Record clinical follow-up and close the alert.
     *
     * @param note non-sensitive follow-up note
     * @return the follow-up command
     * @return the immutable value
     */
    public record FollowUpRequest(String note) {
    }

    /**
     * Record that the patient release was issued. This never acknowledges the alert.
     *
     * @param note non-sensitive release note
     * @return the release command
     * @return the immutable value
     */
    public record ReleaseRequest(String note) {
    }
}
