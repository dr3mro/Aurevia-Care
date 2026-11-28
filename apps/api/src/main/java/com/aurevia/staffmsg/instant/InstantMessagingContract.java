package com.aurevia.staffmsg.instant;

import java.time.Instant;
import java.util.List;

/**
 * Wire contract for instant staff conversations inside one facility.
 *
 * <p>Commands carry the facility selected by the caller; the organization and
 * the authenticated actor always come from the request tenant context. A
 * message id is stable and a client idempotency key is carried by the
 * {@code Idempotency-Key} header on send, never in the body. Views are immutable
 * snapshots so a reader cannot mutate a projection after it leaves the store.</p>
 */
public final class InstantMessagingContract {

    private InstantMessagingContract() {
    }

    /**
     * Open (or find) the direct conversation between the actor and one peer.
     *
     * @param facilityId facility of both staff members
     * @param peerSubject subject of the other participant
     * @return the command
     */
    public record OpenDirectRequest(String facilityId, String peerSubject) {
    }

    /**
     * Create a group conversation with a bounded set of initial members.
     *
     * @param facilityId     facility of every participant
     * @param topic          short topic, or {@code null} for an untitled group
     * @param memberSubjects peer subjects to add alongside the actor
     * @return the command
     */
    public record CreateGroupRequest(String facilityId, String topic, List<String> memberSubjects) {
        /**
         * Freeze the peer list so a caller cannot change it after validation.
         */
        public CreateGroupRequest {
            memberSubjects = List.copyOf(memberSubjects);
        }

        /**
         * @return the immutable peer list
         */
        @Override
        public List<String> memberSubjects() {
            return List.copyOf(memberSubjects);
        }
    }

    /**
     * Publish one message to the conversation named by the request path.
     *
     * <p>The body conversation id is accepted for client compatibility only: it
     * must equal the path parameter and is never used for authorization or the
     * durable write. The service refuses a mismatch with 422 INVALID_INPUT.</p>
     *
     * @param facilityId    facility the conversation must belong to
     * @param conversationId owning conversation, which must match the request path
     * @param body          immutable message content
     * @return the command
     */
    public record SendRequest(String facilityId, String conversationId, String body) {
    }

    /**
     * Add one participant to an existing conversation.
     *
     * @param facilityId    facility the conversation must belong to
     * @param conversationId owning conversation
     * @param subject       staff subject to add
     * @return the command
     */
    public record AddParticipantRequest(String facilityId, String conversationId, String subject) {
    }

    /**
     * Advance the caller's read cursor for one conversation.
     *
     * @param facilityId    facility the conversation must belong to
     * @param conversationId owning conversation
     * @param upToSequence  highest sequence the caller has read
     * @return the command
     */
    public record MarkReadRequest(String facilityId, String conversationId, long upToSequence) {
    }

    /**
     * Conversation metadata with the caller's current unread count.
     *
     * @param id           conversation identifier
     * @param facilityId   owning facility
     * @param kind         stable conversation kind wire value
     * @param topic        topic, or {@code null}
     * @param lastSequence highest stored sequence
     * @param unreadCount  messages after the caller's cursor that they did not send
     * @return the immutable view
     */
    public record ConversationView(String id, String facilityId, String kind, String topic,
                                   long lastSequence, long unreadCount) {
    }

    /**
     * One immutable message as seen by an authorized participant.
     *
     * @param id            message identifier
     * @param sequence      gap-free per-conversation sequence
     * @param senderSubject authenticated sender
     * @param senderRole    role recorded at send time
     * @param body          message content
     * @param sentAt        server timestamp
     * @return the immutable view
     */
    public record MessageView(String id, long sequence, String senderSubject, String senderRole,
                              String body, Instant sentAt) {
    }

    /**
     * Per-conversation unread count for one person.
     *
     * @param conversationId owning conversation
     * @param unreadCount    unread messages for that person
     * @return the immutable view
     */
    public record UnreadView(String conversationId, long unreadCount) {
    }
}
