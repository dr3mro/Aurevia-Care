package com.aurevia.smartclinic.staffmsg.instant;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.StaffIdentity;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.UnreadCount;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Domain operations for instant staff conversations inside one facility
 * (MSG-02, ARC-07).
 *
 * <p>Every operation re-resolves the acting staff member through
 * {@link com.aurevia.smartclinic.staffmsg.instant.StaffGate} and refuses a
 * subject from another tenant or facility before touching any state. Direct
 * conversations are reused for a pair of subjects; group membership can be
 * extended and revoked, but a leave only records a timestamp so that messages
 * sent while a member was present remain readable. Sending is idempotent by
 * durable client message identifier, and read cursors are per reader.</p>
 */
public final class InstantConversationManager {

    private static final String FORBIDDEN = "FORBIDDEN";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String INVALID = "INVALID_INPUT";

    private final InstantConversationState state;
    private final StaffGate gate;
    private final Clock clock;

    /**
     * Create the domain service with its persistence and authorization ports.
     *
     * @param state persistence port for conversations, messages and read cursors
     * @param gate  authorization port resolving active staff of a tenant
     * @param clock clock used for all server timestamps
     */
    public InstantConversationManager(InstantConversationState state, StaffGate gate, Clock clock) {
        this.state = state;
        this.gate = gate;
        this.clock = clock;
    }

    /**
     * Open or reuse the direct conversation between two staff of one facility.
     *
     * @param organizationId active organization identifier
     * @param subject        staff member opening the conversation
     * @param otherSubject   other staff member
     * @param topic          optional subject label, or {@code null}
     * @return the existing or newly created direct conversation
     * @throws ApiException when either subject is not active staff of the
     *                      organization, they are in different facilities, or
     *                      the subject addresses themselves
     */
    public StaffConversation openDirect(String organizationId, String subject,
                                        String otherSubject, String topic) {
        StaffIdentity actor = requireActor(organizationId, subject);
        StaffIdentity other = requireActor(organizationId, otherSubject);
        if (!actor.facilityId().equals(other.facilityId())) {
            throw new ApiException(403, FORBIDDEN, "staff of different facilities cannot converse");
        }
        if (subject.equals(otherSubject)) {
            throw new ApiException(422, INVALID, "a direct conversation needs another staff member");
        }
        String first = subject.compareTo(otherSubject) < 0 ? subject : otherSubject;
        String second = first.equals(subject) ? otherSubject : subject;
        Optional<StaffConversation> existing =
                state.findDirect(organizationId, actor.facilityId(), first, second);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        StaffConversation conversation = storeConversation(organizationId, actor.facilityId(),
                ConversationKind.DIRECT, topic, subject, now, List.of(subject, otherSubject));
        return conversation;
    }

    /**
     * Create a group conversation for staff of the creator's facility.
     *
     * @param organizationId active organization identifier
     * @param subject        staff member creating the group
     * @param topic          group label
     * @param members        additional staff subjects; duplicates are ignored
     * @return the new group conversation
     * @throws ApiException when any subject is not active staff of the
     *                      organization, is in another facility, or no other
     *                      member remains after de-duplication
     */
    public StaffConversation createGroup(String organizationId, String subject, String topic,
                                         List<String> members) {
        StaffIdentity actor = requireActor(organizationId, subject);
        List<String> uniqueMembers = new ArrayList<>();
        for (String member : members) {
            StaffIdentity identity = requireActor(organizationId, member);
            if (!actor.facilityId().equals(identity.facilityId())) {
                throw new ApiException(403, FORBIDDEN, "staff of another facility cannot join the group");
            }
            if (!uniqueMembers.contains(member)) {
                uniqueMembers.add(member);
            }
        }
        if (uniqueMembers.isEmpty()) {
            throw new ApiException(422, INVALID, "a group needs at least one other member");
        }
        List<String> participants = new ArrayList<>();
        participants.add(subject);
        participants.addAll(uniqueMembers);
        return storeConversation(organizationId, actor.facilityId(), ConversationKind.GROUP,
                topic, subject, clock.instant(), participants);
    }

