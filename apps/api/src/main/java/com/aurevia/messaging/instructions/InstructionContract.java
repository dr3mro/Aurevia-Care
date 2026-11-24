package com.aurevia.messaging.instructions;

import java.time.Instant;
import java.util.List;

/**
 * Typed vocabulary for MSG-04 patient instructions.
 *
 * <p>The wire forms are stable lowercase keys so a later catalog change cannot
 * rewrite stored revisions. Clinical content is copy-on-write: a replacement
 * or withdrawal never mutates a sent revision, it links a new revision and
 * records a separate fact for the patient notification.</p>
 */
public final class InstructionContract {

    private InstructionContract() {
    }

    /** Site-relevant instruction types; each maps to an approved template family. */
    public enum Kind {
        /** Specimen collection preparation. */
        COLLECTION("collection"),
        /** Imaging preparation. */
        IMAGING_PREPARATION("imaging_preparation"),
        /** Physical-therapy home instructions. */
        THERAPY_HOME("therapy_home"),
        /** Visit follow-up. */
        VISIT_FOLLOW_UP("visit_follow_up"),
        /** Pharmacy pickup. */
        PHARMACY_PICKUP("pharmacy_pickup"),
        /** Non-specialized free-text instruction. */
        GENERAL("general");

        private final String wire;

        /**


         * @param wire


         */


        Kind(String wire) {
            this.wire = wire;
        }

        /** @return stable wire key */
        public String wire() {
            return wire;
        }

        /**
         * Parse a wire key.
         *
         * @return the matching kind
         * @throws IllegalArgumentException when the key is unknown
         */
        public static Kind from(String value) {
            for (Kind kind : values()) {
                if (kind.wire.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unknown instruction kind: " + value);
        }
    }

    /** The care relationship an instruction is bound to, when one exists. */
    public enum ContextType {
        /** Appointment. */
        APPOINTMENT("appointment"),
        /** Diagnostic order. */
        ORDER("order"),
        /** Specialty referral. */
        REFERRAL("referral"),
        /** Prescription. */
        PRESCRIPTION("prescription"),
        /** Clinical encounter. */
        ENCOUNTER("encounter");

        private final String wire;

        /**


         * @param wire


         */


        ContextType(String wire) {
            this.wire = wire;
        }

        /** @return stable wire key */
        public String wire() {
            return wire;
        }

        /**
         * Parse a wire key.
         *
         * @return the matching context type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static ContextType from(String value) {
            for (ContextType context : values()) {
                if (context.wire.equals(value)) {
                    return context;
                }
            }
            throw new IllegalArgumentException("unknown instruction context: " + value);
        }
    }

    /** Revision lifecycle. Sent revisions are immutable. */
    public enum State {
        /** Not yet published; never exposed to the patient. */
        DRAFT("draft"),
        /** Published to the verified patient connection. */
        SENT("sent"),
        /** Superseded by a linked later revision. */
        SUPERSEDED("superseded"),
        /** Withdrawn; future access stops but history is retained. */
        WITHDRAWN("withdrawn");

        private final String wire;

        /**


         * @param wire


         */


        State(String wire) {
            this.wire = wire;
        }

        /** @return stable wire key */
        public String wire() {
            return wire;
        }

        /**
         * Parse a wire key.
         *
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static State from(String value) {
            for (State state : values()) {
                if (state.wire.equals(value)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("unknown instruction state: " + value);
        }

        /**
         * Guard the legal transitions. A no-op is allowed so an idempotent
         * retry of a terminal command does not corrupt history.
         *
         * @return {@code true} when the transition is legal
         */
        public boolean canTransitionTo(State target) {
            if (target == this) {
                return true;
            }
            return this == SENT && (target == SUPERSEDED || target == WITHDRAWN);
        }
    }

    /** Immutable facts recorded alongside revisions for patient notification. */
    public enum FactType {
        /** First publication. */
        SENT("sent"),
        /** A replacement was published. */
        REPLACED("replaced"),
        /** The prior revision was marked superseded. */
        SUPERSEDED("superseded"),
        /** The revision was withdrawn. */
        WITHDRAWN("withdrawn"),
        /** Notification dispatch was requested; delivery is a separate fact. */
        DISPATCHED("dispatched");

        private final String wire;

        /**


         * @param wire


         */


        FactType(String wire) {
            this.wire = wire;
        }

        /** @return stable wire key */
        public String wire() {
            return wire;
        }

        /**
         * Parse a wire key.
         *
         * @return the matching fact type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static FactType from(String value) {
            for (FactType fact : values()) {
                if (fact.wire.equals(value)) {
                    return fact;
                }
            }
            throw new IllegalArgumentException("unknown instruction fact: " + value);
        }
    }

    /**
     * An approved, unit-owned template version.
     *
     */
    public record Template(String id, String unitId, Kind kind, String title, String body,
                           String language, int version, String approvedBy, Instant approvedAt,
                           boolean clinical, ContextType contextType) {
    }

    /**
     * An immutable instruction revision.
     *
 * @return this record
 */
public record Revision(String threadId, int revision, String templateId, Kind kind, String title,
                           String text, String language, String authorSubject, String authorRole,
                           String unitId, String connectionId, String patientId, Instant issuedAt,
                           Instant actionDueAt, ContextType contextType, String contextId,
                           List<String> attachmentIds, State state, Integer supersededBy,
                           Integer replacesRevision, String withdrawalReason) {
        /**
         * Defensive copy keeps a published revision immutable for its readers.
         *
         * @throws NullPointerException when attachmentIds is null
         */
        public Revision {
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /**
     * A patient-visible fact about a revision.
     *
 * @return this record
 */
public record Fact(String threadId, int revision, FactType type, String recipient,
                       Instant occurredAt, String note) {
    }

    /**
     * Read model for one thread.
     *
 * @return this record
 */
public record View(Revision current, List<Revision> history, List<Fact> facts) {
        /**
         * Defensive copies keep the read model independent of its source lists.
         *
         * @throws NullPointerException when history or facts is null
         */
        public View {
            history = List.copyOf(history);
            facts = List.copyOf(facts);
        }
    }

    /**
     * Approve a unit-owned template version.
     *
 * @return this record
 */
public record DefineTemplateRequest(String unitId, String templateId, Kind kind, String title,
                                        String body, String language, boolean clinical,
                                        ContextType contextType) {
    }

    /**
     * Send an instruction from an approved template or bounded free text.
     *
 * @return this record
 */
public record SendRequest(String patientId, String connectionId, String unitId, String templateId,
                              Kind kind, String title, String text, String language,
                              String actionDueAt, ContextType contextType, String contextId,
                              List<String> attachmentIds) {
        /**
         * Reject any caller mutation of the submitted attachment set.
         *
         * @throws NullPointerException when attachmentIds is null
         */
        public SendRequest {
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /**
     * Publish a linked replacement revision.
     *
 * @return this record
 */
public record ReplaceRequest(String title, String text, String language, String actionDueAt,
                                 List<String> attachmentIds, String reason) {
        /**
         * Reject any caller mutation of the submitted attachment set.
         *
         * @throws NullPointerException when attachmentIds is null
         */
        public ReplaceRequest {
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /**
     * Withdraw the current revision.
     *
     * @param reason non-sensitive withdrawal reason
 * @return this record
 */
public record WithdrawRequest(String reason) {
    }
}
