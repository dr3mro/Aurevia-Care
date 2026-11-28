package com.aurevia.exchange.response;

import java.time.Instant;
import java.util.List;

/**
 * Request payloads and immutable value records for recipient responses.
 *
 * <p>Every record is immutable; the actor, tenant and timestamp of a command come
 * from the authenticated request context, never from the client payload.</p>
 */
public final class ExchangeResponseCommands {

    private ExchangeResponseCommands() {
    }

    /**
     * Accept a released order against a locally verified patient association.
     *
 * @return this record
 */
public record AcceptRequest(String localPatientId, String assignee, String permittedPurpose,
                                String retentionPolicy) {
    }

    /**
     * A recorded reason for reject, cancellation request or revocation.
     *
 * @return this record
 */
public record ReasonRequest(String reason) {
    }

    /**
     * A clarification question sent back to the order source.
     *
 * @return this record
 */
public record ClarifyRequest(String question) {
    }

    /**
     * A linked revision proposed after acceptance.
     *
 * @return this record
 */
public record ProposeChangeRequest(String changeSummary, String contentChecksum) {
    }

    /**
     * Recipient response to a linked change.
     *
 * @return this record
 */
public record ChangeResponseRequest(String decision, String reason) {
    }

    /**
     * Recipient response to a cancellation request.
     *
 * @return this record
 */
public record CancellationResponseRequest(String decision, String reason) {
    }

    /**
     * Revoke a release and emit a downstream notification.
     *
 * @return this record
 */
public record RevokeRequest(String reason, String retentionPolicy, String target, String channel,
                                String eventId) {
    }

    /**
     * Mutable current projection of one exchanged order work item.
     *
 * @return this record
 */
public record ExchangeOrder(String id, int version, String orderCode, ExchangeState state,
                                int revision, String contentChecksum, String localPatientId,
                                String assignee, String reason, String question,
                                String permittedPurpose, String retentionPolicy) {
    }

    /**
     * Immutable released-content revision. A post-acceptance change
     * inserts a new revision linked to the previous one; it never rewrites an old row.
     *
 * @return this record
 */
public record Revision(String id, int revision, Integer previousRevision, String contentChecksum,
                           String changeSummary, String actorId, Instant occurredAt) {
    }

    /**
     * Immutable retained recipient copy with its permitted purpose and policy
     * Only a revoked order blocks retrieval of the retained copy.
     *
 * @return this record
 */
public record RetainedCopy(String id, int revision, String contentChecksum, String permittedPurpose,
                               String retentionPolicy, boolean legalHold, String actorId,
                               Instant occurredAt) {
    }

    /**
     * One append-only response-history entry.
     *
 * @return this record
 */
public record ExchangeEvent(String id, int orderVersion, String action, String fromState,
                                String toState, String reason, String actorId, Instant occurredAt) {
    }

    /**
     * Immutable downstream-notification record emitted on revocation.
     *
 * @return this record
 */
public record Notification(String id, String eventId, String target, String channel, String outcome,
                               String actorId, Instant occurredAt) {
    }

    /**
     * Read projection of one order work item with its append-only ledgers.
     *
 * @return this record
 */
public record OrderView(ExchangeOrder order, List<ExchangeEvent> history, List<Revision> revisions,
                            List<RetainedCopy> retained, List<Notification> notifications) {

        /**
         * Defensively copy every list so the immutable view cannot be mutated.
         *
         */
        public OrderView {
            history = List.copyOf(history);
            revisions = List.copyOf(revisions);
            retained = List.copyOf(retained);
            notifications = List.copyOf(notifications);
        }
    }
}
