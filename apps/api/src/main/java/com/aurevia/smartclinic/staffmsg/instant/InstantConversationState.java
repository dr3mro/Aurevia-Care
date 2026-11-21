package com.aurevia.smartclinic.staffmsg.instant;

import java.util.List;
import java.util.Optional;

import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Persistence port for instant staff conversations (MSG-02, ARC-07).
 *
 * <p>The domain operations in
 * {@link com.aurevia.smartclinic.staffmsg.instant.InstantConversationManager}
 * depend on this small interface so that every authorization and sequencing
 * rule can be exercised without a database. A production implementation must
 * keep conversations, membership history and the immutable message ledger in
 * the tenant-guarded staff-messaging tables; membership rows are never
 * physically deleted so that history stays visible to former members.</p>
 */
public interface InstantConversationState {

    /**
     * Find the direct conversation for an unordered pair of subjects in a facility.
     *
     * @param organizationId active organization identifier
     * @param facilityId     owning facility
     * @param first          first subject
     * @param second         second subject
     * @return the existing direct conversation, or empty when none exists
     */
    Optional<StaffConversation> findDirect(String organizationId, String facilityId,
                                           String first, String second);

    /**
     * Find one conversation by identifier and organization.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @return the conversation, or empty when absent or outside the organization
     */
    Optional<StaffConversation> findConversation(String organizationId, String conversationId);

    /**
     * Persist a new conversation.
     *
     * @param conversation conversation to store
     * @param members      initial member subjects, used to index a direct pair
     */
    void saveConversation(StaffConversation conversation, List<String> members);

    /**
     * Read one membership of a conversation.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @param subject        staff subject
     * @return the membership, including a recorded leave instant, or empty
     */
    Optional<StaffParticipant> participant(String organizationId, String conversationId, String subject);

    /**
     * List all membership rows of a conversation, including former members.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @return all memberships in membership order
     */
    List<StaffParticipant> participants(String organizationId, String conversationId);

    /**
     * Insert or replace a membership row. A former member is re-activated by a
     * new row with a null leave instant; no existing history row is destroyed.
     *
     * @param participant membership to store
     */
    void saveParticipant(StaffParticipant participant);

    /**
     * Find a message by its durable client identifier inside a conversation.
     *
     * @param organizationId  active organization identifier
     * @param conversationId  conversation identifier
     * @param clientMessageId durable client message identifier
     * @return the first message for that key, or empty when none was stored
     */
    Optional<StaffMessage> messageByClientId(String organizationId, String conversationId,
                                             String clientMessageId);

    /**
     * Append one message. The implementation allocates the next gap-free
     * sequence for the conversation inside the same write.
     *
     * @param message         message to append
     * @param clientMessageId durable idempotency key stored alongside the message
     */
    void saveMessage(StaffMessage message, String clientMessageId);

    /**
     * List published messages in ascending sequence order.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @return the immutable message ledger for the conversation
     */
    List<StaffMessage> messages(String organizationId, String conversationId);

    /**
     * Return the next sequence a message in this conversation would take.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @return the next one-based sequence
     */
    long nextSequence(String organizationId, String conversationId);

    /**
     * Return the highest published sequence, or zero for an empty conversation.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @return the highest sequence
     */
    long lastSequence(String organizationId, String conversationId);

    /**
     * Return one reader's current read cursor, or zero when they never marked read.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @param subject        reader subject
     * @return the highest sequence the reader marked read
     */
    long lastReadSequence(String organizationId, String conversationId, String subject);

    /**
     * Advance one reader's read cursor. Implementations must never move it back.
     *
     * @param organizationId active organization identifier
     * @param conversationId conversation identifier
     * @param subject        reader subject
     * @param sequence       new read cursor, already bounded by the domain service
     */
    void saveReadSequence(String organizationId, String conversationId, String subject, long sequence);
}