    /**
     * Add a member to a conversation the actor owns, within the same facility.
     *
     * @param organizationId active organization identifier
     * @param subject        owning staff member
     * @param conversationId conversation identifier
     * @param newSubject     staff member to add
     * @return the new or already-active membership
     * @throws ApiException when the actor is not an owner, or either subject is
     *                      not active staff of the conversation's facility
     */
    public StaffParticipant addParticipant(String organizationId, String subject,
                                           String conversationId, String newSubject) {
        Access access = access(organizationId, subject, conversationId);
        if (access.membership().role() != ParticipantRole.OWNER) {
            throw new ApiException(403, FORBIDDEN, "only an owner may add participants");
        }
        StaffIdentity target = requireActor(organizationId, newSubject);
        if (!access.conversation().facilityId().equals(target.facilityId())) {
            throw new ApiException(403, FORBIDDEN, "staff of another facility cannot join");
        }
        Optional<StaffParticipant> existing =
                state.participant(organizationId, conversationId, newSubject);
        if (existing.isPresent()) {
            // Rejoin is not representable without overwriting the earlier
            // membership window, so an existing row (including a departed one)
            // is returned unchanged and history stays intact.
            return existing.get();
        }
        StaffParticipant membership = new StaffParticipant(organizationId, conversationId, newSubject,
                ParticipantRole.MEMBER, clock.instant(), null, false);
        state.saveParticipant(membership);
        return membership;
    }

    /**
     * Remove a member by recording a leave instant; earlier messages stay visible.
     *
     * @param organizationId active organization identifier
     * @param subject        owning staff member
     * @param conversationId conversation identifier
     * @param targetSubject  member to remove
     * @return the membership with its leave instant recorded
     * @throws ApiException when the actor is not an owner or the target has no membership
     */
    public StaffParticipant removeParticipant(String organizationId, String subject,
                                              String conversationId, String targetSubject) {
        Access access = access(organizationId, subject, conversationId);
        if (access.membership().role() != ParticipantRole.OWNER) {
            throw new ApiException(403, FORBIDDEN, "only an owner may remove participants");
        }
        StaffParticipant target = state.participant(organizationId, conversationId, targetSubject)
                .orElseThrow(() -> new ApiException(404, NOT_FOUND, "membership not found"));
        if (target.leftAt() != null) {
            return target;
        }
        StaffParticipant left = new StaffParticipant(organizationId, conversationId, targetSubject,
                target.role(), target.joinedAt(), clock.instant(), target.muted());
        state.saveParticipant(left);
        return left;
    }

    /**
     * Publish a message once for a durable client message identifier.
     *
     * @param organizationId  active organization identifier
     * @param subject         sending staff member
     * @param conversationId  conversation identifier
     * @param clientMessageId durable idempotency key
     * @param body            immutable message content
     * @return the originally stored message on a retry, else the new message
     * @throws ApiException when the sender is not an active member of the
     *                      conversation's facility, or input is blank
     */
    public StaffMessage send(String organizationId, String subject, String conversationId,
                             String clientMessageId, String body) {
        Access access = access(organizationId, subject, conversationId);
        Instant now = clock.instant();
        if (!access.membership().isActiveAt(now)) {
            throw new ApiException(403, FORBIDDEN, "a former member cannot send messages");
        }
        if (blank(clientMessageId)) {
            throw new ApiException(422, INVALID, "client message id is required");
        }
        if (blank(body)) {
            throw new ApiException(422, INVALID, "message body is required");
        }
        Optional<StaffMessage> existing =
                state.messageByClientId(organizationId, conversationId, clientMessageId);
        if (existing.isPresent()) {
            return existing.get();
        }
        StaffMessage message = new StaffMessage(organizationId, UUID.randomUUID().toString(),
                conversationId, state.nextSequence(organizationId, conversationId), subject,
                access.actor().roleKey(), body, now, null);
        state.saveMessage(message, clientMessageId);
        return message;
    }

