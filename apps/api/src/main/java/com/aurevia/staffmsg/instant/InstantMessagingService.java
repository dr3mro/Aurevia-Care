package com.aurevia.staffmsg.instant;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.attachments.StaffAttachmentPublication;
import com.aurevia.staffmsg.instant.InstantMessagingContract.AddParticipantRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.ConversationView;
import com.aurevia.staffmsg.instant.InstantMessagingContract.CreateGroupRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.MarkReadRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.MessageView;
import com.aurevia.staffmsg.instant.InstantMessagingContract.OpenDirectRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.SendRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.UnreadView;
import com.aurevia.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;
import com.aurevia.tenant.TenantContext;

/**
 * Instant staff-chat commands and read models inside one facility (MSG-02, ARC-07).
 *
 * <p>Every command re-derives the active tenant from the request context, checks
 * that the actor and each peer have active staff membership, and re-checks
 * conversation membership on each read. A former member still sees history from
 * while they belonged to the conversation but cannot send or advance a read
 * cursor. Idempotency keys are matched against the durable client message id
 * before publication, and the store's uniqueness constraint is the backstop for
 * a concurrent retry.</p>
 */
@Service
public class InstantMessagingService {

    private static final int MAX_KEY_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 4000;

    private final InstantConversationStore store;
    private final Clock clock;
    private final StaffAttachmentPublication attachments;

    /**
     * Create the service with its tenant-scoped store, clock and publication seam.
     *
     * @param store       instant conversation store
     * @param clock       application clock used for server timestamps
     * @param attachments publication seam for files bound to the conversation
     */
    @Autowired
    public InstantMessagingService(InstantConversationStore store, Clock clock,
                                   StaffAttachmentPublication attachments) {
        this.store = store;
        this.clock = clock;
        this.attachments = attachments;
    }

    /**
     * Create the service without attachment publication, for callers that do not
     * own a message send (unit fixtures and security checks).
     *
     * @param store instant conversation store
     * @param clock application clock used for server timestamps
     */
    public InstantMessagingService(InstantConversationStore store, Clock clock) {
        this(store, clock, StaffAttachmentPublication.none());
    }

    /**
     * Open the direct conversation with a peer, or find the existing open one.
     *
     * @param request facility and peer subject
     * @return the conversation view
     * @throws ApiException on invalid input or an unauthorized peer
     */
    public ConversationView openDirect(OpenDirectRequest request) {
        TenantContext context = context();
        String peer = requireSubject(request.peerSubject(), "peerSubject");
        if (peer.equals(context.subject())) {
            throw invalid("a direct conversation needs another staff member");
        }
        String facility = requireSubject(request.facilityId(), "facilityId");
        requireActiveStaff(context.organizationId(), context.subject());
        requireActiveStaff(context.organizationId(), peer);
        requireFacilityScope(context.organizationId(), context.subject(), facility);
        requireFacilityScope(context.organizationId(), peer, facility);
        Optional<StaffConversation> existing = store.directConversation(
                context.organizationId(), facility, context.subject(), peer);
        StaffConversation conversation = existing.orElseGet(() -> {
            StaffConversation created = store.createConversation(context.organizationId(), facility,
                    ConversationKind.DIRECT, null, context.subject());
            store.addParticipant(context.organizationId(), created.id(), context.subject(),
                    ParticipantRole.OWNER, clock.instant());
            store.addParticipant(context.organizationId(), created.id(), peer,
                    ParticipantRole.MEMBER, clock.instant());
            return created;
        });
        return view(conversation, context);
    }

    /**
     * Create a group conversation with a bounded, distinct set of peers.
     *
     * @param request facility, optional topic and peer subjects
     * @return the conversation view
     * @throws ApiException on invalid input or an unauthorized peer
     */
    public ConversationView createGroup(CreateGroupRequest request) {
        TenantContext context = context();
        String facility = requireSubject(request.facilityId(), "facilityId");
        if (request.memberSubjects().isEmpty()) {
            throw invalid("a group needs at least one peer");
        }
        requireActiveStaff(context.organizationId(), context.subject());
        requireFacilityScope(context.organizationId(), context.subject(), facility);
        Set<String> peers = new LinkedHashSet<>();
        for (String peer : request.memberSubjects()) {
            String member = requireSubject(peer, "memberSubjects");
            if (!member.equals(context.subject())) {
                requireActiveStaff(context.organizationId(), member);
                requireFacilityScope(context.organizationId(), member, facility);
                peers.add(member);
            }
        }
        // The raw list may be non-empty yet name only the actor, which the loop
        // drops; a group with no peer cannot carry a conversation.
        if (peers.isEmpty()) {
            throw invalid("a group needs at least one peer");
        }
        StaffConversation conversation = store.createConversation(context.organizationId(), facility,
                ConversationKind.GROUP, request.topic(), context.subject());
        store.addParticipant(context.organizationId(), conversation.id(), context.subject(),
                ParticipantRole.OWNER, clock.instant());
        for (String peer : peers) {
            store.addParticipant(context.organizationId(), conversation.id(), peer,
                    ParticipantRole.MEMBER, clock.instant());
        }
        return view(conversation, context);
    }

