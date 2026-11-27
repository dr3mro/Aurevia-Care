package com.aurevia.exchange.result;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Public value contracts for the result-routing slice.
 *
 * <p>All types are immutable value objects with no persistence or framework
 * dependency, so identifier matching, routing and state decisions stay in pure
 * Java and can be exercised without a database. Each nested type has a single
 * responsibility and carries no framework or persistence dependency.</p>
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
         * Return the lower-case wire form used by the API and the database.
         *
         * @return wire form such as {@code corrected}
         */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

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
        ACKNOWLEDGED;

        /**
         * Return the lower-case wire form used by the API and the database.
         *
         * @return wire form such as {@code unreviewed}
         */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
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
        CONFLICT;

        /**
         * Return the lower-case wire form used by the API and the database.
         *
         * @return wire form such as {@code unmatched}
         */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Lifecycle of a reconciliation-queue entry. */
    public enum ReconciliationStatus {
        /** Waiting for an authorized reviewer. */
        OPEN,
        /** Reviewed and closed. */
        RESOLVED;

        /**
         * Return the lower-case wire form used by the API and the database.
         *
         * @return wire form such as {@code resolved}
         */
        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The five issuer-scoped identifiers checked before a result is imported.
     *
     */
    public record ResultIdentifiers(String patientId, String sourceId, String accessionId,
                                    String destinationId, String orderId) {
    }

    /**
     * Authoritative projection for a released order.
     *
     * <p>The matching expectation is registered explicitly and isolated by
     * tenant-scoped keys. It carries the named owner and coverage queue used for routing.</p>
     *
 * @return this record
 */
public record ExpectedOrder(String orderId, ResultIdentifiers identifiers,
                                String owner, String coverageQueue) {
    }

    /**
     * Result of identifier matching.
     *
 * @return this record
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
     */
    public record Routing(String owner, String coverageQueue, boolean critical, String rationale) {
    }

    /**
     * One immutable imported result revision.
     *
 * @return this record
 */
public record ResultReport(String id, int version, String orderId, ResultIdentifiers identifiers,
                               ResultKind kind, Integer supersedesVersion, String previousId,
                               String owner, String coverageQueue, Instant effectiveAt,
                               Instant receivedAt, String checksum) {
    }

    /**
     * Mutable review task bound to exactly one immutable revision.
     *
 * @return this record
 */
public record Review(String reportId, int version, ReviewState state, Instant acknowledgedAt) {
    }

    /**
     * A report waiting in the reconciliation queue.
     *
 * @return this record
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
     */
    public record RecipientNotification(String id, String reportId, String recipient,
                                        int supersededVersion, Instant createdAt) {
    }

    /**
     * Result of an import command: exactly one of the two values is present.
     *
 * @return this record
 */
public record ImportOutcome(ResultReport report, ReconciliationEntry reconciliation) {
    }

    /**
     * One result revision together with its lineage and correction notifications.
     *
 * @return this record
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
