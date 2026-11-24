package com.aurevia.labflow.outage;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.aurevia.labflow.results.ObservationInput;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Value contracts for the partner-outage manual result fallback (LAB-02, XCH-03, PRD-02).
 *
 * <p>When a partner result interface is unavailable, an authorized operator
 * attaches the original report and keys the observations in by hand. The command
 * carries a reason, the evidence attachment and the identifiers presented with
 * the report; the authenticated subject and organization never come from the
 * request. A result that matches no order is kept in the unmatched queue with a
 * named owner instead of being silently imported. All records are immutable so
 * an accepted entry cannot be rewritten without a new command.</p>
 */
public final class OutageModel {

    private OutageModel() {
    }

    /**
     * The original partner report retained as evidence for one manual entry.
     *
     * @param objectKey   private object-store key of the retained original
     * @param fileName    path-free original file name
     * @param checksum    lowercase SHA-256 of the retained bytes
     * @param contentType bounded declared media type
     */
    public record EvidenceAttachment(String objectKey, String fileName, String checksum,
                                     String contentType) {
    }

    /**
     * Command to key a result in manually while the partner interface is down.
     *
     * @param patientId       patient identity presented with the report
     * @param accessionNo     accession number presented with the report
     * @param accessionIssuer issuer of the presented accession number
     * @param reason          non-sensitive justification for the manual fallback
     * @param attachment      the retained original report
     * @param observations    keyed-in observations, at least one
     * @param unmatchedOwner  named owner required only when no order matches
     */
    public record ManualResultRequest(String patientId, String accessionNo, String accessionIssuer,
                                      String reason, EvidenceAttachment attachment,
                                      List<ObservationInput> observations, String unmatchedOwner) {

        /**
         * Freeze the observation list so a submitted command cannot change mid-flight.
         * A null list becomes empty so the service can reject it with a single rule.
         */
        public ManualResultRequest {
            observations = observations == null ? List.of() : List.copyOf(observations);
        }

        /**
         * Return the immutable observations.
         *
         * @return observations in submission order
         */
        @Override
        public List<ObservationInput> observations() {
            return List.copyOf(observations);
        }
    }

    /** Outcome of identifier matching for one manual entry. */
    public enum Disposition {
        /** The report was linked to the order named by the caller. */
        LINKED,
        /** No order matched; the entry waits in the unmatched queue. */
        UNMATCHED;

        /**
         * Return the lower-case wire form used by the API and the audit event.
         *
         * @return wire form such as {@code unmatched}
         */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Immutable outcome of one accepted manual entry.
     *
     * @param entryId           opaque identifier of this entry
     * @param orderId           matched order identifier, or {@code null} when unmatched
     * @param disposition       whether the entry was linked or queued
     * @param patientId         presented patient identity
     * @param reason            recorded fallback justification
     * @param attachment        retained original report, linked to the order when matched
     * @param observationSummary comma-separated keyed-in test codes
     * @param owner             named unmatched-queue owner, or {@code null} when linked
     * @param enteredBy         authenticated subject that entered the result
     * @param recordedAt        server instant of the entry
     */
    public record ManualResult(String entryId, String orderId, Disposition disposition, String patientId,
                               String reason, EvidenceAttachment attachment, String observationSummary,
                               String owner, String enteredBy, Instant recordedAt) {
    }

    /**
     * One append-only outage-history fact.
     *
     * @param eventId     opaque identifier of this event
     * @param orderRef    order reference the operator presented
     * @param action      stable audit action key
     * @param disposition resulting disposition
     * @param reason      recorded fallback justification
     * @param actor       authenticated subject that recorded the entry
     * @param occurredAt  server instant of the event
     */
    public record OutageEvent(String eventId, String orderRef, String action, Disposition disposition,
                              String reason, String actor, Instant occurredAt) {
    }
}
