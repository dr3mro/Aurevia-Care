package com.aurevia.smartclinic.exchange.response;

import java.time.Instant;
import java.util.List;

/**
 * Request payloads and immutable value records for XCH-03.
 *
 * <p>Every record is immutable; the actor, tenant and timestamp of a command come
 * from the authenticated request context, never from the client payload.</p>
 */
public final class Xch03Commands {

    private Xch03Commands() {
    }

    /**
     * Accept a released order against a locally verified patient association.
     *
     * @param localPatientId  verified local patient record identifier
     * @param assignee        optional receiving assignee subject
     * @param permittedPurpose permitted purpose of the retained copy
     * @param retentionPolicy  approved retention policy code
     */
    public record AcceptRequest(String localPatientId, String assignee, String permittedPurpose,
                                String retentionPolicy) {
    }

    /**
     * A recorded reason for reject, cancellation request or revocation.
     *
     * @param reason non-empty bounded reason
     */
    public record ReasonRequest(String reason) {
    }

    /**
     * A clarification question sent back to the order source.
     *
     * @param question non-empty bounded question
     */
    public record ClarifyRequest(String question) {
    }

    /**
     * A linked revision proposed after acceptance.
     *
     * @param changeSummary  non-empty bounded description of the change
     * @param contentChecksum hex checksum of the new immutable content
     */
    public record ProposeChangeRequest(String changeSummary, String contentChecksum) {
    }

    /**
     * Recipient response to a linked change.
     *
     * @param decision {@code accept} or {@code reject}
     * @param reason   required when the change is rejected
     */
    public record ChangeResponseRequest(String decision, String reason) {
    }

    /**
     * Recipient response to a cancellation request.
     *
     * @param decision {@code confirm} or {@code decline}
     * @param reason   required when the cancellation is declined
     */
    public record CancellationResponseRequest(String decision, String reason) {
    }

    /**
     * Revoke a release and emit a downstream notification.
     *
     * @param reason           non-empty bounded reason
     * @param retentionPolicy  approved retention policy code
     * @param target           named downstream recipient
     * @param channel          approved contact channel
     * @param eventId          durable idempotency key for the downstream effect
     */
    public record RevokeRequest(String reason, String retentionPolicy, String target, String channel,
                                String eventId) {
    }

    /**
     * Mutable current projection of one exchanged order work item (XCH-03).
     *
     * @param id              opaque order identifier
     * @param version         optimistic-lock version
     * @param orderCode       tenant-local requisition code
     * @param state           current lifecycle state
     * @param revision        current released content revision
     * @param contentChecksum checksum of the immutable released content
     * @param localPatientId  locally verified patient association, set on accept
     * @param assignee        receiving assignee recorded on accept
     * @param reason          last recorded reason
     * @param question        last clarification question
     * @param permittedPurpose permitted purpose of the retained recipient copy
     * @param retentionPolicy approved retention policy applied to the retained copy
     */
    public record ExchangeOrder(String id, int version, String orderCode, ExchangeState state,
                                int revision, String contentChecksum, String localPatientId,
                                String assignee, String reason, String question,
                                String permittedPurpose, String retentionPolicy) {
    }

    /**
     * Immutable released-content revision (XCH-01, XCH-03). A post-acceptance change
     * inserts a new revision linked to the previous one; it never rewrites an old row.
     *
     * @param id               opaque revision identifier
     * @param revision         revision number within the order
     * @param previousRevision previous revision number, or {@code null} for the first
     * @param contentChecksum  checksum of the immutable released content
     * @param changeSummary    recorded description of a linked change
     * @param actorId          authenticated actor that created the revision
     * @param occurredAt       server time the revision was recorded
     */
    public record Revision(String id, int revision, Integer previousRevision, String contentChecksum,
                           String changeSummary, String actorId, Instant occurredAt) {
    }

    /**
     * Immutable retained recipient copy with its permitted purpose and policy
     * (XCH-03, ARC-06). Access is governed by the parent order's revocation state.
     *
     * @param id              opaque copy identifier
     * @param revision        revision the copy retains
     * @param contentChecksum checksum proved against the released revision
     * @param permittedPurpose purpose the recipient may use the copy for
     * @param retentionPolicy approved retention policy applied to the copy
     * @param legalHold       whether a legal hold suspends expiry
     * @param actorId         authenticated actor that retained the copy
     * @param occurredAt      server time the copy was retained
     */
    public record RetainedCopy(String id, int revision, String contentChecksum, String permittedPurpose,
                               String retentionPolicy, boolean legalHold, String actorId,
                               Instant occurredAt) {
    }

    /**
     * One append-only response-history entry (XCH-03, OPS-02).
     *
     * @param id           opaque event identifier
     * @param orderVersion order version the event describes
     * @param action       command action key
     * @param fromState    state before the command, or {@code null} on creation
     * @param toState      state after the command
     * @param reason       recorded reason or question, when required
     * @param actorId      authenticated actor derived by the database
     * @param occurredAt   server time of the event
     */
    public record ExchangeEvent(String id, int orderVersion, String action, String fromState,
                                String toState, String reason, String actorId, Instant occurredAt) {
    }

    /**
     * Immutable downstream-notification record emitted on revocation (XCH-03, ARC-05).
     *
     * @param id         opaque notification identifier
     * @param eventId    durable idempotency key for the downstream effect
     * @param target     named downstream recipient
     * @param channel    approved contact channel
     * @param outcome    recorded delivery outcome
     * @param actorId    authenticated actor that revoked the release
     * @param occurredAt server time the notification was recorded
     */
    public record Notification(String id, String eventId, String target, String channel, String outcome,
                               String actorId, Instant occurredAt) {
    }

    /**
     * Read projection of one order work item with its append-only ledgers (XCH-03).
     *
     * @param order         current order projection
     * @param history       append-only response events, oldest first
     * @param revisions     immutable released revisions, oldest first
     * @param retained      immutable retained recipient copies, oldest first
     * @param notifications immutable downstream notifications, oldest first
     */
    public record OrderView(ExchangeOrder order, List<ExchangeEvent> history, List<Revision> revisions,
                            List<RetainedCopy> retained, List<Notification> notifications) {

        /**
         * Defensively copy every list so the immutable view cannot be mutated.
         *
         * @param order         current order projection
         * @param history       append-only response events
         * @param revisions     immutable released revisions
         * @param retained      immutable retained recipient copies
         * @param notifications immutable downstream notifications
         */
        public OrderView {
            history = List.copyOf(history);
            revisions = List.copyOf(revisions);
            retained = List.copyOf(retained);
            notifications = List.copyOf(notifications);
        }
    }
}
