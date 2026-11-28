package com.aurevia.pharmacyflow.clarify;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Read model for a prescription clarification thread.
 *
 * <p>A thread is opened by the pharmacy on a prescription visible to it and
 * addressed to the prescriber identified by that prescription. The request and the
 * prescriber's answer are append-only entries; the answer closes the thread and
 * is readable by the pharmacy requester. No chart data or inventory concept is
 * carried here. The types have no persistence or transport concerns.</p>
 */
public final class ClarificationContract {

    private ClarificationContract() {
    }

    /**
     * Lifecycle of a clarification request.
     */
    public enum State {
        /** The pharmacy is waiting for the prescriber. */
        OPEN("open"),
        /** The prescriber answered; the thread is closed and read-only. */
        ANSWERED("answered");

        private final String wire;

        State(String wire) {
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
    }

    /**
     * The two parties of a clarification thread.
     */
    public enum Party {
        /** The pharmacy staff member who opened the request. */
        PHARMACY("pharmacy"),
        /** The prescription's prescriber, who answers the request. */
        PRESCRIBER("prescriber");

        private final String wire;

        Party(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase party key
         */
        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /**
     * One immutable entry in the thread's append-only trail.
     *
     * @return this immutable entry
     */
    public record Entry(String id, String threadId, int sequence, Party authorParty, String authorSubject,
                        String body, String amendedRevisionId, Instant occurredAt) {
    }

    /**
     * Current projection of one clarification thread.
     *
     * <p>The answer carries either free text, an opaque amended prescription
     * revision identifier, or both. {@code version} is the next expected
     * {@code If-Match} precondition for an answer.</p>
     *
     * @return this immutable projection
     */
    public record Thread(String id, String organizationId, String prescriptionId, String requesterSubject,
                         String prescriberSubject, State state, Instant openedAt, int version,
                         String answerBody, String amendedRevisionId, Instant answeredAt, String answeredBy) {
    }

    /**
     * One thread with its trail and its derived overdue flag.
     *
     * @return this immutable view
     */
    public record ThreadView(Thread thread, List<Entry> entries, boolean overdue) {

        /**
         * Freeze the trail against caller mutation.
         */
        public ThreadView {
            entries = List.copyOf(entries);
        }

        /**
         * Return an independent snapshot of the append-only trail.
         *
         * @return the thread entries in append order
         */
        @Override
        public List<Entry> entries() {
            return List.copyOf(entries);
        }
    }
}
