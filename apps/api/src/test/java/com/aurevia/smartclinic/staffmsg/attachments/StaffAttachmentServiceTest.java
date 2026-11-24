package com.aurevia.smartclinic.staffmsg.attachments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.DownloadedFile;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.SendRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffActor;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.UploadRequest;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ScanState;

/**
 * MSG-03 / CLN-07 acceptance for staff-message attachments: allowed types and
 * sizes, quarantine enforcement, explicit send, per-recipient audited download
 * and byte-for-byte preservation. Synthetic data only.
 */
class StaffAttachmentServiceTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String UPLOADER = "nurse-a";
    private static final String RECIPIENT = "clinician-b";
    private static final String STRANGER = "reception-c";
    private static final String DRAFT = "conversation-1";
    private static final String MESSAGE = "message-1";
    private static final byte[] PDF = "%PDF-1.4 synthetic staff attachment".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3 };

    private InMemoryStaffAttachmentStore store;
    private StaffAttachmentService service;
    private StaffActor uploader;
    private StaffActor scanner;
    private StaffActor recipient;
    private StaffActor stranger;

    /** Build a fresh synthetic service with a fixed clock and limits. */
    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        store = new InMemoryStaffAttachmentStore();
        service = new StaffAttachmentService(store, clock, new StaffAttachmentLimits());
        uploader = StaffActor.staff(ORG, UPLOADER, "nurse");
        scanner = StaffActor.staff(ORG, "scanner-a", "security_scanner");
        recipient = StaffActor.staff(ORG, RECIPIENT, "clinician");
        stranger = StaffActor.staff(ORG, STRANGER, "reception");
    }

    private static UploadRequest upload(String contentType, byte[] content, String fileName) {
        return new UploadRequest(DRAFT, contentType, fileName, content, checksum(content));
    }

    private static UploadRequest pdf() {
        return upload("application/pdf", PDF, "note.pdf");
    }

    private static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private StaffAttachment cleanAttachment() {
        StaffAttachment created = service.upload(uploader, pdf());
        service.scan(scanner, created.id(),
                new StaffAttachmentContract.ScanRequest("clean", "ok"));
        return store.attachment(ORG, created.id()).orElseThrow();
    }

    @Test
    void pdfJpegAndPngAreAcceptedButOtherTypesAreRefused() {
        StaffAttachment pdfAttachment = service.upload(uploader, pdf());
        assertEquals(ScanState.PENDING, pdfAttachment.scanState());

        StaffAttachment pngAttachment = service.upload(uploader, upload("image/png", PNG, "scan.png"));
        assertEquals(ScanState.PENDING, pngAttachment.scanState());

        ApiException rejected = assertThrows(ApiException.class,
                () -> service.upload(uploader, upload("application/zip", PDF, "archive.zip")));
        assertEquals(422, rejected.status());
        assertEquals("UNSUPPORTED_CONTENT_TYPE", rejected.code());

        ApiException mismatch = assertThrows(ApiException.class,
                () -> service.upload(uploader, upload("application/pdf", PNG, "fake.pdf")));
        assertEquals(422, mismatch.status());
        assertEquals("CONTENT_MISMATCH", mismatch.code());
    }

    @Test
    void sizeLimitIsEnforcedBeforeQuarantine() {
        byte[] tooLarge = new byte[(int) (new StaffAttachmentLimits().maxSizeBytes() + 1)];
        java.util.Arrays.fill(tooLarge, (byte) 0x25);
        ApiException rejected = assertThrows(ApiException.class,
                () -> service.upload(uploader, upload("application/pdf", tooLarge, "big.pdf")));
        assertEquals(422, rejected.status());
        assertEquals("INVALID_SIZE_BYTES", rejected.code());
        assertEquals(0, store.attachmentsForDraft(ORG, DRAFT).size());
    }

    @Test
    void quarantinedFileCannotBeSentAndSenderSeesWhy() {
        StaffAttachment created = service.upload(uploader, pdf());
        service.scan(scanner, created.id(),
                new StaffAttachmentContract.ScanRequest("failed", "signature scan found a threat"));
        StaffAttachment quarantined = store.attachment(ORG, created.id()).orElseThrow();
        assertEquals(ScanState.QUARANTINED, quarantined.scanState());

        List<StaffAttachment> pending = service.pending(uploader, DRAFT);
        assertEquals(1, pending.size());
        assertEquals(ScanState.QUARANTINED, pending.get(0).scanState());

        ApiException blocked = assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(created.id()), Set.of(RECIPIENT), true)));
        assertEquals(409, blocked.status());
        assertEquals("QUARANTINED_FILE", blocked.code());
        assertTrue(blocked.getMessage().contains("signature scan found a threat"));
        assertFalse(store.attachment(ORG, created.id()).orElseThrow().sent());
    }

    @Test
    void explicitSendConfirmationIsRequired() {
        cleanAttachment();
        ApiException unconfirmed = assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(), Set.of(RECIPIENT), false)));
        assertEquals(422, unconfirmed.status());
        assertEquals("CONFIRM_REQUIRED", unconfirmed.code());
    }

    @Test
    void onlyRecipientsCanDownloadAndDownloadIsAuditedWithUnchangedBytes() {
        StaffAttachment clean = cleanAttachment();
        store.putRecipients(ORG, MESSAGE, Set.of(RECIPIENT));
        service.send(uploader, new SendRequest(DRAFT, MESSAGE, List.of(clean.id()),
                Set.of(RECIPIENT), true));
        StaffAttachment sent = store.attachment(ORG, clean.id()).orElseThrow();
        assertTrue(sent.sent());

        ApiException denied = assertThrows(ApiException.class, () -> service.download(stranger, sent.id()));
        assertEquals(403, denied.status());
        assertEquals("NOT_RECIPIENT", denied.code());
        assertFalse(service.auditTrail(ORG, sent.id()).stream()
                .anyMatch(audit -> audit.type() == AuditType.DOWNLOADED));

        DownloadedFile downloaded = service.download(recipient, sent.id());
        assertArrayEquals(PDF, downloaded.bytes());
        assertEquals(checksum(PDF), downloaded.checksum());
        assertEquals("application/pdf", downloaded.contentType());

        List<AttachmentAudit> downloads = service.auditTrail(ORG, sent.id()).stream()
                .filter(audit -> audit.type() == AuditType.DOWNLOADED)
                .toList();
        assertEquals(1, downloads.size());
        assertEquals(RECIPIENT, downloads.get(0).actorSubject());
    }

    @Test
    void unpublishedOrMissingFilesCannotBeDownloaded() {
        StaffAttachment pending = service.upload(uploader, pdf());
        ApiException notSent = assertThrows(ApiException.class, () -> service.download(recipient, pending.id()));
        assertEquals(409, notSent.status());
        assertEquals("NOT_SENT", notSent.code());

        ApiException missing = assertThrows(ApiException.class, () -> service.download(recipient, "unknown"));
        assertEquals(404, missing.status());
    }

    @Test
    void countLimitIsEnforced() {
        service = new StaffAttachmentService(store,
                Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC),
                new StaffAttachmentLimits(StaffAttachmentLimits.DEFAULT_MAX_SIZE_BYTES, 1));
        service.upload(uploader, pdf());
        ApiException rejected = assertThrows(ApiException.class, () -> service.upload(uploader, pdf()));
        assertEquals(422, rejected.status());
        assertEquals("FILE_LIMIT_REACHED", rejected.code());
    }

    @Test
    void emptyPayloadAndExtensionAndChecksumAreRefused() {
        ApiException empty = assertThrows(ApiException.class, () -> service.upload(uploader,
                new UploadRequest(DRAFT, "application/pdf", "note.pdf", null, "x")));
        assertEquals("INVALID_CONTENT", empty.code());
        assertEquals("INVALID_CONTENT", assertThrows(ApiException.class, () -> service.upload(uploader,
                new UploadRequest(DRAFT, "application/pdf", "note.pdf", new byte[0], "x"))).code());

        ApiException extension = assertThrows(ApiException.class,
                () -> service.upload(uploader, upload("application/pdf", PDF, "note.png")));
        assertEquals("EXTENSION_MISMATCH", extension.code());

        ApiException checksum = assertThrows(ApiException.class, () -> service.upload(uploader,
                new UploadRequest(DRAFT, "application/pdf", "note.pdf", PDF, "wrong")));
        assertEquals("CHECKSUM_MISMATCH", checksum.code());
    }

    @Test
    void scanGuardsInvalidStateAndVerdict() {
        StaffAttachment created = service.upload(uploader, pdf());
        service.scan(scanner, created.id(), new StaffAttachmentContract.ScanRequest("clean", "ok"));
        ApiException state = assertThrows(ApiException.class,
                () -> service.scan(scanner, created.id(), new StaffAttachmentContract.ScanRequest("clean", "ok")));
        assertEquals("INVALID_SCAN_STATE", state.code());

        StaffAttachment other = service.upload(uploader, pdf());
        ApiException verdict = assertThrows(ApiException.class,
                () -> service.scan(scanner, other.id(), new StaffAttachmentContract.ScanRequest("unknown", "x")));
        assertEquals(422, verdict.status());
    }

    @Test
    void sendGuardsEmptyDuplicateAndUnconfirmedInput() {
        StaffAttachment clean = cleanAttachment();
        assertEquals("EMPTY_ATTACHMENTS", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, null, Set.of(RECIPIENT), true))).code());
        assertEquals("EMPTY_ATTACHMENTS", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(), Set.of(RECIPIENT), true))).code());
        assertEquals("DUPLICATE_ATTACHMENTS", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id(), clean.id()), Set.of(RECIPIENT), true))).code());
        assertEquals("EMPTY_RECIPIENTS", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(), true))).code());
        assertEquals("EMPTY_RECIPIENTS", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), null, true))).code());
    }

    @Test
    void sendGrantsTheFrozenAudienceOnceWithoutAnyTestSideSeed() {
        StaffAttachment clean = cleanAttachment();
        service.send(uploader, new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true));
        assertTrue(store.isRecipient(ORG, MESSAGE, RECIPIENT));
        assertFalse(store.isRecipient(ORG, MESSAGE, "nurse-c"));
        assertEquals(1L, service.auditTrail(ORG, clean.id()).stream()
                .filter(fact -> fact.type() == AuditType.SENT && "success".equals(fact.outcome()))
                .count());
    }

    @Test
    void pendingListExcludesAlreadySentFiles() {
        StaffAttachment clean = cleanAttachment();
        store.putRecipients(ORG, MESSAGE, Set.of(RECIPIENT));
        service.send(uploader, new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true));
        assertTrue(service.pending(uploader, DRAFT).isEmpty());
    }

    @Test
    void sendGuardsStateAndOwnership() {
        StaffAttachment pending = service.upload(uploader, pdf());
        assertEquals("NOT_CLEAN", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(pending.id()), Set.of(RECIPIENT), true))).code());

        StaffAttachment clean = cleanAttachment();
        assertEquals("NOT_UPLOADER", assertThrows(ApiException.class, () -> service.send(recipient,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true))).code());
        assertEquals("WRONG_DRAFT", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest("other-draft", MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true))).code());

        service.send(uploader, new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true));
        assertEquals("ALREADY_SENT", assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true))).code());
    }

    @Test
    void quarantinedFallbackReasonIsShownWhenNoScanFactExists() {
        StaffAttachment quarantined = new StaffAttachment(ORG, "quarantined-1", UPLOADER, DRAFT,
                "document-q", "bad.pdf", "application/pdf", PDF.length, checksum(PDF), "object-q", 1,
                ScanState.QUARANTINED, null, false, Instant.parse("2026-11-20T10:00:00Z"));
        store.putAttachment(quarantined);
        ApiException blocked = assertThrows(ApiException.class, () -> service.send(uploader,
                new SendRequest(DRAFT, MESSAGE, List.of(quarantined.id()), Set.of(RECIPIENT), true)));
        assertTrue(blocked.getMessage().contains("scan finding on file quarantined-1"));
    }

    @Test
    void tamperedObjectIsRefusedOnDownload() {
        StaffAttachment clean = cleanAttachment();
        store.putRecipients(ORG, MESSAGE, Set.of(RECIPIENT));
        service.send(uploader, new SendRequest(DRAFT, MESSAGE, List.of(clean.id()), Set.of(RECIPIENT), true));
        store.putBytes(ORG, clean.objectKey(), "tampered".getBytes(StandardCharsets.UTF_8));
        ApiException missing = assertThrows(ApiException.class, () -> service.download(recipient, clean.id()));
        assertEquals("OBJECT_MISSING", missing.code());
    }

    @Test
    void viewRequiresReadRoleAndNonNullActor() {
        StaffAttachment clean = cleanAttachment();
        assertEquals(clean.id(), service.view(uploader, clean.id()).attachment().id());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service.view(scanner, clean.id())).code());
        assertEquals("FORBIDDEN", assertThrows(ApiException.class,
                () -> service.pending(null, DRAFT)).code());
    }
}
