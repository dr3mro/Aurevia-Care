package com.aurevia.smartclinic.messaging.acknowledgment;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * MSG-05 domain vocabulary: publication, notification dispatch, first in-app
 * view and explicit patient acknowledgment as four distinct immutable facts.
 *
 * <p>Clinical text never appears in a fact. Instructions carry only an opaque
 * content reference; notification dispatch carries only a generic template key.
 * An acknowledgment is a receipt fact bound to one exact instruction revision and
 * is deliberately not a clinical consent, task completion or critical-result
 * acknowledgment, which belong to their own workflows.</p>
 */
public final class InstructionFacts {

    private InstructionFacts() {
    }

    /** The kind of an append-only fact. */
    public enum FactType {
        /** An instruction revision was published by its owning unit. */
        PUBLICATION("publication"),
        /** A generic notification was dispatched for a published revision. */
        DISPATCH("dispatch"),
        /** The patient first viewed a published revision in the app. */
        FIRST_VIEW("first_view"),
        /** The patient explicitly acknowledged receipt of one revision. */
        ACKNOWLEDGMENT("acknowledgment"),
        /** An unacknowledged revision was routed to its owning unit for follow-up. */
        FOLLOW_UP("follow_up");

        private final String wire;

        FactType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase wire key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored wire key.
         *
         * @param value stored key
         * @return the matching fact type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static FactType from(String value) {
            for (FactType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown fact type: " + value);
        }
    }

    /**
     * The only meaning an MSG-05 acknowledgment can have: confirmation of
     * receipt. Other acknowledgment workflows use their own distinct types.
     */
    public enum AcknowledgmentScope {
        /** Confirms receipt of one instruction revision; no clinical meaning. */
        RECEIPT("RECEIPT");

        private final String wire;

