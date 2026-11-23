package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.RemoveRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ScanVerdict;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.View;
import com.aurevia.smartclinic.messaging.attachments.AttachmentService;
import com.aurevia.smartclinic.messaging.attachments.InMemoryAttachmentStore;

/**
 * MSG-03 clause 6: a failed quarantine verdict is shown to the uploading sender,
 * who can remove the file and upload a replacement.
 *
 * <p>Synthetic, database-free tests entering through the domain service that the
 * protected attachment controller injects. The service never exposes payload
 * bytes or URLs, and every subject, organization and identifier here is synthetic.</p>
 */
class ShowScanFailuresSenderRemovalReplacementOptionsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "unit-1";
    private static final String PATIENT = "pat-001";
    private static final String CONNECTION = "conv-1";
    private static final String MESSAGE = "msg-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final Actor uploaderA = Actor.patient(ORG_A, "patient-a", PATIENT);
    private final Actor staffA = Actor.staff(ORG_A, "staff-a", UNIT, "reception");
    private final Actor scannerA = Actor.staff(ORG_A, "scanner-a", UNIT, "security_scanner");
    private final Actor uploaderB = Actor.patient(ORG_B, "patient-b", PATIENT);

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        InMemoryAttachmentStore store = new InMemoryAttachmentStore();
        service = new AttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT, true));
        store.putGrant(grant(ORG_B, CONNECTION, UNIT, PATIENT, true));
    }

    // --- rule met: failed verdict, bounded detail and sender options ---------

    @Test
    void failedVerdictIsRecordedWithBoundedDetailAndFailedState() {
        Attachment quarantined = service.upload(uploaderA, pdf());
        Attachment failed = service.scan(scannerA, quarantined.id(),
                new ScanRequest("failed", "x".repeat(200)), quarantined.version());

        assertEquals(AttachmentState.FAILED, failed.state());
        assertEquals(ScanVerdict.FAILED, failed.verdict());
        assertEquals(2, failed.version());
        assertEquals(NOW, failed.createdAt());
        AttachmentEvent verdictFact = service.events(uploaderA, failed.id()).stream()
                .filter(fact -> fact.eventType() == EventType.SCAN_FAILED)
                .findFirst().orElseThrow();
        assertEquals("x".repeat(200), verdictFact.detail());
        assertEquals("scanner-a", verdictFact.actorSubject());
    }

    @Test
    void uploaderSeesFailedVerdictAndBoundedDetailThroughViewAndEvents() {
        Attachment failed = failedUpload("signature mismatch");
        View view = service.view(uploaderA, failed.id());

        assertEquals(AttachmentState.FAILED, view.attachment().state());
        assertEquals(ScanVerdict.FAILED, view.attachment().verdict());
        assertTrue(view.events().stream().anyMatch(fact ->
                fact.eventType() == EventType.SCAN_FAILED && "signature mismatch".equals(fact.detail())));
        assertEquals(view.events(), service.events(uploaderA, failed.id()));
    }

    @Test
    void senderCanReplaceFailedFileByRemovingItAndUploadingAFreshCopy() {
        Attachment failed = failedUpload("signature mismatch");
        Attachment removed = service.remove(uploaderA, failed.id(),
                new RemoveRequest("replace"), failed.version());

        assertEquals(AttachmentState.REMOVED, removed.state());
        assertEquals(ScanVerdict.FAILED, removed.verdict());
        assertEquals(3, removed.version());
        assertTrue(service.events(uploaderA, failed.id()).stream().anyMatch(fact ->
                fact.eventType() == EventType.REMOVED && "replace".equals(fact.detail())));
        assertEquals(AttachmentState.REMOVED, service.view(uploaderA, failed.id()).attachment().state());

        Attachment replacement = service.upload(uploaderA, pdf());
        assertNotEquals(failed.id(), replacement.id());
        assertEquals(AttachmentState.QUARANTINED, replacement.state());
    }

    // --- negatives: the rule refuses what it must refuse ---------------------

    @Test
    void nonUploaderCannotRemoveFailedFile() {
        Attachment failed = failedUpload("signature mismatch");
        assertDenied(403, "NOT_UPLOADER",
                () -> service.remove(staffA, failed.id(), new RemoveRequest("replace"), failed.version()));
    }

    @Test
    void otherTenantCannotViewFailedFileOrItsFacts() {
        Attachment failed = failedUpload("signature mismatch");
        assertDenied(404, "NOT_FOUND", () -> service.view(uploaderB, failed.id()));
        assertDenied(404, "NOT_FOUND", () -> service.events(uploaderB, failed.id()));
    }

    @Test
    void staleVersionCannotRemoveFailedFile() {
        Attachment failed = failedUpload("signature mismatch");
        assertDenied(409, "STALE_VERSION",
                () -> service.remove(uploaderA, failed.id(), new RemoveRequest("replace"), 1));
    }

    @Test
    void publishedFileCannotBeRemovedAsFailed() {
        Attachment quarantined = service.upload(uploaderA, pdf());
        Attachment clean = service.scan(scannerA, quarantined.id(), new ScanRequest("clean", null), 1);
        service.publish(uploaderA, new PublishRequest(List.of(clean.id()), MESSAGE, UNIT));
        assertDenied(409, "INVALID_REMOVE_STATE",
                () -> service.remove(uploaderA, clean.id(), new RemoveRequest("replace"), 3));
    }

    @Test
    void nonScannerCannotRecordFailedVerdict() {
        Attachment quarantined = service.upload(uploaderA, pdf());
        assertDenied(403, "FORBIDDEN",
                () -> service.scan(uploaderA, quarantined.id(), new ScanRequest("failed", "signature"), 1));
    }

    @Test
    void oversizedScanDetailIsRefused() {
        Attachment quarantined = service.upload(uploaderA, pdf());
        assertDenied(422, "INVALID_DETAIL", () -> service.scan(scannerA, quarantined.id(),
                new ScanRequest("failed", "x".repeat(201)), 1));
    }

    @Test
    void oversizedRemovalReasonIsRefused() {
        Attachment failed = failedUpload("signature mismatch");
        assertDenied(422, "INVALID_DETAIL", () -> service.remove(uploaderA, failed.id(),
                new RemoveRequest("x".repeat(201)), failed.version()));
    }

    // --- helpers -------------------------------------------------------------

    /** Upload a synthetic PDF and record a failed scan verdict with bounded detail. */
    private Attachment failedUpload(String detail) {
        Attachment quarantined = service.upload(uploaderA, pdf());
        return service.scan(scannerA, quarantined.id(), new ScanRequest("failed", detail), quarantined.version());
    }

    /** Synthetic PDF upload metadata; no real payload bytes are used. */
    private static UploadRequest pdf() {
        return new UploadRequest(CONNECTION, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }

    /** Synthetic verified connection grant shared by both tenants. */
    private static ConnectionGrant grant(String org, String connection, String unit, String patient, boolean active) {
        return new ConnectionGrant(org, connection, unit, patient, active);
    }

    /** Assert the exact structured error a refused protected action returns. */
    private static void assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }
}
