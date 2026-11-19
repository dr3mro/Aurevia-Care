package com.aurevia.smartclinic.exchange.result;

import java.time.Instant;
import java.util.List;

/**
 * Public value contracts for the XCH-04 result-routing slice (S-G2-04).
 *
 * <p>All types are immutable value objects with no persistence or framework
 * dependency, so identifier matching, routing and state decisions stay in pure
 * Java and can be exercised without a database. Grouping the slice's contracts
 * in one file keeps this bounded story reviewable and inside the swarm PR size
 * limit; each nested type still has a single responsibility.</p>
 */
public final class ResultModel {

    private ResultModel() {
    }

    /** Disposition carried by an imported result. Every disposition is routed. */
    public enum ResultKind {
        /** A first, final report. */
        FINAL,
        /** A correction replacing an earlier immutable revision. */
        CORRECTED,
        /** A critical finding that must carry a critical routing marker. */
        CRITICAL;

        /**
         * Whether this disposition is a correction of a prior revision.
         *
         * @return {@code true} only for {@code CORRECTED}
         */
        public boolean isCorrection() {
            return this == CORRECTED;
        }

        /**
         * Whether this disposition is a critical finding.
         *
         * @return {@code true} only for {@code CRITICAL}
         */
        public boolean isCritical() {
            return this == CRITICAL;
        }
    }

    /** Clinical review state of one immutable result revision. */
    public enum ReviewState {
        /** Awaiting human review; every new correction revision starts here. */
        UNREVIEWED,
        /** An authorized human has acknowledged this exact revision. */
        ACKNOWLEDGED
    }

    /** Outcome of matching an inbound result against its expected order. */
    public enum MatchStatus {
        /** All five identifiers agree; the result may be imported. */
        MATCHED,
        /** No expected order exists for the supplied identifiers. */
        UNMATCHED,
        /** An order exists but one or more identifiers disagree. */
        CONFLICT
    }

    /** Why an inbound result was diverted to the reconciliation queue. */
    public enum ReconciliationReason {
        /** The order could not be found in the active tenant. */
        UNMATCHED,
        /** The order was found but identifiers disagreed or the lineage was incomplete. */
        CONFLICT
    }

    /** Lifecycle of a reconciliation-queue entry. */
    public enum ReconciliationStatus {
        /** Waiting for an authorized reviewer. */
        OPEN,
        /** Reviewed and closed. */
        RESOLVED
    }

    /**
     * The five issuer-scoped identifiers checked before a result is imported.
     *
     * @param patientId    local patient identifier of the receiving organization
     * @param sourceId     verified issuer/system that produced the result
     * @param accessionId  issuer-scoped accession number
     * @param destinationId receiving unit or organization identifier
     * @param orderId      tenant-local identifier of the released order
     */
    public record ResultIdentifiers(String patientId, String sourceId, String accessionId,
                                    String destinationId, String orderId) {
    }

    /**
     * Authoritative projection this story owns for a released order.
     *
     * <p>Sibling exchange stories are not present on this branch, so the matching
     * expectation is registered explicitly and is isolated by a unique table
     * prefix. It carries the named owner and coverage queue used for routing.</p>
     *
     * @param orderId    tenant-local order identifier
     * @param identifiers the five identifiers an inbound result must reproduce
     * @param owner      named owner responsible for the result
     * @param coverageQueue coverage queue the result is routed to
     */
    public record ExpectedOrder(String orderId, ResultIdentifiers identifiers,
                                String owner, String coverageQueue) {
    }

    /**
     * Result of identifier matching.
     *
     * @param status     matched, unmatched or conflicting
     * @param mismatches stable, ordered names of the disagreeing identifier fields
     */
    public record MatchDecision(MatchStatus status, List<String> mismatches) {

        /**
         * Take an immutable copy of the mismatch list.
         *
         * @throws NullPointerException when mismatches is null
         */
        public MatchDecision {
            mismatches = List.copyOf(mismatches);
        }

        /**
         * Return the immutable list of disagreeing field names.
         *
         * @return field names in comparison order
         */
        @Override
        public List<String> mismatches() {
            return List.copyOf(mismatches);
        }
    }

