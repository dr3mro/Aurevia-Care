package com.aurevia.messaging.conversations;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the MSG-02 conversation lifecycle.
 *
 * <p>A conversation is the single context between one verified patient
 * connection and one recipient unit. Messages are an append-only, sequenced
 * ledger; work-item facts are a separate append-only ledger. The current
 * projection carries a version, a lifecycle state, an optional assignee and two
 * read watermarks so unread counts are derived without reading message text.</p>
 */
public final class ConversationContract {

    private ConversationContract() {
    }

    /**
     * Lifecycle state of one conversation and its staff work item.
     *
     * <p>Every forward transition is legal except leaving {@code closed}, which
     * makes a closed thread read-only for all authorized viewers. A patient
     * reply to a resolved thread returns it to {@code awaiting_staff}, which is
     * the work-item reopen.</p>
     */
    public enum State {
        /** A patient message needs a staff response. */
        AWAITING_STAFF("awaiting_staff"),
        /** A staff message needs a patient response or acknowledgment. */
        AWAITING_PATIENT("awaiting_patient"),
        /** The work item was resolved with a reason; a reply can reopen it. */
        RESOLVED("resolved"),
        /** Closed and read-only; no further transition is permitted. */
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
            throw new IllegalArgumentException("unknown conversation state: " + value);
        }

        /**
         * Decide whether a transition is permitted.
         *
         * @return {@code true} for a no-op or any transition out of a non-closed state
         */
        public boolean canTransitionTo(State next) {
            return this == next || this != CLOSED;
        }
    }

    /**
     * Bounded set of recipient care units. A conversation always targets exactly
     * one unit; staff eligibility per unit is defined by
     * {@link com.aurevia.messaging.conversations.ConversationActions}.
     */
    public enum UnitKey {
        /** Clinic care unit. */
        CLINIC("clinic"),
        /** Laboratory care unit. */
        LAB("lab"),
        /** Radiology care unit. */
        RADIOLOGY("radiology"),
        /** Physical-therapy care unit. */
        PT("pt"),
        /** Pharmacy care unit. */
        PHARMACY("pharmacy");

        private final String wire;

        /**
         * Create a unit key with its stable wire value.
         *
         */
        UnitKey(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase unit key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored unit key.
         *
         * @return the matching unit
         * @throws IllegalArgumentException when the key is unknown
         */
        public static UnitKey from(String value) {
            for (UnitKey candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown care unit key: " + value);
        }
    }

    /**
     * Kind of message author. Delivery and notification are separate facts in
     * MSG-05 and are deliberately not represented here.
     */
    public enum SenderKind {
        /** Message authored by the verified patient. */
        PATIENT("patient"),
        /** Message authored by an authorized unit staff member. */
        STAFF("staff");

        private final String wire;

        /**
         * Create a sender kind with its stable wire key.
         *
         */
        SenderKind(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase sender key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored sender key.
         *
         * @return the matching sender kind
         * @throws IllegalArgumentException when the key is unknown
         */
        public static SenderKind from(String value) {
            for (SenderKind candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown message sender kind: " + value);
        }
    }

    /**
     * Append-only work-item fact vocabulary. Each type is a distinct staff
     * action and is never merged into another fact.
     */
    public enum WorkEventType {
        /** The conversation and its first work item were created. */
        CREATED("created"),
        /** A reply reopened a previously resolved work item. */
        REOPENED("reopened"),
        /** The work item was assigned to a named staff subject. */
        ASSIGNED("assigned"),
        /** The work item was transferred to another subject in the same unit. */
        TRANSFERRED("transferred"),
        /** The work item was resolved with a reason. */
        RESOLVED("resolved"),
        /** The thread was closed with a reason and became read-only. */
        CLOSED("closed");

        private final String wire;

        /**
         * Create a work-item event type with its stable wire key.
         *
         */
        WorkEventType(String wire) {
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
         * Parse a stored work-item event key.
         *
         * @return the matching event type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static WorkEventType from(String value) {
            for (WorkEventType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown work-item event: " + value);
        }
    }

    /**
     * Current, mutable projection of one conversation.
     *
     * @return the immutable value
     */
    public record Conversation(String id, int version, String patientId, String patientSubject,
                               String unitKey, String connectionId, String topic, State state,
                               String assigneeSubject, int patientReadSequence,
                               int staffReadSequence, Instant createdAt) {
    }

    /**
     * One immutable message in a conversation.
     *
     * @return the immutable value
     */
    public record Message(String id, String conversationId, int sequence, SenderKind senderKind,
                          String senderSubject, String senderRole, String body, Instant createdAt) {
    }

    /**
     * One immutable work-item fact.
     *
     * @return the immutable value
     */
    public record WorkItemEvent(String id, String conversationId, int conversationVersion,
                                WorkEventType eventType, String actorSubject, String fromState,
                                String toState, String reason, Instant occurredAt) {
    }

    /**
     * One conversation with its complete message and work-item trails.
     *
     * @return the immutable value
     */
    public record View(Conversation conversation, List<Message> messages, List<WorkItemEvent> events) {
        /**
         * Defensively copy both trails.
         *
         */
        public View {
            messages = List.copyOf(messages);
            events = List.copyOf(events);
        }
    }

    /**
     * One patient-inbox entry with its derived unread count.
     *
     * @return the immutable value
     */
    public record InboxEntry(Conversation conversation, int unreadCount) {
    }

    /**
     * A patient inbox.
     *
     * @return the immutable value
     */
    public record Inbox(List<InboxEntry> entries) {
        /**
         * Defensively copy the entries.
         *
         */
        public Inbox {
            entries = List.copyOf(entries);
        }
    }

    /**
     * A role-scoped staff work queue.
     *
     * @return the immutable value
     */
    public record WorkQueue(List<Conversation> entries) {
        /**
         * Defensively copy the entries.
         *
         */
        public WorkQueue {
            entries = List.copyOf(entries);
        }
    }

    /**
     * Open a conversation with a verified connection.
     *
     * @param patientId     owning patient identifier
     * @param patientSubject synthetic subject bound to the patient's account
     * @param unitKey       recipient care-unit key, checked against the connection
     * @param connectionId  connection identifier, verified server-side for patient callers
     * @param topic         optional topic, or {@code null}
     * @return the open command
     */
    public record OpenRequest(String patientId, String patientSubject, String unitKey,
                              String connectionId, String topic) {
    }

    /**
     * Post a reply to a conversation.
     *
     * @param body non-sensitive message text
     * @return the reply command
     */
    public record ReplyRequest(String body) {
    }

    /**
     * Assign an open work item to a named staff subject.
     *
     * @param assigneeSubject staff subject in the same unit
     * @param reason          non-sensitive assignment reason
     * @return the assignment command
     */
    public record AssignRequest(String assigneeSubject, String reason) {
    }

    /**
     * Transfer a work item to another subject in the same unit.
     *
     * @param toSubject target staff subject
     * @param reason    non-sensitive transfer reason
     * @return the transfer command
     */
    public record TransferRequest(String toSubject, String reason) {
    }

    /**
     * Resolve a work item with a reason.
     *
     * @param reason non-sensitive resolution reason
     * @return the resolve command
     */
    public record ResolveRequest(String reason) {
    }

    /**
     * Close a thread with a reason; it becomes read-only.
     *
     * @param reason non-sensitive closure reason
     * @return the close command
     */
    public record CloseRequest(String reason) {
    }
}
