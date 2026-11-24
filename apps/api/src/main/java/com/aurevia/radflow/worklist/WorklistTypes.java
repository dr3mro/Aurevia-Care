package com.aurevia.radflow.worklist;

import java.util.Locale;

/**
 * Bounded vocabularies for the RAD-01 performing-site worklist.
 *
 * <p>The statuses describe the hand of the imaging order as the performing site
 * sees it. They advance along one legal path only and never re-enter a past
 * state; a later finding is a new fact, not a rewrite of a completed step. The
 * enums keep these clinically meaningful choices out of free text at the trust
 * boundary. Images are never fetched here: only a partner workstation reference
 * is recorded, so image pixels remain under partner access control.</p>
 */
public interface WorklistTypes {

    /**
     * Forward-only status of an order on the performing-site worklist (RAD-01).
     *
     * <p>An order is received, scheduled with an accession and modality, performed
     * once the external study is linked after verification, and finally reported.
     * {@code REPORTED} is terminal: no further status transition is possible.</p>
     */
    enum WorklistStatus {

        /** Order received with its urgency and clinical question. */
        RECEIVED,
        /** A time, approved modality and accession were recorded. */
        SCHEDULED,
        /** The external study was linked after the patient and accession check. */
        PERFORMED,
        /** The reported study was handed off to the referrer. */
        REPORTED;

        /**
         * Return the wire form used by projections and events.
         *
         * @return lower-case status name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Decide whether a transition to the requested status is legal.
         *
         * @param target requested next status
         * @return {@code true} only for the immediate next status
         */
        public boolean canTransitionTo(WorklistStatus target) {
            return switch (this) {
                case RECEIVED -> target == SCHEDULED;
                case SCHEDULED -> target == PERFORMED;
                case PERFORMED -> target == REPORTED;
                case REPORTED -> false;
            };
        }
    }

    /**
     * Immutable facts emitted by the worklist kernel for a later hand-off publisher (ARC-05).
     *
     * <p>A hand-off is a distinct fact from the performance it follows: recording
     * that a study was performed does not by itself prove that the referrer was
     * handed off. A refused link is also a fact so a wrong-patient attempt is
     * auditable without changing the order revision.</p>
     */
    enum WorklistEventType {

        /** An order was received onto the worklist. */
        ORDER_RECEIVED,
        /** A study time, modality and accession were recorded. */
        ORDER_SCHEDULED,
        /** The linked study was performed at the performing site. */
        STUDY_PERFORMED,
        /** The performed study was handed off from the performing site for reporting. */
        HANDOFF_RECORDED,
        /** The reported study was recorded on the worklist. */
        ORDER_REPORTED,
        /** A study link was refused because the patient or accession did not match. */
        STUDY_LINK_REFUSED;

        /**
         * Return the wire form used by projections.
         *
         * @return lower-case event type name
         */
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