    /**
     * Mark a conversation read for one reader up to a sequence.
     *
     * @param organizationId active organization identifier
     * @param subject        reading staff member
     * @param conversationId conversation identifier
     * @param upToSequence   highest sequence the reader has seen
     * @return the resulting bounded, per-reader unread counts
     * @throws ApiException when the reader is not an active member of the
     *                      conversation's facility, or the sequence is negative
     */
    public UnreadCount markRead(String organizationId, String subject, String conversationId,
                                long upToSequence) {
        Access access = access(organizationId, subject, conversationId);
        if (!access.membership().isActiveAt(clock.instant())) {
            throw new ApiException(403, FORBIDDEN, "a former member cannot mark messages read");
        }
        if (upToSequence < 0L) {
            throw new ApiException(422, INVALID, "read sequence cannot be negative");
        }
        long maximum = state.lastSequence(organizationId, conversationId);
        long target = Math.min(upToSequence, maximum);
        long current = state.lastReadSequence(organizationId, conversationId, subject);
        if (target > current) {
            state.saveReadSequence(organizationId, conversationId, subject, target);
            current = target;
        }
        return unreadCount(access, current);
    }

    /**
     * List the messages a staff member may read in a conversation. A former
     * member sees history up to their leave instant, never later messages.
     *
     * @param organizationId active organization identifier
     * @param subject        reading staff member
     * @param conversationId conversation identifier
     * @return visible messages in ascending sequence order
     * @throws ApiException when the subject has no membership or facility access
     */
    public List<StaffMessage> messages(String organizationId, String subject, String conversationId) {
        Access access = access(organizationId, subject, conversationId);
        return state.messages(organizationId, conversationId).stream()
                .filter(message -> access.membership().isActiveAt(message.sentAt()))
                .toList();
    }

    /**
     * Return per-person unread counts for one conversation.
     *
     * @param organizationId active organization identifier
     * @param subject        reading staff member
     * @param conversationId conversation identifier
     * @return the reader's unread position
     * @throws ApiException when the subject has no membership or facility access
     */
    public UnreadCount unreadCount(String organizationId, String subject, String conversationId) {
        Access access = access(organizationId, subject, conversationId);
        return unreadCount(access,
                state.lastReadSequence(organizationId, conversationId, subject));
    }

    private UnreadCount unreadCount(Access access, long lastRead) {
        String organizationId = access.conversation().organizationId();
        String conversationId = access.conversation().id();
        String subject = access.actor().subject();
        long unread = state.messages(organizationId, conversationId).stream()
                .filter(message -> message.sequence() > lastRead)
                .filter(message -> !subject.equals(message.senderSubject()))
                .filter(message -> access.membership().isActiveAt(message.sentAt()))
                .count();
        return new UnreadCount(conversationId, subject, lastRead,
                state.lastSequence(organizationId, conversationId), unread);
    }

    private Access access(String organizationId, String subject, String conversationId) {
        StaffIdentity actor = requireActor(organizationId, subject);
        StaffConversation conversation = requireConversation(organizationId, conversationId);
        if (!actor.facilityId().equals(conversation.facilityId())) {
            throw new ApiException(403, FORBIDDEN, "conversation belongs to another facility");
        }
        StaffParticipant membership = state.participant(organizationId, conversationId, subject)
                .orElseThrow(() -> new ApiException(403, FORBIDDEN, "subject is not a member"));
        return new Access(actor, conversation, membership);
    }

    private StaffConversation storeConversation(String organizationId, String facilityId,
                                                ConversationKind kind, String topic, String creator,
                                                Instant now, List<String> members) {
        String conversationId = UUID.randomUUID().toString();
        StaffConversation conversation = new StaffConversation(
                organizationId, facilityId, conversationId, kind, topic, creator, now);
        state.saveConversation(conversation, members);
        for (int i = 0; i < members.size(); i++) {
            String member = members.get(i);
            ParticipantRole role = i == 0 ? ParticipantRole.OWNER : ParticipantRole.MEMBER;
            state.saveParticipant(new StaffParticipant(organizationId, conversationId, member,
                    role, now, null, false));
        }
        return conversation;
    }

    private StaffIdentity requireActor(String organizationId, String subject) {
        return gate.activeStaff(organizationId, subject).orElseThrow(
                () -> new ApiException(403, FORBIDDEN,
                        "subject is not active staff of the active organization"));
    }

    private StaffConversation requireConversation(String organizationId, String conversationId) {
        return state.findConversation(organizationId, conversationId).orElseThrow(
                () -> new ApiException(404, NOT_FOUND, "conversation not found"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Access(StaffIdentity actor, StaffConversation conversation, StaffParticipant membership) {
    }
}
