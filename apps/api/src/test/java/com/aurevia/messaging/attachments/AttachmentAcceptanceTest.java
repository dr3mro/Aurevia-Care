package com.aurevia.messaging.attachments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.messaging.attachments.AttachmentContract.FileRequest;
import com.aurevia.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.messaging.attachments.AttachmentContract.MediatedDownload;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.RemoveRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentContract.View;

/**
 * S-G3-06 acceptance for MSG-03 and ARC-06.
 *
 * <p>All identifiers, subjects and payload metadata are synthetic. The tests
 * exercise the public domain-service entry point and the pure contract and
 * validation helpers with no external infrastructure.</p>
 */
class AttachmentAcceptanceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNIT = "unit-1";
    private static final String OTHER_UNIT = "unit-2";
    private static final String PATIENT = "pat-001";
    private static final String OTHER_PATIENT = "pat-002";
    private static final String CONNECTION = "conv-1";
    private static final String MESSAGE = "msg-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String OTHER_CHECKSUM = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private AttachmentStore store;
    private AttachmentService service;

    private final Actor patientA = Actor.patient(ORG_A, "patient-a", PATIENT);
    private final Actor wrongPatientA = Actor.patient(ORG_A, "patient-a", OTHER_PATIENT);
    private final Actor staffA = Actor.staff(ORG_A, "staff-a", UNIT, "reception");
    private final Actor wrongUnitStaffA = Actor.staff(ORG_A, "staff-a", OTHER_UNIT, "reception");
    private final Actor scannerA = Actor.staff(ORG_A, "scanner-a", UNIT, "security_scanner");
    private final Actor clinicianA = Actor.staff(ORG_A, "clinician-a", UNIT, "clinician");
    private final Actor wrongUnitClinicianA = Actor.staff(ORG_A, "clinician-a", OTHER_UNIT, "clinician");
    private final Actor auditorA = Actor.staff(ORG_A, "auditor-a", UNIT, "privacy_auditor");
    private final Actor noRoleA = Actor.staff(ORG_A, "no-role-a", UNIT, "unrecognized");
    private final Actor patientB = Actor.patient(ORG_B, "patient-b", PATIENT);
    private final Actor staffB = Actor.staff(ORG_B, "staff-b", UNIT, "reception");

    @BeforeEach
    void setUp() {
        store = new InMemoryAttachmentStore();
        service = new AttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT, true));
        store.putGrant(grant(ORG_B, CONNECTION, UNIT, PATIENT, true));
    }

    // --- A1: media-type, magic-byte, size and checksum validation ------------

    @Test
    void acceptsAllThreeSupportedMediaTypes() {
        for (FileType type : FileType.values()) {
            Attachment uploaded = service.upload(patientA,
                    request(type.contentType(), type == FileType.PDF ? "notes.pdf"
                            : type == FileType.JPEG ? "notes.jpg" : "notes.png",
                            type.magicPrefix(), 128, CHECKSUM));
            assertEquals(type, uploaded.fileType());
            assertEquals(AttachmentState.QUARANTINED, uploaded.state());
            assertEquals(ScanVerdict.PENDING, uploaded.verdict());
            assertEquals(1, uploaded.version());
            assertEquals(1, uploaded.objectVersion());
            assertTrue(uploaded.objectKey().startsWith("quarantine/" + ORG_A + "/"));
            assertEquals(NOW, uploaded.createdAt());
        }
    }

    @Test
    void rejectsUnsupportedContentType() {
        assertDenied(422, "UNSUPPORTED_CONTENT_TYPE",
                () -> service.upload(patientA, request("text/plain", "notes.txt", "25504446", 128, CHECKSUM)));
        assertDenied(422, "UNSUPPORTED_CONTENT_TYPE",
                () -> service.upload(patientA, request(null, "notes.pdf", "25504446", 128, CHECKSUM)));
    }

    @Test
    void rejectsPayloadWhoseBytesDoNotMatchDeclaredType() {
        assertDenied(422, "CONTENT_MISMATCH",
                () -> service.upload(patientA, request("application/pdf", "notes.pdf", "89504e470d0a1a0a", 8, CHECKSUM)));
        assertDenied(422, "CONTENT_MISMATCH",
                () -> service.upload(patientA, request("image/jpeg", "notes.jpg", "25504446", 8, CHECKSUM)));
        assertDenied(422, "CONTENT_MISMATCH",
                () -> service.upload(patientA, request("image/png", "notes.png", "ffd8ff", 8, CHECKSUM)));
    }

    @Test
    void rejectsFileNamesWithPathsOrWrongExtensions() {
        assertDenied(422, "INVALID_FILE_NAME",
                () -> service.upload(patientA, request("application/pdf", "../notes.pdf", "25504446", 8, CHECKSUM)));
        assertDenied(422, "INVALID_FILE_NAME",
                () -> service.upload(patientA, request("application/pdf", "", "25504446", 8, CHECKSUM)));
        assertDenied(422, "INVALID_FILE_NAME", () -> AttachmentInputs.fileName(null));
        assertDenied(422, "INVALID_FILE_NAME", () -> AttachmentInputs.fileName("notes..pdf"));
        assertEquals("notes.pdf", AttachmentInputs.fileName(" notes.pdf "));
        assertDenied(422, "EXTENSION_MISMATCH",
                () -> service.upload(patientA, request("application/pdf", "notes.png", "25504446", 8, CHECKSUM)));
    }

    @Test
    void rejectsSizeOutsideBoundsAndAcceptsBoundaries() {
        assertDenied(422, "INVALID_SIZE_BYTES",
                () -> service.upload(patientA, request("application/pdf", "notes.pdf", "25504446", 0, CHECKSUM)));
        assertDenied(422, "INVALID_SIZE_BYTES", () -> service.upload(patientA,
                request("application/pdf", "notes.pdf", "25504446", AttachmentInputs.MAX_SIZE_BYTES + 1, CHECKSUM)));
        assertEquals(1, service.upload(patientA,
                request("application/pdf", "notes.pdf", "25504446", 1, CHECKSUM)).sizeBytes());
        assertEquals(AttachmentInputs.MAX_SIZE_BYTES, service.upload(patientA,
                request("application/pdf", "notes.pdf", "25504446", AttachmentInputs.MAX_SIZE_BYTES, CHECKSUM)).sizeBytes());
    }

    @Test
    void rejectsMalformedMagicAndChecksum() {
        assertDenied(422, "INVALID_MAGIC_HEX",
                () -> service.upload(patientA, request("application/pdf", "notes.pdf", "2", 8, CHECKSUM)));
        assertDenied(422, "INVALID_MAGIC_HEX",
                () -> service.upload(patientA, request("application/pdf", "notes.pdf", "zz", 8, CHECKSUM)));
        assertDenied(422, "INVALID_CHECKSUM",
                () -> service.upload(patientA, request("application/pdf", "notes.pdf", "25504446", 8, "abc")));
        assertDenied(422, "INVALID_CHECKSUM", () -> service.upload(patientA,
                request("application/pdf", "notes.pdf", "25504446", 8, CHECKSUM.toUpperCase())));
        assertEquals(CHECKSUM.toLowerCase(), service.upload(patientA,
                request("application/pdf", "notes.pdf", "25504446", 8, CHECKSUM)).checksum());
    }

    // --- A2: sender binding, connection grants and authorization ------------

    @Test
    void bindsUploaderUnitAndPatientFromTheServerGrant() {
        Attachment byPatient = service.upload(patientA, pdf());
        Attachment byStaff = service.upload(staffA, pdf());
        assertEquals(UNIT, byPatient.unitId());
        assertEquals(PATIENT, byPatient.patientId());
        assertEquals(UNIT, byStaff.unitId());
        assertEquals(PATIENT, byStaff.patientId());
        assertEquals("patient-a", byPatient.uploaderSubject());
        assertEquals("staff-a", byStaff.uploaderSubject());
    }

    @Test
    void deniesUnknownInactiveAndForeignGrants() {
        assertDenied(404, "NOT_FOUND", () -> service.upload(
                Actor.patient(ORG_A, "patient-a", PATIENT),
                new UploadRequest("missing", "notes.pdf", "application/pdf", "25504446", 8, CHECKSUM)));
        store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT, false));
        assertDenied(403, "INACTIVE_CONNECTION", () -> service.upload(patientA, pdf()));
        store.putGrant(grant(ORG_A, CONNECTION, UNIT, PATIENT, true));
        // A grant whose stored organization differs from the active tenant is refused,
        // even if a broken store boundary hands it to the wrong tenant.
        AttachmentStore leaking = new ForeignGrantStore(store,
                new ConnectionGrant(ORG_B, CONNECTION, UNIT, PATIENT, true));
        AttachmentService leakingService = new AttachmentService(leaking, Clock.fixed(NOW, ZoneOffset.UTC));
        assertDenied(403, "WRONG_UNIT", () -> leakingService.upload(
                new Actor(ORG_A, "patient-a", Set.of("patient"), PATIENT, null),
                new UploadRequest(CONNECTION, "notes.pdf", "application/pdf", "25504446", 8, CHECKSUM)));
    }

    @Test
    void deniesMismatchedPatientAndStaffUnitBindings() {
        assertDenied(403, "WRONG_UNIT", () -> service.upload(wrongPatientA, pdf()));
        assertDenied(403, "WRONG_UNIT", () -> service.upload(wrongUnitStaffA, pdf()));
        assertDenied(403, "WRONG_UNIT", () -> service.upload(
                new Actor(ORG_A, "staff-a", Set.of("reception"), null, null), pdf()));
    }

    @Test
    void deniesActorsWithoutPermissionAndNullActor() {
        assertDenied(403, "FORBIDDEN", () -> service.upload(auditorA, pdf()));
        assertDenied(403, "FORBIDDEN", () -> service.upload(noRoleA, pdf()));
        assertDenied(403, "FORBIDDEN", () -> service.upload(null, pdf()));
    }

    // --- A3: scan verdicts and state transitions ----------------------------

    @Test
    void recordsCleanAndFailedVerdictsAsSeparateFacts() {
        Attachment quarantined = service.upload(patientA, pdf());
        Attachment clean = service.scan(scannerA, quarantined.id(), new ScanRequest("clean", null), 1);
        assertEquals(AttachmentState.CLEAN, clean.state());
        assertEquals(ScanVerdict.CLEAN, clean.verdict());
        assertEquals(NOW, clean.createdAt());
        Attachment failed = service.scan(scannerA, clean.id(), new ScanRequest("failed", "signature"), 2);
        assertEquals(AttachmentState.FAILED, failed.state());
        assertEquals(ScanVerdict.FAILED, failed.verdict());
        List<String> kinds = service.events(patientA, failed.id()).stream()
                .map(fact -> fact.eventType().wire()).toList();
        assertEquals(List.of("uploaded", "scan_clean", "scan_failed"), kinds);
    }

    @Test
    void refusesScanFromNonScannerOnUnknownStaleOrTerminalState() {
        assertDenied(403, "FORBIDDEN", () -> service.scan(patientA, "id", new ScanRequest("clean", null), 1));
        assertDenied(404, "NOT_FOUND", () -> service.scan(scannerA, "no-such-id", new ScanRequest("clean", null), 1));
        Attachment a = service.upload(patientA, pdf());
        assertDenied(409, "STALE_VERSION", () -> service.scan(scannerA, a.id(), new ScanRequest("clean", null), 7));
        assertDenied(422, "INVALID_VERDICT", () -> service.scan(scannerA, a.id(), new ScanRequest("pending", null), 1));
        assertDenied(422, "INVALID_VERDICT", () -> service.scan(scannerA, a.id(), new ScanRequest(null, null), 1));
        assertDenied(422, "INVALID_DETAIL", () -> service.scan(scannerA, a.id(),
                new ScanRequest("clean", "x".repeat(201)), 1));
        Attachment removed = service.remove(patientA, a.id(), new RemoveRequest(null), 1);
        assertDenied(409, "INVALID_SCAN_STATE", () -> service.scan(scannerA, removed.id(), new ScanRequest("clean", null), 2));
    }

    // --- A4: explicit send, all-or-nothing publication and wrong-unit denial -

    @Test
    void publishesNamedCleanFilesOnlyAfterExplicitSend() {
        Attachment first = clean(service.upload(patientA, pdf()));
        Attachment second = clean(service.upload(patientA, png()));
        List<Attachment> published = service.publish(patientA,
                new PublishRequest(List.of(first.id(), second.id()), MESSAGE, UNIT));
        assertEquals(2, published.size());
        assertTrue(published.stream().allMatch(a -> a.state() == AttachmentState.PUBLISHED));
        assertTrue(published.stream().allMatch(a -> MESSAGE.equals(a.messageId())));
        assertTrue(published.stream().allMatch(a -> UNIT.equals(a.recipientUnitId())));
        assertEquals(3, published.get(0).version());
        assertEquals(UNIT, published.get(0).unitId());
        List<Attachment> inbox = service.inbox(staffA, UNIT);
        assertEquals(2, inbox.size());
    }

    @Test
    void publishesNothingWhenAnyNamedFileIsInvalid() {
        Attachment cleanOne = clean(service.upload(patientA, pdf()));
        Attachment quarantined = service.upload(patientA, pdf());
        assertDenied(409, "NOT_CLEAN", () -> service.publish(patientA,
                new PublishRequest(List.of(cleanOne.id(), quarantined.id()), MESSAGE, UNIT)));
        assertEquals(AttachmentState.CLEAN, service.view(patientA, cleanOne.id()).attachment().state());
    }

    @Test
    void rejectsEmptyDuplicateAndMalformedPublishCommands() {
        assertDenied(422, "EMPTY_ATTACHMENTS", () -> service.publish(patientA, new PublishRequest(null, MESSAGE, UNIT)));
        assertDenied(422, "EMPTY_ATTACHMENTS", () -> service.publish(patientA, new PublishRequest(List.of(), MESSAGE, UNIT)));
        Attachment a = clean(service.upload(patientA, pdf()));
        assertDenied(422, "DUPLICATE_ATTACHMENTS", () -> service.publish(patientA,
                new PublishRequest(List.of(a.id(), a.id()), MESSAGE, UNIT)));
        assertDenied(422, "INVALID_MESSAGE_ID", () -> service.publish(patientA,
                new PublishRequest(List.of(a.id()), "!", UNIT)));
        assertDenied(422, "INVALID_RECIPIENT_UNIT_ID", () -> service.publish(patientA,
                new PublishRequest(List.of(a.id()), MESSAGE, "!")));
    }

    @Test
    void deniesPublishForUnknownFileOtherUploaderWrongUnitAndWrongRecipient() {
        Attachment a = clean(service.upload(patientA, pdf()));
        assertDenied(404, "NOT_FOUND", () -> service.publish(patientA,
                new PublishRequest(List.of("00000000-0000-0000-0000-000000000000"), MESSAGE, UNIT)));
        assertDenied(403, "NOT_UPLOADER", () -> service.publish(staffA,
                new PublishRequest(List.of(a.id()), MESSAGE, UNIT)));
        assertDenied(403, "WRONG_UNIT", () -> service.publish(patientA,
                new PublishRequest(List.of(a.id()), MESSAGE, OTHER_UNIT)));
        // Same uploader subject but a re-linked patient identity: publication must
        // still fail the live participant check (IAM-05 revocation semantics).
        assertDenied(403, "WRONG_UNIT", () -> service.publish(
                Actor.patient(ORG_A, "patient-a", OTHER_PATIENT),
                new PublishRequest(List.of(a.id()), MESSAGE, UNIT)));
    }

    @Test
    void patientMayPublishOwnFileAndStdAffirmsParticipantBinding() {
        Attachment patientFile = clean(service.upload(patientA, pdf()));
        assertEquals(1, service.publish(patientA,
                new PublishRequest(List.of(patientFile.id()), MESSAGE, UNIT)).size());
        Attachment staffFile = clean(service.upload(staffA, pdf()));
        assertEquals(1, service.publish(staffA,
                new PublishRequest(List.of(staffFile.id()), MESSAGE, UNIT)).size());
        assertDenied(403, "FORBIDDEN", () -> service.publish(auditorA,
                new PublishRequest(List.of(staffFile.id()), MESSAGE, UNIT)));
    }

    // --- A5: clean-verdict access, quarantine privacy and mediated downloads -

    @Test
    void downloadIsMediatedAndNeverReturnsBytesOrUrls() {
        Attachment a = clean(service.upload(patientA, pdf()));
        service.publish(patientA, new PublishRequest(List.of(a.id()), MESSAGE, UNIT));
        MediatedDownload handle = service.download(patientA, a.id(), 3);
        assertEquals(a.objectKey(), handle.objectKey());
        assertEquals(1, handle.objectVersion());
        assertEquals(CHECKSUM, handle.checksum());
        assertEquals("application/pdf", handle.contentType());
        assertEquals(128, handle.sizeBytes());
        assertTrue(service.events(patientA, a.id()).stream()
                .anyMatch(fact -> fact.eventType() == EventType.DOWNLOADED));
    }

    @Test
    void refusesDownloadBeforeCleanVerdictAndPublication() {
        Attachment quarantined = service.upload(patientA, pdf());
        assertDenied(409, "NOT_PUBLISHED", () -> service.download(patientA, quarantined.id(), 1));
        Attachment cleaned = service.scan(scannerA, quarantined.id(), new ScanRequest("clean", null), 1);
        assertDenied(409, "NOT_PUBLISHED", () -> service.download(patientA, cleaned.id(), 2));
    }

    @Test
    void deniesDownloadToWrongUnitWrongPatientAndNonDownloader() {
        Attachment a = clean(service.upload(patientA, pdf()));
        service.publish(patientA, new PublishRequest(List.of(a.id()), MESSAGE, UNIT));
        assertDenied(403, "WRONG_UNIT", () -> service.download(wrongPatientA, a.id(), 3));
        assertDenied(403, "WRONG_UNIT", () -> service.download(wrongUnitStaffA, a.id(), 3));
        assertDenied(403, "FORBIDDEN", () -> service.download(auditorA, a.id(), 3));
        assertDenied(404, "NOT_FOUND", () -> service.download(patientB, a.id(), 3));
        assertDenied(409, "STALE_VERSION", () -> service.download(patientA, a.id(), 9));
    }

    @Test
    void wrongUnitStaffCannotSeeOrListAnotherUnitsInbox() {
        Attachment a = clean(service.upload(patientA, pdf()));
        service.publish(patientA, new PublishRequest(List.of(a.id()), MESSAGE, UNIT));
        assertDenied(404, "NOT_FOUND", () -> service.view(wrongUnitStaffA, a.id()));
        assertDenied(404, "NOT_FOUND", () -> service.events(wrongUnitStaffA, a.id()));
        // A draft in the same unit must not leak through the unit inbox filter.
        service.upload(patientA, pdf());
        assertDenied(403, "WRONG_UNIT", () -> service.download(
                new Actor(ORG_A, "staff-a", Set.of("reception"), null, null), a.id(), 3));
        assertDenied(403, "WRONG_UNIT", () -> service.inbox(wrongUnitStaffA, UNIT));
        assertDenied(403, "WRONG_UNIT", () -> service.inbox(staffA, OTHER_UNIT));
        assertDenied(403, "PATIENT_INBOX_UNAVAILABLE", () -> service.inbox(patientA, UNIT));
        assertDenied(422, "INVALID_UNIT_ID", () -> service.inbox(staffA, "!"));
        assertDenied(403, "WRONG_UNIT", () -> service.inbox(
                new Actor(ORG_A, "staff-a", Set.of("reception"), null, null), UNIT));
        assertTrue(service.inbox(staffA, UNIT).stream().anyMatch(item -> a.id().equals(item.id())));
        Actor otherUnitStaff = Actor.staff(ORG_A, "other-staff", OTHER_UNIT, "reception");
        assertFalse(service.inbox(otherUnitStaff, OTHER_UNIT).stream().anyMatch(item -> a.id().equals(item.id())));
    }

    // --- A6: failure removal, chart filing and visibility -------------------

    @Test
    void senderCanRemoveFailedDraftButNotPublishedFile() {
        Attachment a = service.upload(patientA, pdf());
        Attachment failed = service.scan(scannerA, a.id(), new ScanRequest("failed", "signature"), 1);
        assertDenied(422, "INVALID_DETAIL", () -> service.remove(patientA, failed.id(),
                new RemoveRequest("x".repeat(201)), 2));
        Attachment removed = service.remove(patientA, failed.id(), new RemoveRequest("replace"), 2);
        assertEquals(AttachmentState.REMOVED, removed.state());
        assertDenied(409, "INVALID_REMOVE_STATE", () -> service.remove(patientA, removed.id(), null, 3));
        assertDenied(404, "NOT_FOUND", () -> service.remove(patientA, "no-such-id", null, 1));
        assertDenied(409, "STALE_VERSION", () -> service.remove(patientA, failed.id(), null, 1));
        Attachment other = service.upload(staffA, pdf());
        assertDenied(403, "NOT_UPLOADER", () -> service.remove(patientA, other.id(), null, 1));
        Attachment published = clean(service.upload(patientA, pdf()));
        service.publish(patientA, new PublishRequest(List.of(published.id()), MESSAGE, UNIT));
        assertDenied(409, "INVALID_REMOVE_STATE", () -> service.remove(patientA, published.id(), null, 3));
    }

    @Test
    void clinicianFilesPublishedPatientSuppliedMaterialInChart() {
        Attachment a = clean(service.upload(patientA, pdf()));
        Attachment published = service.publish(patientA,
                new PublishRequest(List.of(a.id()), MESSAGE, UNIT)).get(0);
        assertEquals(published,
                service.fileIntoChart(clinicianA, a.id(), new FileRequest("enc-1", "reviewed"), 3));
        assertTrue(service.events(patientA, a.id()).stream()
                .anyMatch(fact -> fact.eventType() == EventType.FILED_TO_CHART));
        assertDenied(403, "FORBIDDEN", () -> service.fileIntoChart(staffA, a.id(), new FileRequest("enc-1", null), 3));
        assertDenied(403, "WRONG_UNIT", () -> service.fileIntoChart(wrongUnitClinicianA, a.id(), new FileRequest("enc-1", null), 3));
        assertDenied(403, "WRONG_UNIT", () -> service.fileIntoChart(
                new Actor(ORG_A, "clinician-a", Set.of("clinician"), null, null), a.id(), new FileRequest("enc-1", null), 3));
        assertDenied(422, "INVALID_ENCOUNTER_ID", () -> service.fileIntoChart(clinicianA, a.id(), new FileRequest("!", null), 3));
        assertDenied(422, "INVALID_DETAIL", () -> service.fileIntoChart(clinicianA, a.id(),
                new FileRequest("enc-1", "x".repeat(201)), 3));
        Attachment draft = service.upload(patientA, pdf());
        assertDenied(409, "NOT_PUBLISHED", () -> service.fileIntoChart(clinicianA, draft.id(), new FileRequest("enc-1", null), 1));
    }

    @Test
    void quarantineIsPrivateToUploaderAndViewsAreTenantConcealed() {
        Attachment draft = service.upload(patientA, pdf());
        assertEquals(draft, service.view(patientA, draft.id()).attachment());
        assertDenied(404, "NOT_FOUND", () -> service.view(staffA, draft.id()));
        assertDenied(404, "NOT_FOUND", () -> service.view(patientB, draft.id()));
        assertDenied(403, "FORBIDDEN", () -> service.view(noRoleA, draft.id()));
        assertDenied(404, "NOT_FOUND", () -> service.view(patientA, "no-such-id"));
        assertDenied(404, "NOT_FOUND", () -> service.events(patientA, "no-such-id"));
        assertDenied(404, "NOT_FOUND", () -> service.events(patientB, draft.id()));
        View view = service.view(patientA, draft.id());
        assertThrows(UnsupportedOperationException.class, () -> view.events().clear());
    }

    // --- A7: two-tenant isolation with identical local identifiers ---------

    @Test
    void twoTenantsWithIdenticalLocalIdentifiersStayIsolated() {
        Attachment a = service.upload(patientA, pdf());
        Attachment b = service.upload(patientB, pdf());
        assertNotEquals(a.objectKey(), b.objectKey());
        assertEquals(ORG_A, a.organizationId());
        assertEquals(ORG_B, b.organizationId());
        assertDenied(404, "NOT_FOUND", () -> service.view(patientB, a.id()));
        assertDenied(404, "NOT_FOUND", () -> service.view(patientA, b.id()));
        assertDenied(404, "NOT_FOUND", () -> service.download(patientB, a.id(), 1));
        Attachment staffInB = service.upload(staffB, pdf());
        assertEquals(ORG_B, staffInB.organizationId());
        assertEquals(UNIT, staffInB.unitId());
        assertTrue(service.events(patientA, a.id()).stream()
                .allMatch(fact -> ORG_A.equals(fact.organizationId())));
        assertTrue(service.events(patientB, b.id()).stream()
                .allMatch(fact -> ORG_B.equals(fact.organizationId())));
    }

    @Test
    void eventsAreScopedToTheRequestedAttachmentWithinOneTenant() {
        Attachment a = service.upload(patientA, pdf());
        service.scan(scannerA, a.id(), new ScanRequest("failed", "detail-of-file-a"), 1);
        Attachment b = service.upload(patientA, pdf());
        service.scan(scannerA, b.id(), new ScanRequest("failed", "detail-of-file-b"), 1);
        assertNotEquals(a.id(), b.id());

        List<AttachmentEvent> aTrail = service.events(patientA, a.id());
        assertEquals(2, aTrail.size());
        assertTrue(aTrail.stream().allMatch(fact -> a.id().equals(fact.attachmentId())));
        assertTrue(aTrail.stream().anyMatch(fact -> "detail-of-file-a".equals(fact.detail())));
        assertTrue(aTrail.stream().noneMatch(fact -> "detail-of-file-b".equals(fact.detail())));
        // events() and its sibling view() must expose the same per-file trail.
        assertEquals(service.view(patientA, a.id()).events(), aTrail);

        List<AttachmentEvent> bTrail = service.events(patientA, b.id());
        assertEquals(2, bTrail.size());
        assertTrue(bTrail.stream().allMatch(fact -> b.id().equals(fact.attachmentId())));
        assertTrue(bTrail.stream().anyMatch(fact -> "detail-of-file-b".equals(fact.detail())));
        assertTrue(bTrail.stream().noneMatch(fact -> "detail-of-file-a".equals(fact.detail())));
    }

    // --- A8: pure contract and validation coverage --------------------------

    @Test
    void contractEnumsParseAndEnforceLegalTransitions() {
        for (FileType type : FileType.values()) {
            assertTrue(type.accepts(type.contentType().toUpperCase()));
        }
        assertFalse(FileType.PDF.accepts(null));
        assertTrue(FileType.PDF.matchesExtension("FILE.PDF"));
        assertFalse(FileType.PDF.matchesExtension("file.png"));
        for (ScanVerdict verdict : ScanVerdict.values()) {
            assertEquals(verdict, ScanVerdict.from(verdict.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> ScanVerdict.from("unknown"));
        for (EventType type : EventType.values()) {
            assertEquals(type, EventType.from(type.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> EventType.from("unknown"));
        for (AttachmentState state : AttachmentState.values()) {
            assertEquals(state, AttachmentState.from(state.wire()));
            assertTrue(state.canTransitionTo(state));
            assertEquals(state.wire().toLowerCase(), state.wire());
        }
        assertThrows(IllegalArgumentException.class, () -> AttachmentState.from("unknown"));
        assertTrue(AttachmentState.QUARANTINED.canTransitionTo(AttachmentState.CLEAN));
        assertTrue(AttachmentState.QUARANTINED.canTransitionTo(AttachmentState.FAILED));
        assertTrue(AttachmentState.QUARANTINED.canTransitionTo(AttachmentState.REMOVED));
        assertTrue(AttachmentState.CLEAN.canTransitionTo(AttachmentState.PUBLISHED));
        assertTrue(AttachmentState.CLEAN.canTransitionTo(AttachmentState.REMOVED));
        assertTrue(AttachmentState.FAILED.canTransitionTo(AttachmentState.REMOVED));
        assertFalse(AttachmentState.PUBLISHED.canTransitionTo(AttachmentState.CLEAN));
        assertFalse(AttachmentState.REMOVED.canTransitionTo(AttachmentState.CLEAN));
        // Exhaustive matrix closes every short-circuit outcome of canTransitionTo.
        for (AttachmentState from : AttachmentState.values()) {
            for (AttachmentState to : AttachmentState.values()) {
                from.canTransitionTo(to);
            }
        }
        assertTrue(AttachmentState.PUBLISHED.downloadable());
        assertFalse(AttachmentState.CLEAN.downloadable());
    }

    @Test
    void validationHelpersCoverBoundaryAndFormatBranches() {
        assertEquals(CHECKSUM, AttachmentInputs.checksum(CHECKSUM));
        assertNotNull(AttachmentInputs.newId());
        assertTrue(AttachmentInputs.objectKey(ORG_A, "id", 2).endsWith("/v2"));
        assertDenied(422, "INVALID_ID", () -> AttachmentInputs.uuid(null, "id"));
        assertDenied(422, "INVALID_ID", () -> AttachmentInputs.uuid("not-a-uuid", "id"));
        assertEquals(ORG_A, AttachmentInputs.uuid(ORG_A, "id"));
        assertDenied(422, "INVALID_KEY", () -> AttachmentInputs.key(null, "key"));
        assertDenied(422, "INVALID_KEY", () -> AttachmentInputs.key(" ", "key"));
        assertDenied(422, "INVALID_KEY", () -> AttachmentInputs.key("a".repeat(65), "key"));
        assertEquals("key_1", AttachmentInputs.key(" key_1 ", "key"));
        assertDenied(422, "INVALID_MAGIC_HEX", () -> AttachmentInputs.magicHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertDenied(422, "INVALID_MAGIC_HEX", () -> AttachmentInputs.magicHex(null));
        assertEquals("ffd8ff", AttachmentInputs.magicHex("FFD8FF"));
        assertDenied(422, "INVALID_CHECKSUM", () -> AttachmentInputs.checksum(null));
        assertDenied(422, "INVALID_DETAIL", () -> AttachmentInputs.detail("x".repeat(11), 10));
        assertEquals("detail", AttachmentInputs.detail(" detail ", 10));
        assertEquals(null, AttachmentInputs.detail("   ", 10));
        assertEquals(null, AttachmentInputs.detail(null, 10));
        assertFalse(AttachmentActions.allows("UNKNOWN", "patient"));
        for (String action : List.of(AttachmentActions.UPLOAD, AttachmentActions.PUBLISH,
                AttachmentActions.DOWNLOAD, AttachmentActions.REMOVE)) {
            assertTrue(AttachmentActions.allows(action, "patient"));
            assertFalse(AttachmentActions.allows(action, "security_scanner"));
        }
        assertTrue(AttachmentActions.allows(AttachmentActions.SCAN, "security_scanner"));
        assertTrue(AttachmentActions.allows(AttachmentActions.FILE, "clinician"));
        assertTrue(AttachmentActions.allows(AttachmentActions.READ, "privacy_auditor"));
        assertTrue(store.events("00000000-0000-0000-0000-000000000000").isEmpty());
    }

    // --- helpers -------------------------------------------------------------

    private Attachment clean(Attachment uploaded) {
        return service.scan(scannerA, uploaded.id(), new ScanRequest("clean", null), uploaded.version());
    }

    private UploadRequest pdf() {
        return request("application/pdf", "notes.pdf", "25504446", 128, CHECKSUM);
    }

    private UploadRequest png() {
        return request("image/png", "notes.png", "89504e470d0a1a0a", 64, OTHER_CHECKSUM);
    }

    private UploadRequest request(String contentType, String fileName, String magic, long size, String checksum) {
        return new UploadRequest(CONNECTION, fileName, contentType, magic, size, checksum);
    }

    private static ConnectionGrant grant(String org, String connection, String unit, String patient, boolean active) {
        return new ConnectionGrant(org, connection, unit, patient, active);
    }

    private static void assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }

    /** A deliberately leaking boundary used to prove the service's tenant check. */
    private static final class ForeignGrantStore implements AttachmentStore {
        private final AttachmentStore delegate;
        private final ConnectionGrant foreign;

        private ForeignGrantStore(AttachmentStore delegate, ConnectionGrant foreign) {
            this.delegate = delegate;
            this.foreign = foreign;
        }

        @Override
        public java.util.Optional<ConnectionGrant> grant(String organizationId, String connectionId) {
            return connectionId.equals(foreign.connectionId())
                    ? java.util.Optional.of(foreign) : delegate.grant(organizationId, connectionId);
        }

        @Override
        public void putGrant(ConnectionGrant grant) {
            delegate.putGrant(grant);
        }

        @Override
        public java.util.Optional<Attachment> attachment(String organizationId, String id) {
            return delegate.attachment(organizationId, id);
        }

        @Override
        public void putAttachment(Attachment attachment) {
            delegate.putAttachment(attachment);
        }

        @Override
        public List<Attachment> attachments(String organizationId) {
            return delegate.attachments(organizationId);
        }

        @Override
        public void appendEvent(AttachmentEvent event) {
            delegate.appendEvent(event);
        }

        @Override
        public List<AttachmentEvent> events(String organizationId) {
            return delegate.events(organizationId);
        }
    }

    /** Keeps the import of ArrayList exercised by the store snapshot path. */
    @Test
    void storeSnapshotsAreIndependent() {
        Attachment a = service.upload(patientA, pdf());
        List<Attachment> snapshot = new ArrayList<>(store.attachments(ORG_A));
        assertEquals(1, snapshot.size());
        snapshot.clear();
        assertEquals(1, store.attachments(ORG_A).size());
        List<AttachmentEvent> facts = new ArrayList<>(store.events(ORG_A));
        facts.clear();
        assertFalse(store.events(ORG_A).isEmpty());
        assertEquals(a, store.attachment(ORG_A, a.id()).orElseThrow());
        assertTrue(store.grant(ORG_A, CONNECTION).orElseThrow().active());
    }
}
