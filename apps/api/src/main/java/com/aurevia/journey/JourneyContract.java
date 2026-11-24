package com.aurevia.journey;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the single order-journey timeline read model.
 *
 * <p>One item (a prescription, lab order, imaging order or referral) has one
 * canonical sequence of steps. Each append-only fact from a workflow source is
 * normalised into a {@link com.aurevia.journey.JourneyContract.JourneyEvent}
 * and projected by {@link com.aurevia.journey.JourneyTimeline} into a
 * {@link com.aurevia.journey.JourneyContract.JourneyView}. The vocabulary
 * carries no persistence or transport concern: clinical payloads, identifiers and
 * narrative stay in their owning modules.</p>
 *
 * <p>The wire keys are stable so a client can render a step without duplicating
 * the workflow vocabulary. A patient is a distinct viewer: only facts explicitly
 * marked as released to the patient contribute a visible completed step.</p>
 */
public final class JourneyContract {

    private JourneyContract() {
    }

    /**
     * The canonical, ordered steps shared by every item kind. The declaration
     * order is the timeline order: a step never reappears later in the sequence.
     */
    public enum JourneyStep {
        /** The order or prescription was placed by the originating clinician. */
        PLACED("placed"),
        /** The item was delivered to the destination system. */
        DELIVERED("delivered"),
        /** The receiving party accepted the item. */
        ACCEPTED("accepted"),
        /** A specimen was collected, or an imaging study was scheduled. */
        COLLECTED_OR_SCHEDULED("collected_or_scheduled"),
        /** A result or interpretation was produced. */
        RESULTED("resulted"),
        /** The result was released to the patient under a release authorization. */
        RELEASED("released"),
        /** The final acknowledgment was recorded. */
        ACKNOWLEDGED("acknowledged");

        private final String wire;

        JourneyStep(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key of this step.
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
         * @param value wire key
         * @return the matching step
         * @throws IllegalArgumentException when the key is unknown
         */
        public static JourneyStep from(String value) {
            for (JourneyStep candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown journey step: " + value);
        }
    }

    /** The kind of item whose journey is projected. */
    public enum JourneyItemType {
        /** A controlled prescription transmission (RX-02). */
        PRESCRIPTION("prescription"),
        /** A laboratory order (LAB-01). */
        LAB_ORDER("lab_order"),
        /** An imaging order (RAD-01). */
        IMAGING_ORDER("imaging_order"),
        /** A specialty referral. */
        REFERRAL("referral");

        private final String wire;

        JourneyItemType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key of this item type.
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
         * @param value wire key
         * @return the matching item type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static JourneyItemType from(String value) {
            for (JourneyItemType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown journey item type: " + value);
        }
    }

    /** The class of actor that performed a step, never a personal identifier. */
    public enum ActorType {
        /** The prescribing clinician. */
        PRESCRIBER("prescriber"),
        /** A clinician or other authorized care-team member. */
        CLINICIAN("clinician"),
        /** A pharmacy or its system. */
        PHARMACY("pharmacy"),
        /** A laboratory or its system. */
        LABORATORY("laboratory"),
        /** A radiology practice or its system. */
        RADIOLOGY("radiology"),
        /** The recipient of a referral. */
        REFERRAL_RECIPIENT("referral_recipient"),
        /** The patient. */
        PATIENT("patient"),
        /** An automated system step, with no human actor. */
        SYSTEM("system");

        private final String wire;

        ActorType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key of this actor type.
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
         * @param value wire key
         * @return the matching actor type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static ActorType from(String value) {
            for (ActorType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown journey actor type: " + value);
        }
    }

    /** Whether a canonical step has a contributing fact or is still awaited. */
    public enum StepStatus {
        /** No visible fact yet: the step is pending. */
        PENDING("pending"),
        /** A fact contributes an actor and a time to this step. */
        COMPLETE("complete");

        private final String wire;

        StepStatus(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key of this status.
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
         * @param value wire key
         * @return the matching status
         * @throws IllegalArgumentException when the key is unknown
         */
        public static StepStatus from(String value) {
            for (StepStatus candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown journey step status: " + value);
        }
    }

    /** Who is reading the timeline; determines which facts are visible. */
    public enum Viewer {
        /** An authorized staff member sees every workflow fact. */
        CLINICIAN("clinician"),
        /** A patient sees only facts released to the patient. */
        PATIENT("patient");

        private final String wire;

        Viewer(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key of this viewer.
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
         * @param value wire key
         * @return the matching viewer
         * @throws IllegalArgumentException when the key is unknown
         */
        public static Viewer from(String value) {
            for (Viewer candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown journey viewer: " + value);
        }
    }

    /**
     * One normalized fact contributed by a workflow source to an item's journey.
     *
     * @param eventId           stable identifier used to break ties between facts at the same instant
     * @param organizationId    owning tenant; the projector never mixes tenants
     * @param itemId            local identifier of the item whose journey this fact describes
     * @param itemType          kind of the item
     * @param source            emitting workflow (for example prescription, order, lab or imaging)
     * @param step              canonical step this fact completes
     * @param actorType         class of actor that produced the fact
     * @param occurredAt        server occurrence instant of the fact
     * @param releasedToPatient whether the fact is visible in a patient view
     * @return the immutable fact
     */
    public record JourneyEvent(String eventId, String organizationId, String itemId, JourneyItemType itemType,
                               String source, JourneyStep step, ActorType actorType, Instant occurredAt,
                               boolean releasedToPatient) {
    }

    /**
     * One step of the projected timeline. A pending step deliberately carries no
     * actor, source or time so a hidden fact cannot leak through the projection.
     *
     * @param step        canonical step
     * @param status      complete or pending
     * @param source      emitting workflow, or {@code null} when pending
     * @param actorType   class of actor, or {@code null} when pending
     * @param occurredAt  occurrence instant, or {@code null} when pending
     * @return the immutable step view
     */
    public record StepView(JourneyStep step, StepStatus status, String source, ActorType actorType,
                           Instant occurredAt) {
    }

    /**
     * The complete read model for one item and one viewer.
     *
     * @param organizationId   tenant this view was built for
     * @param itemId           item whose journey was projected
     * @param itemType         kind of the item
     * @param viewer           audience the view was built for
     * @param evaluatedAt      instant used to compute the age of the next expected step
     * @param steps            every canonical step in order, complete or pending
     * @param nextExpectedStep first pending step, or {@code null} when the journey is complete
     * @param nextExpectedAge  time the next expected step has been awaited, never negative
     * @return the immutable view
     */
    public record JourneyView(String organizationId, String itemId, JourneyItemType itemType, Viewer viewer,
                              Instant evaluatedAt, List<StepView> steps, JourneyStep nextExpectedStep,
                              Duration nextExpectedAge) {
        /**
         * Freeze the supplied step list so a caller cannot mutate the projection.
         */
        public JourneyView {
            steps = List.copyOf(steps);
        }

        /**
         * Return an independent snapshot of the ordered steps.
         *
         * @return immutable step list
         */
        @Override
        public List<StepView> steps() {
            return List.copyOf(steps);
        }
    }
}
