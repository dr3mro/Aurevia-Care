package com.aurevia.smartclinic.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ScanVerdict;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.smartclinic.messaging.attachments.AttachmentService;
import com.aurevia.smartclinic.messaging.attachments.InMemoryAttachmentStore;

/**
 * MSG-03 clause 1 proof: both a verified patient and an authorized unit staff
 * member may attach PDF, JPEG and PNG files through the shared attachment
 * domain. The positive case uploads every supported type as each side and
 * asserts that the server binds the unit, patient and uploader from the
 * verified connection rather than from the command. Negative cases show the
 * rule refusing unsupported or mismatched files, roles without upload
 * permission, bindings outside the verified connection and unknown grants.
 * All identifiers, subjects and payload metadata are synthetic.
 */
class AllowSidesAttachPdfsJpegPngImagesTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String UNIT = "unit-1";
    private static final String PATIENT = "pat-001";
    private static final String OTHER_PATIENT = "pat-002";
    private static final String OTHER_UNIT = "unit-2";
    private static final String CONNECTION = "conv-1";
    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final long SIZE = 128L;

    private InMemoryAttachmentStore store;
    private AttachmentService service;

    private final Actor patientA = Actor.patient(ORG_A, "patient-a", PATIENT);
    private final Actor staffA = Actor.staff(ORG_A, "staff-a", UNIT, "reception");
    private final Actor auditorA = Actor.staff(ORG_A, "auditor-a", UNIT, "privacy_auditor");
    private final Actor noRoleA = Actor.staff(ORG_A, "no-role-a", UNIT, "unrecognized");
    private final Actor wrongPatientA = Actor.patient(ORG_A, "patient-a", OTHER_PATIENT);
    private final Actor wrongUnitStaffA = Actor.staff(ORG_A, "staff-a", OTHER_UNIT, "reception");
    private final Actor noUnitStaffA = new Actor(ORG_A, "staff-a", Set.of("reception"), null, null);

    @BeforeEach
    void setUp() {
        store = new InMemoryAttachmentStore();
        service = new AttachmentService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        store.putGrant(new ConnectionGrant(ORG_A, CONNECTION, UNIT, PATIENT, true));
    }

    @Test
    void patientAndStaffCanEachUploadPdfJpegAndPng() {
        assertEquals(3, FileType.values().length, "the clause covers exactly PDF, JPEG and PNG");
        Set<String> uploaderSubjects = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Set<String> objectKeys = new HashSet<>();

        for (Actor uploader : List.of(patientA, staffA)) {
            for (FileType type : FileType.values()) {
                String fileName = fileNameFor(type);
                Attachment uploaded = service.upload(uploader,
                        new UploadRequest(CONNECTION, fileName, type.contentType(),
                                type.magicPrefix(), SIZE, CHECKSUM));
                String context = type + " uploaded by " + uploader.subject();

                assertEquals(type, uploaded.fileType(), context);
                assertEquals(AttachmentState.QUARANTINED, uploaded.state(), context);
                assertEquals(ScanVerdict.PENDING, uploaded.verdict(), context);
                assertEquals(fileName, uploaded.fileName(), context);
                assertEquals(SIZE, uploaded.sizeBytes(), context);
                assertEquals(CHECKSUM, uploaded.checksum(), context);
                assertEquals(1, uploaded.version(), context);
                assertEquals(1, uploaded.objectVersion(), context);
                assertEquals(NOW, uploaded.createdAt(), context);
                assertTrue(uploaded.objectKey().startsWith("quarantine/" + ORG_A + "/"), context);
                assertEquals(CONNECTION, uploaded.connectionId(), context);
                assertEquals(UNIT, uploaded.unitId(), context);
                assertEquals(PATIENT, uploaded.patientId(), context);
                assertEquals(ORG_A, uploaded.organizationId(), context);
                assertEquals(uploader.subject(), uploaded.uploaderSubject(), context);
                assertTrue(store.attachment(ORG_A, uploaded.id()).isPresent(), context);

                uploaderSubjects.add(uploaded.uploaderSubject());
                ids.add(uploaded.id());
                objectKeys.add(uploaded.objectKey());
            }
        }

        assertEquals(Set.of(patientA.subject(), staffA.subject()), uploaderSubjects);
        assertEquals(6, ids.size(), "each side uploads each type as a distinct file");
        assertEquals(6, objectKeys.size(), "each upload writes a unique private object key");
    }

    @Test
    void uploadRefusesUnsupportedAndMismatchedTypes() {
        assertDenied(422, "UNSUPPORTED_CONTENT_TYPE",
                () -> service.upload(patientA, request("text/plain", "notes.txt", "25504446")));
        assertDenied(422, "UNSUPPORTED_CONTENT_TYPE",
                () -> service.upload(patientA, request("image/gif", "notes.gif", "47494638")));
        assertDenied(422, "UNSUPPORTED_CONTENT_TYPE",
                () -> service.upload(patientA, request(null, "notes.pdf", "25504446")));
        assertDenied(422, "CONTENT_MISMATCH",
                () -> service.upload(patientA,
                        request("application/pdf", "notes.pdf", FileType.PNG.magicPrefix())));
        assertDenied(422, "EXTENSION_MISMATCH",
                () -> service.upload(patientA, request("application/pdf", "notes.png", "25504446")));
    }

    @Test
    void uploadRefusesUnauthorizedActorAndForeignBinding() {
        assertDenied(403, "FORBIDDEN", () -> service.upload(auditorA, pdf()));
        assertDenied(403, "FORBIDDEN", () -> service.upload(noRoleA, pdf()));
        assertDenied(403, "FORBIDDEN", () -> service.upload(null, pdf()));
        assertDenied(403, "WRONG_UNIT", () -> service.upload(wrongPatientA, pdf()));
        assertDenied(403, "WRONG_UNIT", () -> service.upload(wrongUnitStaffA, pdf()));
        assertDenied(403, "WRONG_UNIT", () -> service.upload(noUnitStaffA, pdf()));
        assertDenied(404, "NOT_FOUND", () -> service.upload(patientA,
                new UploadRequest("missing", "notes.pdf", "application/pdf", "25504446", SIZE, CHECKSUM)));
    }

    /** Build an upload command for the seeded connection with synthetic content metadata. */
    private static UploadRequest request(String contentType, String fileName, String magicHex) {
        return new UploadRequest(CONNECTION, fileName, contentType, magicHex, SIZE, CHECKSUM);
    }

    /** Build a valid PDF upload command for the seeded connection. */
    private static UploadRequest pdf() {
        return request("application/pdf", "notes.pdf", "25504446");
    }

    /** Map a supported type to its canonical file name; JPEG uses the .jpg extension. */
    private static String fileNameFor(FileType type) {
        return type == FileType.PDF ? "notes.pdf"
                : type == FileType.JPEG ? "notes.jpg" : "notes.png";
    }

    /** Assert the stable status and machine-readable code of a refused upload. */
    private static void assertDenied(int status, String code, Runnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }
}