        AcknowledgmentScope(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return wire key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored wire key.
         *
         * @param value stored key
         * @return the matching scope
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AcknowledgmentScope from(String value) {
            for (AcknowledgmentScope candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown acknowledgment scope: " + value);
        }
    }

    /** Lifecycle of an overdue follow-up routed to an owning unit. */
    public enum FollowUpState {
        /** Detected and awaiting unit action. */
        OPEN("open"),
        /** Routed to the owning unit's follow-up queue. */
        ROUTED("routed"),
        /** Resolved by an authorized unit reviewer with a recorded reason. */
        RESOLVED("resolved");

        private final String wire;

        FollowUpState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase wire key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored wire key.
         *
         * @param value stored key
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static FollowUpState from(String value) {
            for (FollowUpState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown follow-up state: " + value);
        }
    }

    /** Common projection of an append-only fact. */
    public interface Fact {
        /**
         * Return the fact kind.
         *
         * @return fact type
         */
        FactType type();

        /**
         * Return the owning organization.
         *
         * @return organization identifier
         */
        String organizationId();

        /**
         * Return the addressed instruction.
         *
         * @return instruction identifier
         */
        String instructionId();

        /**
         * Return the bound revision.
         *
         * @return positive revision number
         */
        int revision();

        /**
         * Return the server occurrence time.
         *
         * @return occurrence instant
         */
        Instant occurredAt();
    }

    /**
     * One published instruction revision (MSG-04 handoff, MSG-05 fact source).
     *
     * <p>The content reference is opaque; the fact layer never holds clinical
     * text. Full content is retrieved only through an authenticated read.</p>
     *
     * @param id                    instruction identifier
     * @param organizationId        owning organization
     * @param patientId             verified linked chart
     * @param unitId                single owning recipient unit
     * @param revision              immutable revision number
     * @param contentRef            opaque artifact reference
     * @param requiresAcknowledgment whether the patient must acknowledge receipt
     * @param acknowledgmentDueAt   due instant, or {@code null} when not requested
     * @return the immutable instruction
     */
    public record Instruction(String id, String organizationId, String patientId, String unitId,
                              int revision, String contentRef, boolean requiresAcknowledgment,
                              Instant acknowledgmentDueAt) {
    }

    /**
     * Publication fact for one revision.
     *
     * @param organizationId owning organization
     * @param instructionId  published instruction
     * @param revision       published revision
     * @param actor          authenticated publishing staff subject
     * @param occurredAt     server publication instant
     * @return the immutable fact
     */
    public record Publication(String organizationId, String instructionId, int revision, String actor,
                              Instant occurredAt) implements Fact {
        @Override
        public FactType type() {
            return FactType.PUBLICATION;
        }
    }

    /**
     * Notification dispatch fact. Carries a generic template key only, never
     * clinical text or a content reference.
     *
     * @param organizationId owning organization
     * @param instructionId  addressed instruction
     * @param revision       addressed revision
     * @param genericKey     generic notification template key
     * @param correlationId  request correlation identifier
     * @param occurredAt     server dispatch instant
     * @return the immutable fact
     */
    public record Dispatch(String organizationId, String instructionId, int revision, String genericKey,
                           String correlationId, Instant occurredAt) implements Fact {
        @Override
        public FactType type() {
            return FactType.DISPATCH;
        }
    }

    /**
     * First in-app view fact, unique per instruction, revision and viewer.
     *
     * @param organizationId owning organization
     * @param instructionId  viewed instruction
     * @param revision       viewed revision
     * @param actor          authenticated patient subject
     * @param occurredAt     first server view instant
     * @return the immutable fact
     */
    public record FirstView(String organizationId, String instructionId, int revision, String actor,
                            Instant occurredAt) implements Fact {
        @Override
        public FactType type() {
            return FactType.FIRST_VIEW;
        }
    }

    /**
     * Receipt-only acknowledgment bound to one exact instruction revision.
     *
     * @param organizationId owning organization
     * @param instructionId  acknowledged instruction
     * @param revision       exact acknowledged revision
     * @param actor          authenticated patient subject
     * @param scope          always {@link com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts.AcknowledgmentScope#RECEIPT}
     * @param occurredAt     server acknowledgment instant
     * @return the immutable fact
     */
    public record Acknowledgment(String organizationId, String instructionId, int revision, String actor,
                                 AcknowledgmentScope scope, Instant occurredAt) implements Fact {
        @Override
        public FactType type() {
            return FactType.ACKNOWLEDGMENT;
        }
    }

    /**
     * Overdue follow-up routed to the unit that owns the instruction.
     *
     * @param organizationId owning organization
     * @param instructionId  overdue instruction
     * @param revision       overdue revision
     * @param unitId         owning recipient unit
     * @param routedAt       server detection instant
     * @param state          follow-up lifecycle state
     * @param resolvedBy     authorized resolver, or {@code null}
     * @param resolvedAt     resolution instant, or {@code null}
     * @param reason         resolution reason, or {@code null}
     * @return the immutable fact
     */
    public record FollowUp(String organizationId, String instructionId, int revision, String unitId,
                           Instant routedAt, FollowUpState state, String resolvedBy, Instant resolvedAt,
                           String reason) implements Fact {
        @Override
        public FactType type() {
            return FactType.FOLLOW_UP;
        }

        @Override
        public Instant occurredAt() {
            return routedAt;
        }
    }

    /** An immutable, ordered projection of an instruction's facts. */
    public record Timeline(List<Fact> facts) {
        /**
         * Freeze the supplied list.
         *
         * @param facts ordered facts
         */
        public Timeline {
            facts = List.copyOf(facts);
        }

        /**
         * Return an immutable snapshot of the facts.
         *
         * @return ordered facts
         */
        @Override
        public List<Fact> facts() {
            return List.copyOf(facts);
        }
    }

    /**
     * One non-sensitive audit fact for an MSG-05 decision (OPS-02).
     *
     * @param organizationId active organization
     * @param subject        authenticated actor
     * @param action         action key
     * @param resourceId     addressed resource
     * @param outcome        SUCCESS, DENIED, NOT_FOUND, CONFLICT or error code
     * @param purpose        recorded business purpose
     * @param scope          audited scope
     * @param correlationId  request correlation identifier
     * @param occurredAt     server instant
     * @return the immutable audit fact
     */
    public record AuditFact(String organizationId, String subject, String action, String resourceId,
                            String outcome, String purpose, String scope, String correlationId,
                            Instant occurredAt) {
    }

    /** Command to publish one instruction revision. */
    public record PublishCommand(String instructionId, String patientId, String unitId, int revision,
                                 String contentRef, boolean requiresAcknowledgment, String acknowledgmentDueAt,
                                 boolean sendNotification, String clientId) {
    }

    /** Command to retry a generic notification dispatch. */
    public record RetryCommand(String instructionId, int revision, String clientId) {
    }

    /** Command to record a first in-app view. */
    public record ViewCommand(String instructionId, int revision, String clientId) {
    }

    /** Command to acknowledge receipt of one exact revision. */
    public record AcknowledgeCommand(String instructionId, int revision, String clientId) {
    }

    /** Command to resolve an overdue follow-up with a reason. */
    public record ResolveCommand(String instructionId, int revision, String reason) {
    }
}