    /**
     * Add a participant to a conversation owned by the caller.
     *
     * @param request facility, conversation and subject
     * @return the updated conversation view
     * @throws ApiException on denial or a duplicate active membership
     */
    public ConversationView addParticipant(AddParticipantRequest request) {
        TenantContext context = context();
        String subject = requireSubject(request.subject(), "subject");
        Access access = requireAccess(request.facilityId(), request.conversationId(), true);
        requireOwner(access);
        requireActiveStaff(context.organizationId(), subject);
        requireFacilityScope(context.organizationId(), subject, access.conversation().facilityId());
        if (activeParticipant(access.participants(), subject, clock.instant()).isPresent()) {
            throw new ApiException(409, "ALREADY_MEMBER", "the subject is already an active participant");
        }
        store.addParticipant(context.organizationId(), access.conversation().id(), subject,
                ParticipantRole.MEMBER, clock.instant());
        return view(access.conversation(), context);
    }

    /**
     * Close a participant's membership window; earlier history stays visible.
     *
     * @param facilityId    facility the conversation must belong to
     * @param conversationId owning conversation
     * @param subject       participant to remove
     * @return the updated conversation view
     * @throws ApiException on denial or invalid input
     */
    public ConversationView removeParticipant(String facilityId, String conversationId, String subject) {
        TenantContext context = context();
        String removed = requireSubject(subject, "subject");
        Access access = requireAccess(facilityId, conversationId, true);
        requireOwner(access);
        if (removed.equals(context.subject())) {
            throw invalid("an owner cannot remove itself");
        }
        store.leaveParticipant(context.organizationId(), access.conversation().id(), removed, clock.instant());
        return view(access.conversation(), context);
    }

    /**
     * Publish one message, or return the first message for a retried key.
     *
     * @param request        facility, conversation and body
     * @param idempotencyKey durable client message id from the HTTP header
     * @return the published (or original) message view
     * @throws ApiException on denial or invalid input
     */
    public MessageView send(SendRequest request, String idempotencyKey) {
        TenantContext context = context();
        String key = requireKey(idempotencyKey);
        String body = requireBody(request.body());
        Access access = requireAccess(request.facilityId(), request.conversationId(), true);
        Optional<StaffMessage> previous = store.messageByClientId(
                context.organizationId(), access.conversation().id(), key);
        StaffMessage message;
        if (previous.isPresent()) {
            message = previous.get();
        } else {
            // Preflight before the ledger write so a quarantined file cannot leave
            // a published message with no attachment grant.
            List<String> prepared = attachments.requirePublishable(context.organizationId(), context.subject(),
                    access.conversation().id());
            message = store.sendMessage(context.organizationId(), access.conversation().id(),
                    access.participant().role().wire(), body, key, clock.instant());
            Set<String> recipients = access.participants().stream()
                    .filter(participant -> participant.isActiveAt(clock.instant()))
                    .map(StaffParticipant::subject)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            attachments.publish(context.organizationId(), context.subject(), access.conversation().id(),
                    message.id(), recipients, prepared);
        }
        return messageView(message);
    }

    /**
     * List messages visible to the caller, bounded by a former member's leave time.
     *
     * @param facilityId     facility the conversation must belong to
     * @param conversationId owning conversation
     * @return messages ordered by sequence
     * @throws ApiException on denial or a missing conversation
     */
    public List<MessageView> messages(String facilityId, String conversationId) {
        TenantContext context = context();
        Access access = requireAccess(facilityId, conversationId, false);
        return store.messages(context.organizationId(), access.conversation().id(), 0L,
                access.participant().leftAt()).stream().map(InstantMessagingService::messageView).toList();
    }

    /**
     * Advance the caller's read cursor and return the remaining unread count.
     *
     * @param request facility, conversation and highest sequence read
     * @return the caller's unread view
     * @throws ApiException on denial or invalid input
     */
    public UnreadView markRead(MarkReadRequest request) {
        TenantContext context = context();
        if (request.upToSequence() < 0L) {
            throw invalid("upToSequence cannot be negative");
        }
        Access access = requireAccess(request.facilityId(), request.conversationId(), true);
        store.markRead(context.organizationId(), access.conversation().id(), context.subject(),
                request.upToSequence());
        return new UnreadView(access.conversation().id(),
                unread(access.conversation(), context.subject(), context));
    }

