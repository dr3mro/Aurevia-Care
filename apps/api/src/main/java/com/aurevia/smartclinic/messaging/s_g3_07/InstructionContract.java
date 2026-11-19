package com.aurevia.smartclinic.messaging.s_g3_07;

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
         * @param value wire key
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
         * @param value wire key
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
         * @param value wire key
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
         * @param target requested state
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
         * @param value wire key
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
     * @param id           tenant-local template key
     * @param unitId       owning care unit
     * @param kind         typed instruction kind
     * @param title        default title
     * @param body         default text
     * @param language     BCP-47 language tag of the body
     * @param version      immutable template version
     * @param approvedBy   subject that approved this version
     * @param approvedAt   approval instant
     * @param clinical     whether only a clinician may send it
     * @param contextType  optional required context type, or {@code null}
     */
    public record Template(String id, String unitId, Kind kind, String title, String body,
                           String language, int version, String approvedBy, Instant approvedAt,
                           boolean clinical, ContextType contextType) {
    }

    /**
     * An immutable instruction revision.
     *
     * @param threadId         stable thread identifier shared by all revisions
     * @param revision         monotonically increasing revision number
     * @param templateId       approved template used, or {@code null} for free text
     * @param kind             typed instruction kind
     * @param title            localized title
     * @param text             localized instruction text
     * @param language         BCP-47 language tag
     * @param authorSubject    authenticated author
     * @param authorRole       role in force at send time
     * @param unitId           sending care unit
     * @param connectionId     verified patient-unit connection
     * @param patientId        linked patient
     * @param issuedAt         server issue instant
     * @param actionDueAt      optional requested action time, or {@code null}
     * @param contextType      linked care relationship type, or {@code null}
     * @param contextId        linked care relationship identifier, or {@code null}
     * @param attachmentIds    secure attachment identifiers bound to this revision
     * @param state            current revision state
     * @param supersededBy     later revision that superseded this one, or {@code null}
     * @param replacesRevision earlier revision this one replaces, or {@code null}
     * @param withdrawalReason reason when withdrawn, or {@code null}
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
         * @param threadId         stable thread identifier
         * @param revision         revision number
         * @param templateId       approved template, or {@code null}
         * @param kind             typed instruction kind
         * @param title            localized title
         * @param text             localized body
         * @param language         BCP-47 language tag
         * @param authorSubject    authenticated author
         * @param authorRole       role at send time
         * @param unitId           sending care unit
         * @param connectionId     verified connection
         * @param patientId        linked patient
         * @param issuedAt         server issue instant
         * @param actionDueAt      optional action time
         * @param contextType      linked care relationship type
         * @param contextId        linked care relationship identifier
         * @param attachmentIds    secure attachment identifiers
         * @param state            revision state
         * @param supersededBy     later revision number, or {@code null}
         * @param replacesRevision earlier revision number, or {@code null}
         * @param withdrawalReason reason when withdrawn, or {@code null}
         * @throws NullPointerException when attachmentIds is null
         */
        public Revision {
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /**
     * A patient-visible fact about a revision.
     *
     * @param threadId   owning thread
     * @param revision   bound revision number
     * @param type       fact type
     * @param recipient  notified patient, or {@code null} for an internal fact
     * @param occurredAt server instant
     * @param note       non-sensitive reason or detail, or {@code null}
     */
    public record Fact(String threadId, int revision, FactType type, String recipient,
                       Instant occurredAt, String note) {
    }

    /**
     * Read model for one thread.
     *
     * @param current current revision
     * @param history all revisions in ascending order
     * @param facts   immutable facts in occurrence order
     */
    public record View(Revision current, List<Revision> history, List<Fact> facts) {
        /**
         * Defensive copies keep the read model independent of its source lists.
         *
         * @param current current revision
         * @param history all revisions in ascending order
         * @param facts   immutable facts in occurrence order
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
     * @param unitId      owning care unit
     * @param templateId  tenant-local template key
     * @param kind        typed instruction kind
     * @param title       default title
     * @param body        default text
     * @param language    BCP-47 language tag
     * @param clinical    whether only a clinician may send it
     * @param contextType optional context type, or {@code null}
     */
    public record DefineTemplateRequest(String unitId, String templateId, Kind kind, String title,
                                        String body, String language, boolean clinical,
                                        ContextType contextType) {
    }

    /**
     * Send an instruction from an approved template or bounded free text.
     *
     * @param patientId     verified patient
     * @param connectionId  verified patient-unit connection
     * @param unitId        sending care unit
     * @param templateId    approved template, or {@code null} for free text
     * @param kind          kind when free text is used, or {@code null}
     * @param title         title when free text is used, or {@code null}
     * @param text          body when free text is used, or {@code null}
     * @param language      language when free text is used, or {@code null}
     * @param actionDueAt   optional future action instant, or {@code null}
     * @param contextType   linked care relationship type, or {@code null}
     * @param contextId     linked care relationship identifier, or {@code null}
     * @param attachmentIds secure attachment identifiers
     */
    public record SendRequest(String patientId, String connectionId, String unitId, String templateId,
                              Kind kind, String title, String text, String language,
                              String actionDueAt, ContextType contextType, String contextId,
                              List<String> attachmentIds) {
        /**
         * Reject any caller mutation of the submitted attachment set.
         *
         * @param patientId     verified patient
         * @param connectionId  verified patient-unit connection
         * @param unitId        sending care unit
         * @param templateId    approved template, or {@code null}
         * @param kind          kind for free text, or {@code null}
         * @param title         title for free text, or {@code null}
         * @param text          body for free text, or {@code null}
         * @param language      language for free text, or {@code null}
         * @param actionDueAt   optional action instant
         * @param contextType   linked care relationship type
         * @param contextId     linked care relationship identifier
         * @param attachmentIds secure attachment identifiers
         * @throws NullPointerException when attachmentIds is null
         */
        public SendRequest {
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /**
     * Publish a linked replacement revision.
     *
     * @param title         new title
     * @param text          new text
     * @param language      new language
     * @param actionDueAt   optional future action instant, or {@code null}
     * @param attachmentIds secure attachment identifiers
     * @param reason        non-sensitive change reason
     */
    public record ReplaceRequest(String title, String text, String language, String actionDueAt,
                                 List<String> attachmentIds, String reason) {
        /**
         * Reject any caller mutation of the submitted attachment set.
         *
         * @param title         new title
         * @param text          new body
         * @param language      new language
         * @param actionDueAt   optional action instant
         * @param attachmentIds secure attachment identifiers
         * @param reason        non-sensitive change reason
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
     */
    public record WithdrawRequest(String reason) {
    }
}
