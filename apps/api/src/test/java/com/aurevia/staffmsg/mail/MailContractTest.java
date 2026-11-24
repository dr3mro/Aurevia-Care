package com.aurevia.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.mail.MailContract.DraftView;
import com.aurevia.staffmsg.mail.MailContract.MailView;
import com.aurevia.staffmsg.mail.MailContract.Recipient;
import com.aurevia.staffmsg.mail.MailContract.RecipientKind;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.mail.MailContract.ThreadView;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;

/** Frozen projections of the staff mail domain (MSG-02, ARC-07). */
class MailContractTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void recipientKindHasStableWireValuesAndNoBlindCopyKind() {
        for (RecipientKind kind : RecipientKind.values()) {
            assertEquals(kind, RecipientKind.from(kind.wire()));
        }
        assertEquals("unit_mailbox", RecipientKind.UNIT_MAILBOX.wire());
        assertThrows(IllegalArgumentException.class, () -> RecipientKind.from("bcc"));
        assertEquals(3, RecipientKind.values().length);
    }

    @Test
    void recipientFactoriesValidateTheirValue() {
        assertEquals(new Recipient(RecipientKind.PERSON, "nurse-a"), Recipient.person("nurse-a"));
        assertEquals(new Recipient(RecipientKind.ROLE, "nurse"), Recipient.role("nurse"));
        assertEquals(new Recipient(RecipientKind.UNIT_MAILBOX, "ward-a"),
                Recipient.unitMailbox("ward-a"));
        assertEquals("INVALID_RECIPIENT", assertThrows(ApiException.class,
                () -> Recipient.person("  ")).code());
        assertThrows(ApiException.class, () -> Recipient.role(null));
    }

    @Test
    void draftFreezesEveryClientList() {
        List<Recipient> to = new ArrayList<>(List.of(Recipient.person("nurse-b")));
        List<Recipient> cc = new ArrayList<>(List.of(Recipient.person("doctor-a")));
        List<String> attachments = new ArrayList<>(List.of("doc-1"));
        DraftView draft = new DraftView(ORG, "d-1", "facility-a", "nurse-a", 1, "Handover", "body",
                to, cc, attachments, null, NOW);

        to.add(Recipient.person("intruder"));
        cc.clear();
        attachments.add("doc-2");
        assertEquals(List.of(Recipient.person("nurse-b")), draft.to());
        assertEquals(List.of(Recipient.person("doctor-a")), draft.cc());
        assertEquals(List.of("doc-1"), draft.attachmentDocumentIds());
        assertThrows(UnsupportedOperationException.class,
                () -> draft.to().add(Recipient.person("intruder")));
        assertThrows(UnsupportedOperationException.class,
                () -> draft.cc().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> draft.attachmentDocumentIds().add("doc-3"));
    }

    @Test
    void sentMailFreezesEveryProjectionList() {
        SentMail message = new SentMail(ORG, "m-1", "facility-a", "m-1", "Handover", "body",
                "nurse-a", "staff", NOW, new ArrayList<>(List.of(Recipient.person("nurse-b"))),
                new ArrayList<>(List.of(Recipient.role("nurse"))),
                new ArrayList<>(List.of("nurse-b")), new ArrayList<>(List.of("doc-1")),
                new ArrayList<>(List.of("flagged")));

        assertEquals(List.of("nurse-b"), message.resolvedRecipients());
        assertEquals(List.of("doc-1"), message.attachmentDocumentIds());
        assertEquals(List.of("flagged"), message.flags());
        assertThrows(UnsupportedOperationException.class,
                () -> message.to().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> message.cc().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> message.resolvedRecipients().add("intruder"));
        assertThrows(UnsupportedOperationException.class,
                () -> message.attachmentDocumentIds().add("doc-2"));
        assertThrows(UnsupportedOperationException.class,
                () -> message.flags().add("urgent"));
    }

    @Test
    void threadAndMailViewsAreImmutableProjections() {
        SentMail message = new SentMail(ORG, "m-1", "facility-a", "m-1", "Handover", "body",
                "nurse-a", "staff", NOW, List.of(), List.of(), List.of(), List.of(), List.of());
        MailView view = new MailView(message, MailFolder.INBOX);
        ThreadView thread = new ThreadView("m-1", "handover", new ArrayList<>(List.of(view)));

        assertEquals(MailFolder.INBOX, view.viewerFolder());
        assertEquals(message, view.message());
        assertEquals("m-1", thread.threadId());
        assertEquals("handover", thread.subject());
        assertEquals(List.of(view), thread.messages());
        assertTrue(thread.messages() instanceof List);
        assertThrows(UnsupportedOperationException.class,
                () -> thread.messages().add(new MailView(message, MailFolder.SENT)));
    }
}