    /**
     * List unread counts for the caller's active conversations in one facility.
     *
     * @param facilityId facility filter
     * @return one count per conversation
     * @throws ApiException when no tenant context is bound
     */
    public List<UnreadView> unreadCounts(String facilityId) {
        TenantContext context = context();
        requireActiveStaff(context.organizationId(), context.subject());
        String facility = requireSubject(facilityId, "facilityId");
        requireFacilityScope(context.organizationId(), context.subject(), facility);
        return store.conversationsForFacility(context.organizationId(), facility, context.subject())
                .stream()
                .map(conversation -> new UnreadView(conversation.id(),
                        unread(conversation, context.subject(), context)))
                .toList();
    }

    /**
     * List the caller's active conversations in a facility with their read state.
     *
     * @param facilityId facility filter
     * @return conversation views ordered by creation time
     * @throws ApiException when no tenant context is bound
     */
    public List<ConversationView> conversations(String facilityId) {
        TenantContext context = context();
        requireActiveStaff(context.organizationId(), context.subject());
        String facility = requireSubject(facilityId, "facilityId");
        requireFacilityScope(context.organizationId(), context.subject(), facility);
        return store.conversationsForFacility(context.organizationId(), facility, context.subject())
                .stream().map(conversation -> view(conversation, context)).toList();
    }

    private ConversationView view(StaffConversation conversation, TenantContext context) {
        long last = store.messages(context.organizationId(), conversation.id(), 0L, null).stream()
                .mapToLong(StaffMessage::sequence).max().orElse(0L);
        return new ConversationView(conversation.id(), conversation.facilityId(),
                conversation.kind().wire(), conversation.topic(), last,
                unread(conversation, context.subject(), context));
    }

    private long unread(StaffConversation conversation, String subject, TenantContext context) {
        long cursor = store.readSequence(context.organizationId(), conversation.id(), subject);
        return store.messages(context.organizationId(), conversation.id(), cursor, null).stream()
                .filter(message -> !message.senderSubject().equals(subject)).count();
    }

    private static MessageView messageView(StaffMessage message) {
        return new MessageView(message.id(), message.sequence(), message.senderSubject(),
                message.senderRole(), message.body(), message.sentAt());
    }

    private Access requireAccess(String facilityId, String conversationId, boolean requireActive) {
        TenantContext context = context();
        // A still-open participant row is not proof of a current staff role: a
        // revoked membership must lose access on the next command, not when the
        // conversation membership is eventually closed.
        requireActiveStaff(context.organizationId(), context.subject());
        String facility = requireSubject(facilityId, "facilityId");
        String id = requireSubject(conversationId, "conversationId");
        StaffConversation conversation = store.conversation(context.organizationId(), id)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "conversation not found"));
        if (!conversation.facilityId().equals(facility)) {
            throw new ApiException(403, "FORBIDDEN", "the conversation belongs to another facility");
        }
        requireFacilityScope(context.organizationId(), context.subject(), conversation.facilityId());
        List<StaffParticipant> participants = store.participants(context.organizationId(), id);
        StaffParticipant participant = participants.stream()
                .filter(candidate -> candidate.subject().equals(context.subject()))
                .findFirst()
                .orElseThrow(() -> new ApiException(403, "FORBIDDEN", "the caller is not a participant"));
        if (requireActive && !participant.isActiveAt(clock.instant())) {
            throw new ApiException(403, "FORBIDDEN", "the caller is no longer a participant");
        }
        return new Access(conversation, participant, participants);
    }

    private void requireOwner(Access access) {
        if (access.participant().role() != ParticipantRole.OWNER) {
            throw new ApiException(403, "FORBIDDEN", "only an owner may change participants");
        }
    }

    private void requireActiveStaff(String organizationId, String subject) {
        if (!store.activeStaff(organizationId, subject)) {
            throw new ApiException(403, "FORBIDDEN", "the subject has no active staff role");
        }
    }

    private void requireFacilityScope(String organizationId, String subject, String facilityId) {
        if (!store.facilityWithinScope(organizationId, subject, facilityId)) {
            throw new ApiException(403, "FORBIDDEN", "the subject is outside the active facility");
        }
    }

    private static Optional<StaffParticipant> activeParticipant(List<StaffParticipant> participants,
                                                                String subject, Instant at) {
        return participants.stream()
                .filter(candidate -> candidate.subject().equals(subject))
                .filter(candidate -> candidate.isActiveAt(at))
                .findFirst();
    }

    private static String requireSubject(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
        return value;
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw invalid("Idempotency-Key must be between 1 and " + MAX_KEY_LENGTH + " characters");
        }
        return key;
    }

    private static String requireBody(String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
            throw invalid("body must be between 1 and " + MAX_BODY_LENGTH + " characters");
        }
        return body;
    }

    private static ApiException invalid(String message) {
        return new ApiException(422, "INVALID_INPUT", message);
    }

    private static TenantContext context() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "FORBIDDEN", "no active tenant context");
        }
        return context;
    }

    private record Access(StaffConversation conversation, StaffParticipant participant,
                          List<StaffParticipant> participants) {
    }
}