    /**
     * Where an imported result is routed.
     *
     * @param owner         named owner of the result
     * @param coverageQueue coverage queue that owns follow-up
     * @param critical      whether this is a critical finding
     * @param rationale     human-readable reason tied to the order and disposition
     */
    public record Routing(String owner, String coverageQueue, boolean critical, String rationale) {
    }

    /**
     * One immutable imported result revision.
     *
     * @param id                opaque identifier
     * @param version           lineage version, starting at one
     * @param orderId           tenant-local order identifier
     * @param identifiers       the five matched identifiers
     * @param kind              final, corrected or critical
     * @param supersedesVersion prior version corrected by this revision, or null
     * @param previousId        identifier of the superseded revision, or null
     * @param owner             named routing owner
     * @param coverageQueue     routing coverage queue
     * @param effectiveAt       clinical effective instant
     * @param receivedAt        ingestion instant
     * @param checksum          checksum of the retained synthetic payload
     */
    public record ResultReport(String id, int version, String orderId, ResultIdentifiers identifiers,
                               ResultKind kind, Integer supersedesVersion, String previousId,
                               String owner, String coverageQueue, Instant effectiveAt,
                               Instant receivedAt, String checksum) {
    }

    /**
     * Mutable review task bound to exactly one immutable revision.
     *
     * @param reportId      identifier of the reviewed revision
     * @param version       optimistic version of the task
     * @param state         review state
     * @param acknowledgedAt instant of acknowledgment, or null
     */
    public record Review(String reportId, int version, ReviewState state, Instant acknowledgedAt) {
    }

    /**
     * A report waiting in the reconciliation queue.
     *
     * @param id            opaque identifier
     * @param identifiers   identifiers supplied by the inbound report
     * @param reason        why it could not be imported
     * @param mismatches    names of the disagreeing identifier fields
     * @param detail        non-sensitive description of the conflict
     * @param status        queue status
     * @param createdAt     ingestion instant
     */
    public record ReconciliationEntry(String id, ResultIdentifiers identifiers,
                                      ReconciliationReason reason, List<String> mismatches,
                                      String detail, ReconciliationStatus status, Instant createdAt) {

        /**
         * Take an immutable copy of the mismatch list.
         *
         * @throws NullPointerException when mismatches is null
         */
        public ReconciliationEntry {
            mismatches = List.copyOf(mismatches);
        }

        /**
         * Return the immutable list of disagreeing field names.
         *
         * @return field names in comparison order
         */
        @Override
        public List<String> mismatches() {
            return List.copyOf(mismatches);
        }
    }

    /**
     * A notification that a correction reached a recipient of an earlier revision.
     *
     * @param id                opaque identifier
     * @param reportId          identifier of the correction revision
     * @param recipient         owner notified from a prior revision
     * @param supersededVersion version that the recipient had previously received
     * @param createdAt         notification instant
     */
    public record RecipientNotification(String id, String reportId, String recipient,
                                        int supersededVersion, Instant createdAt) {
    }

    /**
     * Result of an import command: exactly one of the two values is present.
     *
     * @param report          imported revision when matching succeeded
     * @param reconciliation  queue entry when the result was diverted
     */
    public record ImportOutcome(ResultReport report, ReconciliationEntry reconciliation) {
    }

    /**
     * One result revision together with its lineage and correction notifications.
     *
     * @param current       the requested revision
     * @param review        review task bound to the requested revision
     * @param lineage       all revisions of the same order, oldest first
     * @param notifications correction notifications bound to the requested revision
     */
    public record ResultDetail(ResultReport current, Review review, List<ResultReport> lineage,
                               List<RecipientNotification> notifications) {

        /**
         * Take immutable copies of the supplied lists.
         *
         * @throws NullPointerException when either list is null
         */
        public ResultDetail {
            lineage = List.copyOf(lineage);
            notifications = List.copyOf(notifications);
        }

        /**
         * Return the immutable revision lineage.
         *
         * @return revisions oldest first
         */
        @Override
        public List<ResultReport> lineage() {
            return List.copyOf(lineage);
        }

        /**
         * Return the immutable correction-notification list.
         *
         * @return notifications for the requested revision
         */
        @Override
        public List<RecipientNotification> notifications() {
            return List.copyOf(notifications);
        }
    }
}
