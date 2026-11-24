package com.aurevia.acceptance.patient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.RemoveRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentService;
import com.aurevia.messaging.attachments.InMemoryAttachmentStore;

/**
 * Gate G3 acceptance for patient document uploads.
 *
 * <p>Requirements proved: MSG-03 (an upload is bound to its uploader,
 * conversation and unit, reaches only that unit's inbox after a clean scan and
 * explicit send, and a failed scan is shown to the sender with a removal or
 * replacement option) and ARC-06 (quarantine and mediated access). All
 * fixtures are synthetic.</p>
 */
class PatientUploadAcceptanceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "unit-1";
    private static final String OTHER_UNIT = "unit-2";
    private static final String PATIENT = "patient-1";
    private static final String CONNECTION = "connection-1";
    private static final String MESSAGE = "message-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private InMemoryAttachmentStore store;
    private AttachmentService service;

    private final Actor patient = Actor.patient(ORG_A, "syn-patient", PATIENT);
    private final Actor unitStaff = Actor.staff(ORG_A, "syn-staff", UNIT, "reception");
    private final Actor otherUnitStaff = Actor.staff(ORG_A, "syn-other-staff", OTHER_UNIT, "reception");
    private final Actor scanner = Actor.staff(ORG_A, "syn-scanner", UNIT, "security_scanner");

    @BeforeEach
    void setUp() {
        store = new InMemoryAttachmentStore();
        service = new AttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        store.putGrant(new ConnectionGrant(ORG_A, CONNECTION, UNIT, PATIENT, true));
        store.putGrant(new ConnectionGrant(ORG_B, CONNECTION, UNIT, PATIENT, true));
    }

    @Test
    void patientUploadReachesOnlyItsConnectedUnit() {
        // MSG-03: after a clean verdict and an explicit send the file appears
        // in its own unit's inbox, not in another unit's and not to its staff.
        Attachment uploaded = service.upload(patient, pdf());
        Attachment clean = service.scan(scanner, uploaded.id(), new ScanRequest("clean", null), 1);
        service.publish(patient, new PublishRequest(List.of(clean.id()), MESSAGE, UNIT));

        List<Attachment> inbox = service.inbox(unitStaff, UNIT);
        assertEquals(1, inbox.size());
        assertEquals(uploaded.id(), inbox.get(0).id());
        assertEquals(UNIT, inbox.get(0).unitId());
        assertTrue(service.inbox(otherUnitStaff, OTHER_UNIT).isEmpty());
        assertDenied(404, "NOT_FOUND", () -> service.view(otherUnitStaff, uploaded.id()));
        assertDenied(403, "WRONG_UNIT", () -> service.inbox(otherUnitStaff, UNIT));
    }

    @Test
    void failedUploadIsVisibleToSenderAndNeverPublished() {
        // MSG-03: a failed scan is a separate fact shown to the sender; the file
        // stays unpublished, cannot be downloaded and can be removed/replaced.
        Attachment uploaded = service.upload(patient, pdf());
        Attachment failed = service.scan(scanner, uploaded.id(), new ScanRequest("failed", "signature"), 1);

        assertEquals(AttachmentState.FAILED, failed.state());
        assertTrue(service.events(patient, uploaded.id()).stream()
                .anyMatch(event -> event.eventType() == EventType.SCAN_FAILED));
        assertDenied(409, "NOT_CLEAN", () -> service.publish(patient,
                new PublishRequest(List.of(failed.id()), MESSAGE, UNIT)));
        assertDenied(409, "NOT_PUBLISHED", () -> service.download(patient, failed.id(), 2));

        Attachment replaced = service.remove(patient, failed.id(), new RemoveRequest("replace"), 2);
        assertEquals(AttachmentState.REMOVED, replaced.state());
        assertTrue(store.events(ORG_A).stream()
                .anyMatch(event -> event.attachmentId().equals(uploaded.id())));
    }

    @Test
    void uploadIsBoundToTheUploaderAndTheServerGrantedUnit() {
        // ARC-07: the uploader, patient and unit come from the verified grant,
        // never from client input; a re-linked patient is denied.
        Attachment uploaded = service.upload(patient, pdf());
        assertEquals(PATIENT, uploaded.patientId());
        assertEquals(UNIT, uploaded.unitId());
        assertEquals("syn-patient", uploaded.uploaderSubject());
        assertEquals(AttachmentState.QUARANTINED, uploaded.state());
        assertDenied(403, "WRONG_UNIT", () -> service.upload(
                Actor.patient(ORG_A, "syn-patient", "patient-2"), pdf()));
    }

    private UploadRequest pdf() {
        return new UploadRequest(CONNECTION, "notes.pdf", "application/pdf", "25504446", 128, CHECKSUM);
    }

    private static void assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }
}
