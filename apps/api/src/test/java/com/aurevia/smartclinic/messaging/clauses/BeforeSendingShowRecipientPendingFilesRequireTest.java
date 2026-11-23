package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.attachments.InMemoryStaffAttachmentStore;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.ScanRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.SendRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffActor;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.UploadRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.View;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentLimits;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentService;
import com.aurevia.smartclinic.staffmsg.mail.MailCommands.SendCommand;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.DraftView;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.Recipient;
import com.aurevia.smartclinic.staffmsg.mail.MailContract.SentMail;
import com.aurevia.smartclinic.staffmsg.mail.MailService;
import com.aurevia.smartclinic.staffmsg.mail.MailStore;
import com.aurevia.smartclinic.staffmsg.mail.RecipientDirectory;

/**
 * MSG-03 clause 2 (API test): the pending files of a draft are reviewable before
 * a send and publication happens only on an explicit confirm. Every attachment
 * case enters through {@link com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentService#pending}
 * or {@link com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentService#send},
 * the commands the staff-attachment controller calls. The recipient half enters
 * through {@link com.aurevia.smartclinic.staffmsg.mail.MailService#send}, which
 * requires the sender to confirm the resolved recipient set. Synthetic fixtures
 * and a fixed clock only; no production code, contract or migration is changed.
 */
