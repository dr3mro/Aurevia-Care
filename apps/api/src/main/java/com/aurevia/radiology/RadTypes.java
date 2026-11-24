package com.aurevia.radiology;

import java.util.Locale;

/**
 * Bounded vocabularies and the imaging order lifecycle for RAD-01.
 *
 * <p>The enums keep clinically meaningful choices out of free-text strings at a
 * trust boundary. An imaging order advances through one legal path: intake,
 * schedule, accession, external study association, signed interpretation and
 * linked addenda. Images themselves are never fetched or stored here: a partner
 * diagnostic workstation reference is recorded instead, so images remain under
 * partner access control (RAD-01).</p>
 */
public interface RadTypes {

    /**
     * Clinical urgency of an imaging order (RAD-01).
     *
     * <p>Urgency is recorded, not interpreted into a care decision. A stat order
     * additionally requires a named responsible clinician at intake so that a
     * time-critical study always has an owner.</p>
     */
    enum Urgency {

        /** Routine queue order. */
        ROUTINE,
        /** Urgent order handled ahead of routine work. */
        URGENT,
        /** Immediately time-critical order requiring a named owner. */
        STAT;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case urgency name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Whether a named responsible clinician is mandatory at intake.
         *
         * @return {@code true} only for a stat order
         */
        public boolean requiresNamedOwner() {
            return this == STAT;
        }
    }

    /**
     * Imaging modality captured when the order is scheduled (RAD-01).
     *
     * <p>Only modalities on the tenant's approved configuration may be scheduled;
     * membership in this enum does not by itself confer approval.</p>
     */
    enum Modality {

        /** Computed tomography. */
        CT,
        /** Magnetic resonance imaging. */
        MRI,
        /** Plain radiography. */
        XRAY,
        /** Ultrasound. */
        ULTRASOUND,
        /** Positron emission tomography. */
        PET;

        /**
         * Return the wire form used by projections and fixtures.
         *
         * @return lower-case modality name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Lifecycle of an imaging order and its legal transitions (RAD-01).
     *
     * <p>Each transition creates a new immutable order revision and advances the
     * version by exactly one. A past state is never re-entered; a late finding is
     * represented by a linked addendum revision rather than by editing a signed
     * interpretation.</p>
     */
    enum OrderState {

        /** Order received with urgency, clinical question and destination. */
        INTAKE,
        /** A time and approved modality have been recorded. */
        SCHEDULED,
        /** An issuer-scoped accession has been assigned. */
        ACCESSIONED,
        /** The external study has been matched to patient and accession. */
        STUDY_ASSOCIATED,
        /** A signed interpretation has been recorded. */
        SIGNED,
        /** One or more signed addenda have been linked. */
        ADDENDED;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case state name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Decide whether a transition to the requested state is legal.
         *
         * @param target requested next state
         * @return {@code true} when the transition is allowed
         */
        public boolean canTransitionTo(OrderState target) {
            return switch (this) {
                case INTAKE -> target == SCHEDULED;
                case SCHEDULED -> target == ACCESSIONED;
                case ACCESSIONED -> target == STUDY_ASSOCIATED;
                case STUDY_ASSOCIATED -> target == SIGNED;
                case SIGNED -> target == ADDENDED;
                case ADDENDED -> target == ADDENDED;
            };
        }
    }

    /**
     * Whether an interpretation revision is the original report or a linked addendum (RAD-01).
     */
    enum InterpretationKind {

        /** The first signed interpretation of a study. */
        INITIAL,
        /** A signed addendum that supersedes a prior interpretation. */
        ADDENDUM;

        /**
         * Return the wire form used by projections.
         *
         * @return lower-case kind name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Lifecycle of a study-link review raised by a patient or accession mismatch (RAD-01).
     *
     * <p>A review is created in {@code OPEN} when an external study cannot be
     * associated, and is replaced by a {@code RESOLVED} revision when an
     * authorized reviewer records the decision. Both revisions stay in the
     * queue so the linkage decision is auditable.</p>
     */
    enum StudyLinkReviewStatus {

        /** Awaiting a reviewer decision; the study is not linked. */
        OPEN,
        /** Reviewed and cleared for a fresh association attempt. */
        RESOLVED;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case status name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Immutable facts emitted by the RAD-01 kernel for a later outbox publisher (ARC-05).
     *
     * <p>A referrer notification is a separate fact from the interpretation or
     * addendum that caused it: producing a report does not by itself prove that
     * the referrer was notified.</p>
     */
    enum RadEventType {

        /** An imaging order was received. */
        ORDER_INTAKED,
        /** A study time and modality were recorded. */
        ORDER_SCHEDULED,
        /** An issuer-scoped accession was assigned. */
        ACCESSION_RECORDED,
        /** An external study was associated with a verified patient. */
        STUDY_ASSOCIATED,
        /** A patient or accession mismatch queued a study-link review. */
        STUDY_LINK_REVIEW_OPENED,
        /** A study-link review was resolved by an authorized reviewer. */
        STUDY_LINK_REVIEW_RESOLVED,
        /** An interpretation was signed. */
        INTERPRETATION_SIGNED,
        /** A signed addendum was linked. */
        ADDENDUM_LINKED,
        /** A critical communication was recorded. */
        CRITICAL_COMMUNICATION,
        /** Read-back of a critical communication was recorded as a separate fact. */
        READ_BACK,
        /** A referrer was notified about a signed revision. */
        REFERRER_NOTIFIED;

        /**
         * Return the wire form used as the event type namespace.
         *
         * @return lower-case event type name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
