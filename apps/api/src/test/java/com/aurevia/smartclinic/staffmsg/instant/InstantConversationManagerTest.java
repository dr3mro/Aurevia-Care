package com.aurevia.smartclinic.staffmsg.instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.UnreadCount;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.StaffParticipant;

/**
 * Unit acceptance for instant staff conversations (MSG-02, ARC-07). Synthetic
 * subjects and facilities only; no real identity, patient data or credential.
 */
class InstantConversationManagerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACILITY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private InMemoryInstantConversationState state;
    private FakeStaffGate gate;
    private MutableClock clock;
    private InstantConversationManager manager;

    @BeforeEach
    void setUp() {
        state = new InMemoryInstantConversationState();
        gate = new FakeStaffGate();
        clock = new MutableClock(NOW);
        manager = new InstantConversationManager(state, gate, clock);
        gate.register(ORG, "alice", FACILITY_A, "nurse");
        gate.register(ORG, "bob", FACILITY_A, "clinician");
        gate.register(ORG, "carol", FACILITY_A, "reception");
        gate.register(ORG, "dave", FACILITY_B, "nurse");
        gate.register(ORG, "dave2", FACILITY_B, "clinician");
        gate.register(OTHER_ORG, "erin", FACILITY_A, "nurse");
    }

    @Test
    void openDirectCreatesConversationWithOrderedMembership() {
        StaffConversation conversation = manager.openDirect(ORG, "bob", "alice", "shift handover");
        assertEquals(ConversationKind.DIRECT, conversation.kind());
        assertEquals(FACILITY_A, conversation.facilityId());
        assertEquals("shift handover", conversation.topic());
        assertEquals(NOW, conversation.createdAt());
        List<StaffParticipant> members = state.participants(ORG, conversation.id());
        assertEquals(2, members.size());
        assertEquals(ParticipantRole.OWNER,
                members.stream().filter(p -> p.subject().equals("bob")).findFirst().orElseThrow().role());
    }

    @Test
    void openDirectReusesTheSameConversationInEitherOrder() {
        StaffConversation first = manager.openDirect(ORG, "alice", "bob", "first");
        StaffConversation again = manager.openDirect(ORG, "bob", "alice", "second");
        assertEquals(first.id(), again.id());
        assertEquals(2, state.participants(ORG, first.id()).size());
    }

    @Test
    void openDirectRefusesSelfAndForeignFacilityAndTenant() {
        assertThrows(ApiException.class, () -> manager.openDirect(ORG, "alice", "alice", "self"));
        ApiException facility = assertThrows(ApiException.class,
                () -> manager.openDirect(ORG, "alice", "dave", "cross facility"));
        assertEquals(403, facility.status());
        assertThrows(ApiException.class, () -> manager.openDirect(ORG, "alice", "erin", "cross tenant"));
        assertThrows(ApiException.class, () -> manager.openDirect(ORG, "alice", "nobody", "inactive"));
        assertThrows(ApiException.class, () -> manager.openDirect(ORG, "nobody", "bob", "inactive"));
    }

    @Test
    void createGroupDeDuplicatesMembersAndRejectsEmptyGroups() {
        StaffConversation group = manager.createGroup(ORG, "alice", "roster",
                List.of("bob", "bob", "carol"));
        assertEquals(ConversationKind.GROUP, group.kind());
        assertEquals(3, state.participants(ORG, group.id()).size());
        assertThrows(ApiException.class,
                () -> manager.createGroup(ORG, "alice", "empty", List.of()));
        assertThrows(ApiException.class,
                () -> manager.createGroup(ORG, "alice", "inactive", List.of("nobody")));
        assertThrows(ApiException.class,
                () -> manager.createGroup(ORG, "alice", "other facility", List.of("dave")));
    }

    @Test
    void ownerCanAddAndRemoveParticipantsButHistoryWindowIsPreserved() {
        StaffConversation group = manager.createGroup(ORG, "alice", "roster", List.of("bob"));
        StaffParticipant added = manager.addParticipant(ORG, "alice", group.id(), "carol");
        assertEquals(ParticipantRole.MEMBER, added.role());
        assertEquals(added, manager.addParticipant(ORG, "alice", group.id(), "carol"));

        StaffParticipant removed = manager.removeParticipant(ORG, "alice", group.id(), "bob");
        assertNotNull(removed.leftAt());
        assertEquals(removed, manager.removeParticipant(ORG, "alice", group.id(), "bob"));
    }

    @Test
    void onlyOwnerMayManageMembershipAndManagementFailuresAreRefused() {
        StaffConversation group = manager.createGroup(ORG, "alice", "roster", List.of("bob"));
        assertThrows(ApiException.class,
                () -> manager.addParticipant(ORG, "bob", group.id(), "carol"));
        assertThrows(ApiException.class,
                () -> manager.removeParticipant(ORG, "bob", group.id(), "carol"));
        assertThrows(ApiException.class,
                () -> manager.addParticipant(ORG, "alice", group.id(), "nobody"));
        assertThrows(ApiException.class,
                () -> manager.addParticipant(ORG, "alice", group.id(), "dave"));
        assertThrows(ApiException.class,
                () -> manager.removeParticipant(ORG, "alice", group.id(), "nobody"));
    }

    @Test
    void retriedSendMakesOneMessageAndDoesNotAdvanceTheSequence() {
        String conversationId = manager.openDirect(ORG, "alice", "bob", "topic").id();
        StaffMessage first = manager.send(ORG, "alice", conversationId, "key-1", "hello");
        StaffMessage retry = manager.send(ORG, "alice", conversationId, "key-1", "hello again");
        assertEquals(first.id(), retry.id());
        assertEquals(first.sequence(), retry.sequence());
        assertEquals("hello", retry.body());
        assertEquals(1, state.messages(ORG, conversationId).size());
        StaffMessage next = manager.send(ORG, "bob", conversationId, "key-2", "hi");
        assertEquals(2L, next.sequence());
        assertEquals("nurse", first.senderRole());
    }

    @Test
    void sendValidatesInputAndRefusesNonMembers() {
        String conversationId = manager.openDirect(ORG, "alice", "bob", "topic").id();
        assertThrows(ApiException.class, () -> manager.send(ORG, "alice", conversationId, null, "body"));
        assertThrows(ApiException.class, () -> manager.send(ORG, "alice", conversationId, "  ", "body"));
        assertThrows(ApiException.class, () -> manager.send(ORG, "alice", conversationId, "key", null));
        assertThrows(ApiException.class, () -> manager.send(ORG, "alice", conversationId, "key", "  "));
        assertThrows(ApiException.class, () -> manager.send(ORG, "carol", conversationId, "key", "body"));
        assertThrows(ApiException.class, () -> manager.send(ORG, "nobody", conversationId, "key", "body"));
        assertThrows(ApiException.class, () -> manager.send(ORG, "alice", "missing", "key", "body"));
    }

    @Test
    void removedMemberCannotReadLaterMessagesButKeepsEarlierHistory() {
        String conversationId = manager.createGroup(ORG, "alice", "roster", List.of("bob")).id();
        StaffMessage before = manager.send(ORG, "bob", conversationId, "k1", "before leave");
        clock.advance(Duration.ofMinutes(1));
        manager.removeParticipant(ORG, "alice", conversationId, "bob");
        clock.advance(Duration.ofMinutes(1));
        StaffMessage after = manager.send(ORG, "alice", conversationId, "k2", "after leave");

        List<StaffMessage> visible = manager.messages(ORG, "bob", conversationId);
        assertEquals(1, visible.size());
        assertEquals(before.id(), visible.get(0).id());
        assertTrue(visible.stream().noneMatch(m -> m.id().equals(after.id())));
        assertThrows(ApiException.class,
                () -> manager.send(ORG, "bob", conversationId, "k3", "must be refused"));
        assertThrows(ApiException.class,
                () -> manager.markRead(ORG, "bob", conversationId, 2L));
    }

    @Test
    void unreadCountsArePerPersonAndExcludeOwnMessagesAndFormerMembers() {
        String conversationId = manager.openDirect(ORG, "alice", "bob", "topic").id();
        assertNotNull(manager.send(ORG, "alice", conversationId, "k1", "from alice"));
        manager.send(ORG, "bob", conversationId, "k2", "from bob");

        UnreadCount alice = manager.unreadCount(ORG, "alice", conversationId);
        UnreadCount bob = manager.unreadCount(ORG, "bob", conversationId);
        assertEquals(1L, alice.unread());
        assertEquals(1L, bob.unread());
        assertEquals("alice", alice.subject());
        assertEquals(2L, alice.lastSequence());

        UnreadCount afterRead = manager.markRead(ORG, "alice", conversationId, 2L);
        assertEquals(0L, afterRead.unread());
        assertEquals(0L, manager.unreadCount(ORG, "alice", conversationId).unread());
        assertEquals(1L, manager.unreadCount(ORG, "bob", conversationId).unread());
    }

    @Test
    void readCursorIsMonotonicAndClampedToPublishedSequence() {
        String conversationId = manager.openDirect(ORG, "alice", "bob", "topic").id();
        manager.send(ORG, "bob", conversationId, "k1", "one");
        manager.send(ORG, "bob", conversationId, "k2", "two");

        UnreadCount clamped = manager.markRead(ORG, "alice", conversationId, 99L);
        assertEquals(2L, clamped.lastReadSequence());
        assertEquals(2L, clamped.lastSequence());
        assertEquals(0L, clamped.unread());

        UnreadCount unchanged = manager.markRead(ORG, "alice", conversationId, 0L);
        assertEquals(2L, unchanged.lastReadSequence());
        assertThrows(ApiException.class, () -> manager.markRead(ORG, "alice", conversationId, -1L));
    }

    @Test
    void accessIsRefusedAcrossTenantsFacilitiesAndForNonMembers() {
        String conversationId = manager.openDirect(ORG, "alice", "bob", "topic").id();
        assertThrows(ApiException.class, () -> manager.messages(ORG, "dave", conversationId));
        assertThrows(ApiException.class, () -> manager.messages(OTHER_ORG, "erin", conversationId));
        assertThrows(ApiException.class, () -> manager.messages(ORG, "carol", conversationId));
        assertThrows(ApiException.class, () -> manager.unreadCount(ORG, "nobody", conversationId));
        assertThrows(ApiException.class, () -> manager.messages(ORG, "alice", "missing"));
    }

    @Test
    void conversationFromAnotherFacilityCannotBeReachedByFacilityAMember() {
        String conversationId = manager.openDirect(ORG, "dave", "dave2", "topic").id();
        ApiException denial = assertThrows(ApiException.class,
                () -> manager.send(ORG, "alice", conversationId, "k", "intrusion"));
        assertEquals(403, denial.status());
        assertThrows(ApiException.class, () -> manager.messages(ORG, "alice", conversationId));
        assertThrows(ApiException.class, () -> manager.unreadCount(ORG, "alice", conversationId));
        assertThrows(ApiException.class, () -> manager.markRead(ORG, "alice", conversationId, 1L));
        assertThrows(ApiException.class,
                () -> manager.addParticipant(ORG, "alice", conversationId, "bob"));
        assertThrows(ApiException.class,
                () -> manager.removeParticipant(ORG, "alice", conversationId, "dave"));
    }

    /** Deterministic clock that tests can advance to exercise membership windows. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
