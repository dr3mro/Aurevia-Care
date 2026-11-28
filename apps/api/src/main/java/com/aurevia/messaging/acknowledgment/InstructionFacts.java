package com.aurevia.messaging.acknowledgment;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain vocabulary for publication, notification dispatch, first in-app
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

        /**
         * Create a fact type with its stable wire key.
         *
         * @param wire lowercase key used on the wire and in stored facts
         */
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
         * Parse a stored fact key.
         *
         * @param value lowercase wire key stored on the fact
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
     * The only meaning an acknowledgment can have: confirmation of
     * receipt. Other acknowledgment workflows use their own distinct types.
     */
    public enum AcknowledgmentScope {
        /** Confirms receipt of one instruction revision; no clinical meaning. */
        RECEIPT("RECEIPT");

        private final String wire;

        /**
         * Create an acknowledgment scope with its stable wire key.
         *
         * @param wire key used on the wire and in stored facts
         */
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
         * @param value lowercase wire key stored on the fact
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

        /**
         * Create a follow-up state with its stable wire key.
         *
         * @param wire lowercase key used on the wire and in stored facts
         */
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
         * @param value lowercase wire key stored on the fact
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
     * One published instruction revision handed off for dispatch and used as the fact source.
     *
     * <p>The content reference is opaque; the fact layer never holds clinical
     * text. Full content is retrieved only through an authenticated read.</p>
     *
     * @return the immutable instruction
     */
    public record Instruction(String id, String organizationId, String patientId, String unitId,
                              int revision, String contentRef, boolean requiresAcknowledgment,
                              Instant acknowledgmentDueAt) {
    }

    /**
     * Publication fact for one revision.
     *
     * @return the immutable fact
     */
    public record Publication(String organizationId, String instructionId, int revision, String actor,
                              Instant occurredAt) implements Fact {
        /** {@inheritDoc} */
        @Override
        public FactType type() {
            return FactType.PUBLICATION;
        }
    }

    /**
     * Notification dispatch fact. Carries a generic template key only, never
     * clinical text or a content reference.
     *
     * @return the immutable fact
     */
    public record Dispatch(String organizationId, String instructionId, int revision, String genericKey,
                           String correlationId, Instant occurredAt) implements Fact {
        /** {@inheritDoc} */
        @Override
        public FactType type() {
            return FactType.DISPATCH;
        }
    }

    /**
     * First in-app view fact, unique per instruction, revision and viewer.
     *
     * @return the immutable fact
     */
    public record FirstView(String organizationId, String instructionId, int revision, String actor,
                            Instant occurredAt) implements Fact {
        /** {@inheritDoc} */
        @Override
        public FactType type() {
            return FactType.FIRST_VIEW;
        }
    }

    /**
     * Receipt-only acknowledgment bound to one exact instruction revision.
     *
     * @return the immutable fact
     */
    public record Acknowledgment(String organizationId, String instructionId, int revision, String actor,
                                 AcknowledgmentScope scope, Instant occurredAt) implements Fact {
        /** {@inheritDoc} */
        @Override
        public FactType type() {
            return FactType.ACKNOWLEDGMENT;
        }
    }

    /**
     * Overdue follow-up routed to the unit that owns the instruction.
     *
     * @return the immutable fact
     */
    public record FollowUp(String organizationId, String instructionId, int revision, String unitId,
                           Instant routedAt, FollowUpState state, String resolvedBy, Instant resolvedAt,
                           String reason) implements Fact {
        /** {@inheritDoc} */
        @Override
        public FactType type() {
            return FactType.FOLLOW_UP;
        }

        /** {@inheritDoc} */
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
     * One non-sensitive audit fact for an instruction decision.
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

    /**
     * Command to publish one immutable instruction revision.
     *
     * <p>The content reference is opaque and the due instant is required when a
     * receipt acknowledgment is requested, so an overdue follow-up can be routed.</p>
     *
     * @return the publish command
     */
    public record PublishCommand(String instructionId, String patientId, String unitId, int revision,
                                 String contentRef, boolean requiresAcknowledgment, String acknowledgmentDueAt,
                                 boolean sendNotification, String clientId) {
    }

    /**
     * Command to retry a generic notification dispatch for one revision.
     *
     * @return the dispatch retry command
     */
    public record RetryCommand(String instructionId, int revision, String clientId) {
    }

    /**
     * Command to record a first in-app view of one revision.
     *
     * @return the first-view command
     */
    public record ViewCommand(String instructionId, int revision, String clientId) {
    }

    /**
     * Command to acknowledge receipt of one exact revision.
     *
     * @return the acknowledgment command
     */
    public record AcknowledgeCommand(String instructionId, int revision, String clientId) {
    }

    /**
     * Command to resolve an overdue follow-up with a recorded reason.
     *
     * @return the follow-up resolution command
     */
    public record ResolveCommand(String instructionId, int revision, String reason) {
    }
}
