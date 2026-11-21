package com.aurevia.smartclinic.staffmsg.instant;

/**
 * Value types for instant staff conversations inside one facility (MSG-02, ARC-07).
 *
 * <p>These records carry the results of the instant-conversation domain
 * operations. They deliberately contain no behavior: all invariants are decided
 * by {@link com.aurevia.smartclinic.staffmsg.instant.InstantConversationManager}
 * and every persisted record is already defined by
 * {@link com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract}, which
 * this class reuses rather than duplicates.</p>
 */
public final class InstantMessagingContract {

    private InstantMessagingContract() {
    }

    /**
     * The authoritative identity of a staff member who may take part in a
     * conversation: active membership of the active organization, the facility
     * they are assigned to and the role key used as the message sender role.
     *
     * @param subject    stable staff subject within the organization
     * @param facilityId facility the staff member is assigned to
     * @param roleKey    active role key recorded on sent messages
     * @return the immutable identity
     */
    public record StaffIdentity(String subject, String facilityId, String roleKey) {
    }

    /**
     * Per-person unread position in one conversation. The unread count is the
     * number of messages with a sequence greater than the reader's own cursor,
     * so it is independent of every other participant.
     *
     * @param conversationId owning conversation
     * @param subject        reader whose cursor this is
     * @param lastReadSequence highest sequence the reader marked read
     * @param lastSequence    highest published sequence in the conversation
     * @param unread          messages newer than the reader's cursor
     * @return the immutable count
     */
    public record UnreadCount(String conversationId, String subject, long lastReadSequence,
                              long lastSequence, long unread) {
    }
}
