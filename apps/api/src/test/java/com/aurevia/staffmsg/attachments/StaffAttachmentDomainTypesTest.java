package com.aurevia.staffmsg.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.DownloadedFile;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.SendRequest;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.StaffActor;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.UploadRequest;
import com.aurevia.staffmsg.attachments.StaffAttachmentContract.View;
import com.aurevia.staffmsg.model.StaffMessagingContract.ScanState;

/**
 * Focused unit tests for validation, action policy and value semantics of the
 * staff-attachment slice (MSG-03, CLN-07).
 */
class StaffAttachmentDomainTypesTest {

    /** Every audit wire key is stable and distinct. */
    @Test
    void auditWireKeysAreStable() {
        Set<String> wires = java.util.Arrays.stream(AuditType.values())
                .map(AuditType::wire).collect(java.util.stream.Collectors.toSet());
        assertEquals(AuditType.values().length, wires.size());
        assertEquals("uploaded", AuditType.UPLOADED.wire());
        assertEquals("downloaded", AuditType.DOWNLOADED.wire());
    }

    /** Media type resolution accepts only PDF, JPEG and PNG. */
    @Test
    void contentTypeResolutionIsBounded() {
        assertEquals(MediaType.PDF, StaffAttachmentInputs.contentType("application/pdf"));
        assertEquals(MediaType.JPEG, StaffAttachmentInputs.contentType("IMAGE/JPEG"));
        assertEquals(MediaType.PNG, StaffAttachmentInputs.contentType("image/png"));
        assertEquals("UNSUPPORTED_CONTENT_TYPE", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.contentType("text/plain")).code());
        assertEquals("UNSUPPORTED_CONTENT_TYPE", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.contentType(null)).code());
    }

    /** Size, checksum and verdict guards take each failure side. */
    @Test
    void inputGuardsCoverFailureSides() {
        assertEquals(10L, StaffAttachmentInputs.size(10L, 10L));
        assertEquals("INVALID_SIZE_BYTES", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.size(0L, 10L)).code());
        assertEquals("INVALID_SIZE_BYTES", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.size(11L, 10L)).code());

        String checksum = StaffAttachmentInputs.checksum("%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> StaffAttachmentInputs.checksum(
                "not-a-digest", "%PDF".getBytes(StandardCharsets.UTF_8)));
        StaffAttachmentInputs.requireMatchingChecksum(checksum.toUpperCase(), checksum);
        assertEquals("CHECKSUM_MISMATCH", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.requireMatchingChecksum(null, checksum)).code());
        assertEquals("CHECKSUM_MISMATCH", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.requireMatchingChecksum("00", checksum)).code());
        assertTrue(StaffAttachmentInputs.cleanVerdict("clean"));
        assertFalse(StaffAttachmentInputs.cleanVerdict("failed"));
        assertEquals("INVALID_VERDICT", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.cleanVerdict("unknown")).code());
    }

    /** Shared validators normalize keys, names, details and object keys. */
    @Test
    void sharedValidatorsAreApplied() {
        assertEquals("draft-1", StaffAttachmentInputs.key("  draft-1 ", "draftId"));
        assertEquals("note.pdf", StaffAttachmentInputs.fileName("note.pdf"));
        assertEquals(null, StaffAttachmentInputs.detail("  ", 20));
        assertEquals("ok", StaffAttachmentInputs.detail(" ok ", 20));
        assertTrue(StaffAttachmentInputs.objectKey("org", "id", 1).startsWith("quarantine/"));
        assertNotNull(StaffAttachmentInputs.newId());
        assertEquals("255044462d312e34",
                StaffAttachmentInputs.magicHex("%PDF-1.4".getBytes(StandardCharsets.UTF_8)));
        assertEquals("INVALID_FILE_NAME", assertThrows(ApiException.class,
                () -> StaffAttachmentInputs.fileName("../note.pdf")).code());
    }

    /** Configured limits reject non-positive bounds. */
    @Test
    void limitsRejectNonPositiveBounds() {
        assertEquals(new StaffAttachmentLimits(), new StaffAttachmentLimits(
                StaffAttachmentLimits.DEFAULT_MAX_SIZE_BYTES, StaffAttachmentLimits.DEFAULT_MAX_FILES_PER_DRAFT));
        assertThrows(IllegalArgumentException.class, () -> new StaffAttachmentLimits(0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new StaffAttachmentLimits(1L, 0));
    }

    /** The action matrix grants only the intended roles. */
    @Test
    void actionMatrixIsBounded() {
        assertTrue(StaffAttachmentActions.allows(StaffAttachmentActions.SCAN, "security_scanner"));
        assertFalse(StaffAttachmentActions.allows(StaffAttachmentActions.SCAN, "clinician"));
        assertTrue(StaffAttachmentActions.allows(StaffAttachmentActions.UPLOAD, "nurse"));
        assertTrue(StaffAttachmentActions.allows(StaffAttachmentActions.SEND, "clinician"));
        assertTrue(StaffAttachmentActions.allows(StaffAttachmentActions.DOWNLOAD, "reception"));
        assertTrue(StaffAttachmentActions.allows(StaffAttachmentActions.READ, "privacy_auditor"));
        assertTrue(StaffAttachmentActions.allows(StaffAttachmentActions.READ, "tenant_admin"));
        assertFalse(StaffAttachmentActions.allows(StaffAttachmentActions.READ, "security_scanner"));
        assertFalse(StaffAttachmentActions.allows("UNKNOWN", "clinician"));
    }

    /** Value types defend their invariants and copies. */
    @Test
    void valueTypesDefendInvariants() {
        StaffAttachment sent = attachment(true, ScanState.CLEAN);
        assertTrue(sent.downloadable());
        assertFalse(attachment(false, ScanState.CLEAN).downloadable());
        assertFalse(attachment(true, ScanState.QUARANTINED).downloadable());

        byte[] content = "%PDF".getBytes(StandardCharsets.UTF_8);
        UploadRequest request = new UploadRequest("draft", "application/pdf", "a.pdf", content, "sum");
        content[0] = 'X';
        assertEquals("%PDF", new String(request.content(), StandardCharsets.UTF_8));

        SendRequest send = new SendRequest("draft", "message", List.of("a"), null, true);
        assertEquals(null, send.recipients());
        assertEquals(List.of("a"), send.attachmentIds());

        byte[] original = "%PDF".getBytes(StandardCharsets.UTF_8);
        DownloadedFile file = new DownloadedFile(original, "sum", "application/pdf", original.length);
        file.bytes()[0] = 'Y';
        assertEquals("%PDF", new String(file.bytes(), StandardCharsets.UTF_8));

        AttachmentAudit audit = new AttachmentAudit("id", "org", "attachment", AuditType.UPLOADED,
                "subject", "success", "detail", Instant.parse("2026-11-20T10:00:00Z"));
        View view = new View(sent, List.of(audit));
        assertEquals("id", view.audit().get(0).id());
        assertEquals("org", audit.organizationId());
        assertEquals("attachment", audit.attachmentId());
        assertEquals("success", audit.outcome());
        assertEquals("detail", audit.detail());
        assertEquals(Instant.parse("2026-11-20T10:00:00Z"), audit.occurredAt());
        assertEquals("subject", StaffActor.staff("org", "subject", "nurse").subject());
    }

    private static StaffAttachment attachment(boolean sent, ScanState scanState) {
        return new StaffAttachment("org", "id", "uploader", "draft", "document", "a.pdf",
                "application/pdf", 4L, "sum", "key", 1, scanState, "message", sent,
                Instant.parse("2026-11-20T10:00:00Z"));
    }
}
