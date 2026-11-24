package com.aurevia.staffmsg.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.staffmsg.model.StaffMessagingContract.AttachmentLink;
import com.aurevia.staffmsg.model.StaffMessagingContract.ConversationKind;
import com.aurevia.staffmsg.model.StaffMessagingContract.DraftState;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolderAssignment;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailMetadata;
import com.aurevia.staffmsg.model.StaffMessagingContract.MessageSequence;
import com.aurevia.staffmsg.model.StaffMessagingContract.ParticipantRole;
import com.aurevia.staffmsg.model.StaffMessagingContract.ScanState;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffConversation;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffMessage;
import com.aurevia.staffmsg.model.StaffMessagingContract.StaffParticipant;

/** Unit acceptance for the staff-messaging data model (MSG-03, MSG-07, ARC-07). */
class StaffMessagingContractTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONVERSATION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void conversationKindHasStableWireValues() {
        for (ConversationKind kind : ConversationKind.values()) {
            assertEquals(kind, ConversationKind.from(kind.wire()));
        }
        assertEquals("mail_thread", ConversationKind.MAIL_THREAD.wire());
        assertThrows(IllegalArgumentException.class, () -> ConversationKind.from("broadcast"));
    }

    @Test
    void participantRoleHasStableWireValues() {
        for (ParticipantRole role : ParticipantRole.values()) {
            assertEquals(role, ParticipantRole.from(role.wire()));
        }
        assertEquals("member", ParticipantRole.MEMBER.wire());
        assertThrows(IllegalArgumentException.class, () -> ParticipantRole.from("admin"));
    }

    @Test
    void draftStateHasStableWireValues() {
        for (DraftState state : DraftState.values()) {
            assertEquals(state, DraftState.from(state.wire()));
        }
        assertEquals("sent", DraftState.SENT.wire());
        assertThrows(IllegalArgumentException.class, () -> DraftState.from("archived"));
    }

    @Test
    void scanStateHasStableWireValues() {
        for (ScanState state : ScanState.values()) {
            assertEquals(state, ScanState.from(state.wire()));
        }
        assertEquals("quarantined", ScanState.QUARANTINED.wire());
        assertThrows(IllegalArgumentException.class, () -> ScanState.from("infected"));
    }

    @Test
    void mailFolderHasStableWireValues() {
        for (MailFolder folder : MailFolder.values()) {
            assertEquals(folder, MailFolder.from(folder.wire()));
        }
        assertEquals("archive", MailFolder.ARCHIVE.wire());
        assertThrows(IllegalArgumentException.class, () -> MailFolder.from("spam"));
    }

    @Test
    void conversationClassifiesMailThreadsWithoutChangingItsKind() {
        StaffConversation direct = new StaffConversation(ORG, FACILITY, CONVERSATION,
                ConversationKind.DIRECT, null, "nurse-a", NOW);
        StaffConversation mail = new StaffConversation(ORG, FACILITY, CONVERSATION,
                ConversationKind.MAIL_THREAD, "Handover", "nurse-a", NOW);

        assertFalse(direct.isMailThread());
        assertTrue(mail.isMailThread());
        assertEquals(ConversationKind.MAIL_THREAD, mail.kind());
        assertEquals(FACILITY, mail.facilityId());
    }

    @Test
    void participantIsActiveOnlyInsideItsMembershipWindow() {
        StaffParticipant open = new StaffParticipant(ORG, CONVERSATION, "nurse-a",
                ParticipantRole.OWNER, NOW, null, false);
        StaffParticipant closed = new StaffParticipant(ORG, CONVERSATION, "nurse-b",
                ParticipantRole.MEMBER, NOW, NOW.plusSeconds(60), true);

        assertFalse(open.isActiveAt(NOW.minusSeconds(1)));
        assertTrue(open.isActiveAt(NOW));
        assertTrue(closed.isActiveAt(NOW.plusSeconds(59)));
        assertFalse(closed.isActiveAt(NOW.plusSeconds(60)));
        assertTrue(closed.muted());
        assertFalse(open.muted());
        assertEquals(ParticipantRole.OWNER, open.role());
    }

    @Test
    void sequenceStartsAtOneAndAdvancesWithoutGapsPerConversation() {
        MessageSequence sequence = MessageSequence.initial(CONVERSATION);
        assertEquals(0L, sequence.lastSequence());
        assertEquals(1L, sequence.next());

        MessageSequence first = sequence.advance();
        MessageSequence second = first.advance();
        MessageSequence third = second.advance();
        assertEquals(2L, first.next());
        assertEquals(3L, second.next());
        assertEquals(4L, third.next());
        assertEquals(CONVERSATION, third.conversationId());
        assertEquals(3L, third.lastSequence());
        assertThrows(IllegalArgumentException.class,
                () -> new MessageSequence(CONVERSATION, -1L).next());
    }

    @Test
    void correctionIsANewMessagePointingAtTheOriginal() {
        StaffMessage original = new StaffMessage(ORG, "m-1", CONVERSATION, 1L,
                "nurse-a", "nurse", "draft body", NOW, null);

        assertFalse(original.isCorrection());
        StaffMessage correction = original.correct("corrected body", "nurse-b",
                NOW.plusSeconds(5), new MessageSequence(CONVERSATION, 1L));
        assertTrue(correction.isCorrection());
        assertEquals(original.id(), correction.correctedMessageId());
        assertEquals(2L, correction.sequence());
        assertEquals("corrected body", correction.body());
        assertEquals("nurse-b", correction.senderSubject());
        assertEquals(NOW.plusSeconds(5), correction.sentAt());
        assertNotEquals(original.id(), correction.id());
        assertEquals("draft body", original.body());
    }

    @Test
    void mailMetadataFreezesRecipientsAndFlags() {
        List<String> recipients = new ArrayList<>(List.of("nurse-b"));
        List<String> cc = new ArrayList<>(List.of("doctor-a"));
        List<String> flags = new ArrayList<>(List.of("flagged"));
        MailMetadata draft = new MailMetadata(ORG, "m-1", "Handover",
                recipients, cc, flags, DraftState.DRAFT);
        MailMetadata sent = new MailMetadata(ORG, "m-2", "Handover",
                List.of("nurse-b"), List.of(), List.of(), DraftState.SENT);

        recipients.add("intruder");
        flags.clear();
        assertEquals(List.of("nurse-b"), draft.toRecipients());
        assertEquals(List.of("flagged"), draft.flags());
        assertEquals(List.of("doctor-a"), draft.ccRecipients());
        assertThrows(UnsupportedOperationException.class,
                () -> draft.toRecipients().add("intruder"));
        assertTrue(draft.isDraft());
        assertFalse(sent.isDraft());
        assertTrue(draft.hasFlag("flagged"));
        assertFalse(draft.hasFlag("urgent"));
        assertEquals("Handover", draft.subject());
        assertEquals("m-2", sent.messageId());
    }

    @Test
    void folderAssignmentIsOwnedPerParticipant() {
        MailFolderAssignment inbox = new MailFolderAssignment(ORG, "m-1", "nurse-b", MailFolder.INBOX);
        MailFolderAssignment archive = new MailFolderAssignment(ORG, "m-1", "doctor-a", MailFolder.ARCHIVE);

        assertEquals(MailFolder.INBOX, inbox.folder());
        assertEquals(MailFolder.ARCHIVE, archive.folder());
        assertEquals("nurse-b", inbox.participantSubject());
        assertEquals("m-1", archive.messageId());
    }

    @Test
    void attachmentIsDownloadableOnlyAfterACleanScan() {
        AttachmentLink pending = new AttachmentLink(ORG, "a-1", "m-1", "doc-1", "sha256:pending", ScanState.PENDING);
        AttachmentLink quarantined = new AttachmentLink(ORG, "a-2", "m-1", "doc-2", "sha256:bad", ScanState.QUARANTINED);
        AttachmentLink clean = new AttachmentLink(ORG, "a-3", "m-1", "doc-3", "sha256:good", ScanState.CLEAN);

        assertFalse(pending.isDownloadable());
        assertFalse(quarantined.isDownloadable());
        assertTrue(clean.isDownloadable());
        AttachmentLink cleared = quarantined.withScanState(ScanState.CLEAN);
        assertTrue(cleared.isDownloadable());
        assertEquals(ScanState.QUARANTINED, quarantined.scanState());
        assertEquals("doc-3", clean.documentId());
        assertEquals("sha256:good", clean.checksum());
    }
}
