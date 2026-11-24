package com.aurevia.staffmsg.instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.instant.InstantMessagingContract.AddParticipantRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.CreateGroupRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.MarkReadRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.OpenDirectRequest;
import com.aurevia.staffmsg.instant.InstantMessagingContract.SendRequest;
import com.aurevia.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;
import com.aurevia.tenant.TenantContext;

/** Unit acceptance for instant staff conversations (MSG-02, ARC-07). */
class InstantMessagingServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_FACILITY = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ACTOR = "nurse-a";
    private static final String PEER = "nurse-b";
    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private InstantConversationStore store;
    private InstantMessagingService service;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext(ORG, ACTOR, "STAFF_MESSAGING", "correlation-1"));
        store = mock(InstantConversationStore.class);
        // Default allow: individual tests override a subject to model a revoked role.
        when(store.activeStaff(anyString(), anyString())).thenReturn(true);
        when(store.facilityWithinScope(anyString(), anyString(), anyString())).thenReturn(true);
        service = new InstantMessagingService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static StaffConversation conversation(String facility, ConversationKind kind) {
        return new StaffConversation(ORG, facility, CONVERSATION, kind, "topic", ACTOR, NOW);
    }

    private static StaffParticipant participant(String subject, ParticipantRole role, Instant leftAt) {
        return new StaffParticipant(ORG, CONVERSATION, subject, role, NOW, leftAt, false);
    }

    private static StaffMessage message(long sequence, String sender) {
        return new StaffMessage(ORG, "message-" + sequence, CONVERSATION, sequence, sender,
                "nurse", "body-" + sequence, NOW.plusSeconds(sequence), null);
    }

    private ApiException failure(String facility) {
        return assertThrows(ApiException.class,
                () -> service.send(new SendRequest(facility, CONVERSATION, "body"), "key-1"));
    }

    /**
     * A subject whose staff role was revoked must not keep acting on a conversation
     * whose participant row is still open — active staff status is re-checked on
     * every command, not only when a conversation is first opened.
     */
    @Test
    void aRevokedStaffMemberCannotKeepActingOnAnOpenConversation() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(false);
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));
        when(store.sendMessage(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(message(1L, ACTOR));

        assertEquals(403, failure(FACILITY).status());
        verify(store, never()).sendMessage(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.markRead(new MarkReadRequest(FACILITY, CONVERSATION, 1L))).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.messages(FACILITY, CONVERSATION)).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.unreadCounts(FACILITY)).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.conversations(FACILITY)).status());
    }

    @Test
    void openDirectReusesTheStillOpenConversation() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        when(store.activeStaff(ORG, PEER)).thenReturn(true);
        when(store.directConversation(ORG, FACILITY, ACTOR, PEER))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.messages(ORG, CONVERSATION, 0L, null)).thenReturn(List.of());

        assertEquals(CONVERSATION, service.openDirect(new OpenDirectRequest(FACILITY, PEER)).id());
        verify(store, never()).createConversation(anyString(), anyString(), any(), any(), anyString());
        verify(store, never()).addParticipant(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void openDirectCreatesAConversationWithOwnerAndMember() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        when(store.activeStaff(ORG, PEER)).thenReturn(true);
        when(store.directConversation(ORG, FACILITY, ACTOR, PEER)).thenReturn(Optional.empty());
        when(store.createConversation(ORG, FACILITY, ConversationKind.DIRECT, null, ACTOR))
                .thenReturn(conversation(FACILITY, ConversationKind.DIRECT));
        when(store.messages(ORG, CONVERSATION, 0L, null)).thenReturn(List.of());

        assertEquals(ConversationKind.DIRECT.wire(),
                service.openDirect(new OpenDirectRequest(FACILITY, PEER)).kind());
        verify(store).addParticipant(ORG, CONVERSATION, ACTOR, ParticipantRole.OWNER, NOW);
        verify(store).addParticipant(ORG, CONVERSATION, PEER, ParticipantRole.MEMBER, NOW);
    }

    @Test
    void openDirectRefusesAPeerWithoutAnActiveRole() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        when(store.activeStaff(ORG, PEER)).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> service.openDirect(new OpenDirectRequest(FACILITY, PEER)));
        assertEquals(403, error.status());
    }

    @Test
    void openDirectRefusesTheActorAsItsOwnPeerAndBlankSubjects() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.openDirect(new OpenDirectRequest(FACILITY, ACTOR))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.openDirect(new OpenDirectRequest(FACILITY, null))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.openDirect(new OpenDirectRequest(FACILITY, "  "))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.openDirect(new OpenDirectRequest("  ", PEER))).status());
        verify(store, never()).activeStaff(anyString(), anyString());
    }

    @Test
    void createGroupAddsTheOwnerAndEachDistinctPeerOnce() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        when(store.activeStaff(ORG, PEER)).thenReturn(true);
        when(store.createConversation(ORG, FACILITY, ConversationKind.GROUP, "topic", ACTOR))
                .thenReturn(conversation(FACILITY, ConversationKind.GROUP));
        when(store.messages(ORG, CONVERSATION, 0L, null)).thenReturn(List.of());

        service.createGroup(new CreateGroupRequest(FACILITY, "topic", List.of(PEER, ACTOR, PEER)));
        verify(store).addParticipant(ORG, CONVERSATION, ACTOR, ParticipantRole.OWNER, NOW);
        verify(store).addParticipant(ORG, CONVERSATION, PEER, ParticipantRole.MEMBER, NOW);
    }

    @Test
    void createGroupRequiresAtLeastOnePeer() {
        ApiException empty = assertThrows(ApiException.class,
                () -> service.createGroup(new CreateGroupRequest(FACILITY, "topic", List.of())));
        assertEquals(422, empty.status());

        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        ApiException onlyActor = assertThrows(ApiException.class,
                () -> service.createGroup(new CreateGroupRequest(FACILITY, "topic", List.of(ACTOR))));
        assertEquals(422, onlyActor.status());
        verify(store, never()).createConversation(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void addParticipantRequiresAnOwnerAnActiveStaffMemberAndANewSubject() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.GROUP)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.MEMBER, null)));
        ApiException notOwner = assertThrows(ApiException.class,
                () -> service.addParticipant(new AddParticipantRequest(FACILITY, CONVERSATION, PEER)));
        assertEquals(403, notOwner.status());

        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));
        when(store.activeStaff(ORG, PEER)).thenReturn(false);
        ApiException inactive = assertThrows(ApiException.class,
                () -> service.addParticipant(new AddParticipantRequest(FACILITY, CONVERSATION, PEER)));
        assertEquals(403, inactive.status());

        when(store.activeStaff(ORG, PEER)).thenReturn(true);
        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        ApiException duplicate = assertThrows(ApiException.class,
                () -> service.addParticipant(new AddParticipantRequest(FACILITY, CONVERSATION, ACTOR)));
        assertEquals(409, duplicate.status());

        when(store.messages(ORG, CONVERSATION, 0L, null)).thenReturn(List.of());
        service.addParticipant(new AddParticipantRequest(FACILITY, CONVERSATION, PEER));
        verify(store).addParticipant(ORG, CONVERSATION, PEER, ParticipantRole.MEMBER, NOW);
    }

    @Test
    void removeParticipantClosesTheMembershipButKeepsHistory() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.GROUP)));
        when(store.participants(ORG, CONVERSATION)).thenReturn(List.of(
                participant(ACTOR, ParticipantRole.OWNER, null),
                participant(PEER, ParticipantRole.MEMBER, null)));
        when(store.messages(ORG, CONVERSATION, 0L, null)).thenReturn(List.of());

        service.removeParticipant(FACILITY, CONVERSATION, PEER);
        verify(store).leaveParticipant(ORG, CONVERSATION, PEER, NOW);

        ApiException self = assertThrows(ApiException.class,
                () -> service.removeParticipant(FACILITY, CONVERSATION, ACTOR));
        assertEquals(422, self.status());
    }

    @Test
    void removeParticipantRefusesANonOwner() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.GROUP)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.MEMBER, null)));

        ApiException error = assertThrows(ApiException.class,
                () -> service.removeParticipant(FACILITY, CONVERSATION, PEER));
        assertEquals(403, error.status());
    }

    @Test
    void aRetriedSendReturnsTheFirstMessageWithoutPublishingAgain() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));
        when(store.messageByClientId(ORG, CONVERSATION, "key-1")).thenReturn(Optional.of(message(1L, ACTOR)));

        assertEquals(1L, service.send(new SendRequest(FACILITY, CONVERSATION, "body"), "key-1").sequence());
        verify(store, never()).sendMessage(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void sendPublishesAnImmutableMessageWithTheRecordedRole() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.MEMBER, null)));
        when(store.messageByClientId(ORG, CONVERSATION, "key-2")).thenReturn(Optional.empty());
        when(store.sendMessage(ORG, CONVERSATION, "member", "body", "key-2", NOW))
                .thenReturn(message(2L, ACTOR));

        assertEquals(2L, service.send(new SendRequest(FACILITY, CONVERSATION, "body"), "key-2").sequence());
        verify(store).sendMessage(ORG, CONVERSATION, "member", "body", "key-2", NOW);
    }

    @Test
    void sendValidatesTheIdempotencyKeyAndBody() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));

        assertEquals(422, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(FACILITY, CONVERSATION, "body"), "  ")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(FACILITY, CONVERSATION, ""), "key-3")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(FACILITY, CONVERSATION, "body"), null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(FACILITY, CONVERSATION, "body"), "k".repeat(201))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(FACILITY, CONVERSATION, null), "key-3")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(FACILITY, CONVERSATION, "a".repeat(4001)), "key-3")).status());
        verify(store, never()).sendMessage(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void aFormerMemberCannotReadLaterMessagesButSeesEarlierHistory() {
        TenantContext.set(new TenantContext(ORG, PEER, "STAFF_MESSAGING", "correlation-2"));
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION)).thenReturn(List.of(
                participant(ACTOR, ParticipantRole.OWNER, null),
                participant(PEER, ParticipantRole.MEMBER, NOW.minusSeconds(3))));
        when(store.messages(ORG, CONVERSATION, 0L, NOW.minusSeconds(3)))
                .thenReturn(List.of(message(1L, PEER)));

        assertEquals(1, service.messages(FACILITY, CONVERSATION).size());
        verify(store).messages(eq(ORG), eq(CONVERSATION), eq(0L), eq(NOW.minusSeconds(3)));
    }

    @Test
    void aFormerMemberCannotSendOrAdvanceAReadCursor() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, NOW.minusSeconds(3))));

        assertEquals(403, failure(FACILITY).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.markRead(new MarkReadRequest(FACILITY, CONVERSATION, 2L))).status());
        verify(store, never()).sendMessage(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
        verify(store, never()).markRead(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void aCallerWhoIsNotAParticipantIsRefused() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION)).thenReturn(List.of());

        assertEquals(403, failure(FACILITY).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.messages(FACILITY, CONVERSATION)).status());
    }

    @Test
    void blankFacilityAndConversationIdentifiersAreRefused() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.unreadCounts("  ")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.conversations(null)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> service.messages(FACILITY, "  ")).status());
    }

    @Test
    void anActiveMemberReadsTheWholeThread() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.GROUP)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));
        when(store.messages(ORG, CONVERSATION, 0L, null))
                .thenReturn(List.of(message(1L, PEER), message(2L, ACTOR)));

        assertEquals(2, service.messages(FACILITY, CONVERSATION).size());
        verify(store).messages(eq(ORG), eq(CONVERSATION), eq(0L), isNull());
    }

    @Test
    void markReadAdvancesTheCursorAndCountsOnlyOtherPeoplesMessages() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));
        when(store.readSequence(ORG, CONVERSATION, ACTOR)).thenReturn(1L);
        when(store.messages(ORG, CONVERSATION, 1L, null))
                .thenReturn(List.of(message(2L, PEER), message(3L, ACTOR)));

        assertEquals(1L, service.markRead(new MarkReadRequest(FACILITY, CONVERSATION, 3L)).unreadCount());
        verify(store).markRead(ORG, CONVERSATION, ACTOR, 3L);
    }

    @Test
    void markReadRefusesANegativeSequence() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.markRead(new MarkReadRequest(FACILITY, CONVERSATION, -1L)));
        assertEquals(422, error.status());
        verify(store, never()).markRead(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void unreadCountsArePerPersonBecauseEachCursorIsPerSubject() {
        when(store.conversationsForFacility(ORG, FACILITY, ACTOR))
                .thenReturn(List.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.conversationsForFacility(ORG, FACILITY, PEER))
                .thenReturn(List.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.readSequence(ORG, CONVERSATION, ACTOR)).thenReturn(0L);
        when(store.messages(ORG, CONVERSATION, 0L, null))
                .thenReturn(List.of(message(1L, PEER)));
        when(store.readSequence(ORG, CONVERSATION, PEER)).thenReturn(1L);
        when(store.messages(ORG, CONVERSATION, 1L, null)).thenReturn(List.of());

        TenantContext.set(new TenantContext(ORG, PEER, "STAFF_MESSAGING", "correlation-2"));
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
        TenantContext.set(new TenantContext(ORG, ACTOR, "STAFF_MESSAGING", "correlation-1"));
        assertEquals(1L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    @Test
    void conversationsCarryTheLastSequenceAndTheCallersUnreadCount() {
        when(store.conversationsForFacility(ORG, FACILITY, ACTOR))
                .thenReturn(List.of(conversation(FACILITY, ConversationKind.GROUP)));
        when(store.messages(ORG, CONVERSATION, 0L, null))
                .thenReturn(List.of(message(1L, ACTOR), message(4L, PEER)));

        var view = service.conversations(FACILITY).get(0);
        assertEquals(4L, view.lastSequence());
        assertEquals(1L, view.unreadCount());
    }

    @Test
    void openDirectRefusesAPeerOutsideTheFacility() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        when(store.activeStaff(ORG, PEER)).thenReturn(true);
        when(store.facilityWithinScope(ORG, ACTOR, FACILITY)).thenReturn(true);
        when(store.facilityWithinScope(ORG, PEER, FACILITY)).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> service.openDirect(new OpenDirectRequest(FACILITY, PEER)));
        assertEquals(403, error.status());
        verify(store, never()).createConversation(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void createGroupRefusesAPeerOutsideTheFacility() {
        when(store.activeStaff(ORG, ACTOR)).thenReturn(true);
        when(store.activeStaff(ORG, PEER)).thenReturn(true);
        when(store.facilityWithinScope(ORG, ACTOR, FACILITY)).thenReturn(true);
        when(store.facilityWithinScope(ORG, PEER, FACILITY)).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> service.createGroup(new CreateGroupRequest(FACILITY, "topic", List.of(PEER))));
        assertEquals(403, error.status());
        verify(store, never()).createConversation(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void addParticipantRefusesASubjectOutsideTheFacility() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.GROUP)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));
        when(store.activeStaff(ORG, PEER)).thenReturn(true);
        when(store.facilityWithinScope(ORG, PEER, FACILITY)).thenReturn(false);

        ApiException error = assertThrows(ApiException.class,
                () -> service.addParticipant(new AddParticipantRequest(FACILITY, CONVERSATION, PEER)));
        assertEquals(403, error.status());
        verify(store, never()).addParticipant(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void aCallerOutsideTheFacilityIsRefusedOnEveryCommand() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));
        when(store.facilityWithinScope(ORG, ACTOR, FACILITY)).thenReturn(false);

        assertEquals(403, failure(FACILITY).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.messages(FACILITY, CONVERSATION)).status());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.markRead(new MarkReadRequest(FACILITY, CONVERSATION, 1L))).status());
        verify(store, never()).sendMessage(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void aConversationInAnotherFacilityIsRefused() {
        when(store.conversation(ORG, CONVERSATION))
                .thenReturn(Optional.of(conversation(FACILITY, ConversationKind.DIRECT)));
        when(store.participants(ORG, CONVERSATION))
                .thenReturn(List.of(participant(ACTOR, ParticipantRole.OWNER, null)));

        assertEquals(403, failure(OTHER_FACILITY).status());
        verify(store, never()).sendMessage(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void aConversationFromAnotherTenantIsConcealed() {
        when(store.conversation(ORG, CONVERSATION)).thenReturn(Optional.empty());

        ApiException error = failure(FACILITY);
        assertEquals(404, error.status());
        assertTrue(error.getMessage().contains("conversation"));
    }

    @Test
    void aRequestWithoutTenantContextIsRefused() {
        TenantContext.clear();
        ApiException error = assertThrows(ApiException.class,
                () -> service.unreadCounts(FACILITY));
        assertEquals(403, error.status());
    }
}