class BeforeSendingShowRecipientPendingFilesRequireTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String UPLOADER = "nurse-a";
    private static final String RECIPIENT = "clinician-b";
    private static final String DRAFT = "conversation-1";
    private static final String MESSAGE = "message-1";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);

    private InMemoryStaffAttachmentStore store;
    private StaffAttachmentService service;
    private StaffActor sender;
    private StaffActor scanner;
    private StaffActor auditor;

    /** Build a fresh synthetic attachment service with a fixed clock. */
    @BeforeEach
    void setUp() {
        store = new InMemoryStaffAttachmentStore();
        service = new StaffAttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC), new StaffAttachmentLimits());
        sender = StaffActor.staff(ORG, UPLOADER, "nurse");
        scanner = StaffActor.staff(ORG, "scanner-a", "security_scanner");
        auditor = StaffActor.staff(ORG, "auditor-a", "privacy_auditor");
    }

    /** Upload the synthetic PDF and record a clean verdict; return the stored projection. */
    private StaffAttachment cleanFile(String fileName) {
        return scanned(fileName, "clean", "ok");
    }

    /** Upload the synthetic PDF and record a failed verdict with the reason the sender sees. */
    private StaffAttachment quarantinedFile(String fileName, String reason) {
        return scanned(fileName, "failed", reason);
    }

    private StaffAttachment scanned(String fileName, String verdict, String detail) {
        StaffAttachment created = service.upload(sender,
                new UploadRequest(DRAFT, "application/pdf", fileName, PDF, checksum(PDF)));
        service.scan(scanner, created.id(), new ScanRequest(verdict, detail));
        return store.attachment(ORG, created.id()).orElseThrow();
    }

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** List the attachments that carry a SENT fact, proving which files were actually published. */
    private List<String> sentAttachmentIds() {
        return store.audits(ORG).stream()
                .filter(fact -> fact.type() == AuditType.SENT)
                .map(AttachmentAudit::attachmentId)
                .toList();
    }

    /** The sender can review every unsent file of the draft, including why a file is held. */
    @Test
    void pendingListsUnsentDraftFilesBeforeSend() {
        StaffAttachment clean = cleanFile("clean.pdf");
        StaffAttachment flagged = quarantinedFile("flagged.pdf", "signature scan found a threat");

        List<StaffAttachment> pending = service.pending(sender, DRAFT);

        assertEquals(Set.of(clean.id(), flagged.id()),
                pending.stream().map(StaffAttachment::id).collect(java.util.stream.Collectors.toSet()));
        assertTrue(pending.stream().noneMatch(StaffAttachment::sent));
        View flaggedView = service.view(sender, flagged.id());
        assertTrue(flaggedView.audit().stream().anyMatch(fact -> fact.type() == AuditType.SCANNED_QUARANTINED
                && "signature scan found a threat".equals(fact.detail())));
    }

    /** A confirmed send publishes only the named clean files and leaves the quarantined file held. */
    @Test
    void sendPublishesNamedCleanFilesOnlyWithExplicitConfirmation() {
        StaffAttachment first = cleanFile("first.pdf");
        StaffAttachment second = cleanFile("second.pdf");
        StaffAttachment flagged = quarantinedFile("flagged.pdf", "threat");

        List<StaffAttachment> sent = service.send(sender, new SendRequest(DRAFT, MESSAGE,
                List.of(first.id(), second.id()), Set.of(RECIPIENT), true));

        assertEquals(List.of(first.id(), second.id()), sent.stream().map(StaffAttachment::id).toList());
        assertTrue(store.attachment(ORG, first.id()).orElseThrow().sent());
        assertTrue(store.attachment(ORG, second.id()).orElseThrow().sent());
        assertFalse(store.attachment(ORG, flagged.id()).orElseThrow().sent());
        assertEquals(Set.of(first.id(), second.id()), Set.copyOf(sentAttachmentIds()));
        assertEquals(List.of(flagged.id()), service.pending(sender, DRAFT).stream()
                .map(StaffAttachment::id).toList());
    }

    /** An unconfirmed send is refused and writes no publication, even for a real clean file. */
    @Test
    void sendWithoutConfirmationRefusesAndSendsNothing() {
        StaffAttachment clean = cleanFile("clean.pdf");

        ApiException refused = assertThrows(ApiException.class, () -> service.send(sender,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), false)));

        assertEquals(422, refused.status());
        assertEquals("CONFIRM_REQUIRED", refused.code());
        assertFalse(store.attachment(ORG, clean.id()).orElseThrow().sent());
        assertTrue(sentAttachmentIds().isEmpty());
    }

    /** One quarantined member blocks the whole batch: validation runs before any publication. */
    @Test
    void sendRefusesQuarantinedFileAndPublishesNoBatchMember() {
        StaffAttachment first = cleanFile("first.pdf");
        StaffAttachment flagged = quarantinedFile("flagged.pdf", "signature scan found a threat");
        StaffAttachment second = cleanFile("second.pdf");

        ApiException refused = assertThrows(ApiException.class, () -> service.send(sender,
                new SendRequest(DRAFT, MESSAGE, List.of(first.id(), flagged.id(), second.id()),
                        Set.of(RECIPIENT), true)));

        assertEquals(409, refused.status());
        assertEquals("QUARANTINED_FILE", refused.code());
        assertFalse(store.attachment(ORG, first.id()).orElseThrow().sent());
        assertFalse(store.attachment(ORG, second.id()).orElseThrow().sent());
        assertTrue(sentAttachmentIds().isEmpty());
    }

    /** Roles without send permission, a foreign draft and a foreign tenant all fail closed. */
    @Test
    void sendRefusesWrongRoleAndForeignDraft() {
        StaffAttachment clean = cleanFile("clean.pdf");

        ApiException scannerDenied = assertThrows(ApiException.class, () -> service.send(scanner,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true)));
        assertEquals(403, scannerDenied.status());
        assertEquals("FORBIDDEN", scannerDenied.code());

        ApiException auditorDenied = assertThrows(ApiException.class, () -> service.send(auditor,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true)));
        assertEquals(403, auditorDenied.status());
        assertEquals("FORBIDDEN", auditorDenied.code());

        ApiException foreignDraft = assertThrows(ApiException.class, () -> service.send(sender,
                new SendRequest("other-draft", MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true)));
        assertEquals(403, foreignDraft.status());
        assertEquals("WRONG_DRAFT", foreignDraft.code());

        ApiException foreignTenant = assertThrows(ApiException.class,
                () -> service.send(StaffActor.staff(OTHER_ORG, UPLOADER, "nurse"),
                        new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true)));
        assertEquals(404, foreignTenant.status());
        assertEquals("NOT_FOUND", foreignTenant.code());

        assertFalse(store.attachment(ORG, clean.id()).orElseThrow().sent());
        assertTrue(sentAttachmentIds().isEmpty());
    }

    /** Mail publication requires the sender to confirm the exact resolved recipient set. */
    @Test
    void recipientConfirmationMustMatchResolvedRecipientsBeforeSend() {
        RecipientDirectory directory = mock(RecipientDirectory.class);
        when(directory.holders(ORG, FACILITY, "nurse")).thenReturn(List.of("nurse-b", "nurse-c"));
        MailStore mailStore = mock(MailStore.class);
        DraftView draft = new DraftView(ORG, "draft-1", FACILITY, UPLOADER, 1, "Handover", "body",
                List.of(Recipient.role("nurse")), List.of(), List.of(), null, NOW);
        when(mailStore.draft(ORG, draft.id(), UPLOADER)).thenReturn(Optional.of(draft));
        when(mailStore.replay(ORG, UPLOADER, "key-1")).thenReturn(Optional.empty());
        MailService mail = new MailService(Clock.fixed(NOW, ZoneOffset.UTC), directory, mailStore);

        ApiException missing = assertThrows(ApiException.class,
                () -> mail.send(new SendCommand(ORG, UPLOADER, draft.id(), null, "key-1")));
        assertEquals(428, missing.status());
        assertEquals("CONFIRMATION_REQUIRED", missing.code());

        ApiException mismatched = assertThrows(ApiException.class,
                () -> mail.send(new SendCommand(ORG, UPLOADER, draft.id(), List.of("nurse-b"), "key-1")));
        assertEquals(409, mismatched.status());
        assertEquals("RECIPIENT_CONFIRMATION_MISMATCH", mismatched.code());
        verify(mailStore, never()).publish(anyString(), anyString(), anyString(), anyString(), any(), any());

        AtomicReference<SentMail> published = new AtomicReference<>();
        when(mailStore.publish(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    published.set(invocation.getArgument(4));
                    return "published-1";
                });
        when(mailStore.message(ORG, "published-1")).thenAnswer(invocation -> Optional.of(published.get()));

        var sent = mail.send(new SendCommand(ORG, UPLOADER, draft.id(),
                List.of("nurse-b", "nurse-c"), "key-1"));

        assertEquals(List.of("nurse-b", "nurse-c"), sent.message().resolvedRecipients());
        verify(mailStore).publish(eq(ORG), eq(UPLOADER), eq("key-1"), eq(draft.id()), any(),
                eq(List.of("nurse-b", "nurse-c")));
    }
}
