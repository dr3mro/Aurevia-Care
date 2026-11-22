package com.aurevia.smartclinic.staffmsg.instant;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Persistence boundary for instant staff conversations (MSG-02, ARC-07).
 *
 * <p>Every method names the active organization explicitly even though forced
 * row-level security also confines the statement to the guarded transaction.
 * Conversation, participant and message values come from the shared
 * {@link com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract} domain so
 * no second copy of the messaging vocabulary is introduced. Read cursors are a
 * process-local projection: the V91 schema has no durable read-state column,
 * which is recorded in the story notes, so a cursor is never treated as a
 * clinical record.</p>
 */
public interface InstantConversationStore {

    /**
     * Find the still-open direct conversation shared by two subjects.
     *
     * @param organizationId active organization
     * @param facilityId     facility both subjects belong to
     * @param firstSubject   first participant
     * @param secondSubject  second participant
     * @return the open conversation, or empty when none exists
     */
    Optional<StaffConversation> directConversation(String organizationId, String facilityId,
                                                   String firstSubject, String secondSubject);

    /**
     * Create and persist a conversation of the requested kind.
     *
     * @param organizationId active organization
     * @param facilityId     owning facility
     * @param kind           direct or group kind
     * @param topic          topic, or {@code null}
     * @param creatorSubject authenticated creator
     * @return the stored conversation
     */
    StaffConversation createConversation(String organizationId, String facilityId,
                                         ConversationKind kind, String topic, String creatorSubject);

    /**
     * Load one conversation by identifier inside the active organization.
     *
     * @param organizationId active organization
     * @param conversationId conversation identifier
     * @return the conversation, or empty when absent or foreign
     */
    Optional<StaffConversation> conversation(String organizationId, String conversationId);

    /**
     * List the full membership history of a conversation, including leavers.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @return all memberships ordered by join time
     */
    List<StaffParticipant> participants(String organizationId, String conversationId);

    /**
     * Add a membership row; a later leave does not delete history.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @param subject        staff subject
     * @param role           owner or member role
     * @param joinedAt       membership start
     */
    void addParticipant(String organizationId, String conversationId, String subject,
                        ParticipantRole role, Instant joinedAt);

    /**
     * Close a membership window without deleting the membership row.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @param subject        staff subject
     * @param leftAt         membership end
     */
    void leaveParticipant(String organizationId, String conversationId, String subject, Instant leftAt);

    /**
     * Find a message previously published with a client idempotency key.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @param clientId       durable client message id
     * @return the first message for that key, or empty when new
     */
    Optional<StaffMessage> messageByClientId(String organizationId, String conversationId, String clientId);

    /**
     * Append one immutable message; the database allocates the sequence and
     * binds the sender from the guarded request actor.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @param senderRole     role recorded at send time
     * @param body           immutable content
     * @param clientId       durable client message id
     * @param sentAt         server publication time
     * @return the stored message with its allocated sequence
     */
    StaffMessage sendMessage(String organizationId, String conversationId, String senderRole,
                             String body, String clientId, Instant sentAt);

    /**
     * List messages after a sequence, optionally bounded by a join/leave cut-off.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @param afterSequence  exclusive lower sequence bound
     * @param notAfter       exclusive upper time bound, or {@code null} for no bound
     * @return messages ordered by sequence
     */
    List<StaffMessage> messages(String organizationId, String conversationId,
                                long afterSequence, Instant notAfter);

    /**
     * Report whether a subject has active membership and at least one active role.
     *
     * @param organizationId active organization
     * @param subject        staff subject
     * @return {@code true} only for an active member with an active role
     */
    boolean activeStaff(String organizationId, String subject);

    /**
     * Report whether a facility is inside a subject's assigned scope.
     *
     * <p>The tenant-owned membership-facility fold is authoritative: a subject
     * with no recorded assignment keeps organization-wide scope, while an
     * assigned subject is confined to that set. A facility that does not exist
     * in the active organization is refused.</p>
     *
     * @param organizationId active organization
     * @param subject        staff subject
     * @param facilityId     facility the subject must be allowed to use
     * @return {@code true} only when the facility exists in the tenant and is in scope
     */
    boolean facilityWithinScope(String organizationId, String subject, String facilityId);

    /**
     * List conversations in a facility where a subject is still an active member.
     *
     * @param organizationId active organization
     * @param facilityId     facility filter
     * @param subject        staff subject
     * @return visible conversations ordered by creation time
     */
    List<StaffConversation> conversationsForFacility(String organizationId, String facilityId,
                                                     String subject);

    /**
     * Advance one person's read cursor; a cursor never moves backwards.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @param subject        reader subject
     * @param upToSequence   highest sequence read
     */
    void markRead(String organizationId, String conversationId, String subject, long upToSequence);

    /**
     * Read a person's cursor for one conversation.
     *
     * @param organizationId active organization
     * @param conversationId owning conversation
     * @param subject        reader subject
     * @return the last read sequence, or zero when nothing was read
     */
    long readSequence(String organizationId, String conversationId, String subject);
}
