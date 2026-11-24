package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentActions.Action;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.ArtifactState;
import com.aurevia.documents.DocumentContract.BindRequest;
import com.aurevia.documents.DocumentContract.CompleteRequest;
import com.aurevia.documents.DocumentContract.DocumentAuthorization;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.EventType;
import com.aurevia.documents.DocumentContract.Language;
import com.aurevia.documents.DocumentContract.MediatedDownload;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.documents.DocumentContract.PdfBinding;
import com.aurevia.documents.DocumentContract.PdfJob;
import com.aurevia.documents.DocumentContract.PdfJobState;
import com.aurevia.documents.DocumentContract.ReleaseDecision;
import com.aurevia.documents.DocumentContract.ReleaseRequest;
import com.aurevia.documents.DocumentContract.RemoveRequest;
import com.aurevia.documents.DocumentContract.RenderFinishRequest;
import com.aurevia.documents.DocumentContract.RenderRequest;
import com.aurevia.documents.DocumentContract.ScanRequest;
import com.aurevia.documents.DocumentContract.ScanVerdict;
import com.aurevia.documents.DocumentContract.SourceRevision;
import com.aurevia.documents.DocumentContract.UploadRequest;
import com.aurevia.documents.DocumentContract.View;

/**
 * X-03 acceptance for ARC-06: upload validation, private quarantine, mediated
 * download and PDF binding to immutable revisions.
 *
 * <p>All identifiers, subjects and payload metadata are synthetic. Every test
 * drives the public domain-service entry points (or the pure helpers behind
 * them) with an in-memory tenant-scoped store and a fixed clock, so no external
 * infrastructure, real patient data or credentials are needed.</p>
 */
class DocumentArtifactTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String CONTEXT = "ctx-1";
    private static final String PATIENT = "pat-001";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String OTHER_CHECKSUM = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final Actor patientA = Actor.patient(ORG_A, "patient-a", PATIENT);
    private final Actor otherPatientA = Actor.patient(ORG_A, "patient-b", "pat-999");
    private final Actor receptionA = Actor.staff(ORG_A, "reception-a", "reception");
    private final Actor reviewerA = Actor.staff(ORG_A, "reviewer-a", "document_reviewer");
    private final Actor clinicianA = Actor.staff(ORG_A, "clinician-a", "clinician");
    private final Actor scannerA = Actor.staff(ORG_A, "scanner-a", "security_scanner");
    private final Actor rendererA = Actor.staff(ORG_A, "renderer-a", "pdf_renderer");
    private final Actor auditorA = Actor.staff(ORG_A, "auditor-a", "privacy_auditor");
    private final Actor noRoleA = Actor.staff(ORG_A, "no-role-a", "unrecognized");
    private final Actor tenantB = Actor.patient(ORG_B, "patient-b", PATIENT);

    private InMemoryDocumentStore store;
    private DocumentGuard guard;
    private QuarantineService quarantine;
    private PdfBindingService pdf;
    private Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new InMemoryDocumentStore();
        guard = new DocumentGuard(store, clock);
        quarantine = new QuarantineService(store, guard, clock);
        pdf = new PdfBindingService(store, guard, clock);
        store.putAuthorization(authorization(ORG_A, CONTEXT, PATIENT, true));
        store.putAuthorization(authorization(ORG_B, CONTEXT, PATIENT, true));
    }

    // --- A1: upload validation ------------------------------------------------

    @Test
    void acceptsSupportedMediaTypesIntoPrivateQuarantine() {
        for (MediaType type : MediaType.values()) {
            Artifact uploaded = quarantine.upload(patientA, upload(type, type.magicPrefix(), CHECKSUM));
            assertEquals(type, uploaded.fileType());
            assertEquals(ArtifactState.QUARANTINED, uploaded.state());
            assertEquals(ScanVerdict.PENDING, uploaded.verdict());
            assertEquals(1, uploaded.version());
            assertEquals(1, uploaded.objectVersion());
            assertTrue(uploaded.objectKey().startsWith("quarantine/" + ORG_A + "/"));
            assertEquals(NOW, uploaded.createdAt());
            assertEquals(CONTEXT, uploaded.contextId());
            assertEquals(PATIENT, uploaded.patientId());
        }
    }

    @Test
    void rejectsUnsupportedContentTypeIncludingNull() {
        assertDenied(422, "UNSUPPORTED_CONTENT_TYPE",
                () -> quarantine.upload(patientA, new UploadRequest(CONTEXT, "notes.txt",
                        "text/plain", "25504446", 128, CHECKSUM)));
        assertDenied(422, "UNSUPPORTED_CONTENT_TYPE",
                () -> quarantine.upload(patientA, new UploadRequest(CONTEXT, "notes.pdf",
                        null, "25504446", 128, CHECKSUM)));
    }

    @Test
    void rejectsPayloadWhoseBytesDoNotMatchDeclaredType() {
        assertDenied(422, "CONTENT_MISMATCH", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "89504e470d0a1a0a", 8, CHECKSUM)));
        assertDenied(422, "CONTENT_MISMATCH", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.jpg", "image/jpeg", "25504446", 8, CHECKSUM)));
        assertDenied(422, "CONTENT_MISMATCH", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.png", "image/png", "ffd8ff", 8, CHECKSUM)));
    }

    @Test
    void rejectsPathFileNamesAndWrongExtensions() {
        assertDenied(422, "EXTENSION_MISMATCH", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.png", "application/pdf", "25504446", 8, CHECKSUM)));
        assertDenied(422, "INVALID_FILE_NAME", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "../notes.pdf", "application/pdf", "25504446", 8, CHECKSUM)));
        assertDenied(422, "INVALID_FILE_NAME", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes..pdf", "application/pdf", "25504446", 8, CHECKSUM)));
        assertDenied(422, "INVALID_FILE_NAME", () -> ArtifactInputs.fileName(null));
        assertEquals("notes.pdf", ArtifactInputs.fileName(" notes.pdf "));
    }

    @Test
    void rejectsSizeOutsideBoundsAndAcceptsBoundaries() {
        assertDenied(422, "INVALID_SIZE_BYTES", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "25504446", 0, CHECKSUM)));
        assertDenied(422, "INVALID_SIZE_BYTES", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "25504446",
                        ArtifactInputs.MAX_SIZE_BYTES + 1, CHECKSUM)));
        assertEquals(1, quarantine.upload(patientA, new UploadRequest(CONTEXT, "notes.pdf",
                "application/pdf", "25504446", 1, CHECKSUM)).sizeBytes());
        assertEquals(ArtifactInputs.MAX_SIZE_BYTES, quarantine.upload(patientA, new UploadRequest(
                CONTEXT, "notes.pdf", "application/pdf", "25504446", ArtifactInputs.MAX_SIZE_BYTES, CHECKSUM)).sizeBytes());
    }

    @Test
    void rejectsMalformedMagicAndChecksum() {
        assertDenied(422, "INVALID_MAGIC_HEX", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "2", 8, CHECKSUM)));
        assertDenied(422, "INVALID_MAGIC_HEX", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "zz", 8, CHECKSUM)));
        assertDenied(422, "INVALID_CHECKSUM", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "25504446", 8, "abc")));
        assertDenied(422, "INVALID_CHECKSUM", () -> quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "25504446", 8, CHECKSUM.toUpperCase())));
        assertEquals(CHECKSUM, quarantine.upload(patientA,
                new UploadRequest(CONTEXT, "notes.pdf", "application/pdf", "25504446", 8, CHECKSUM)).checksum());
    }

    @Test
    void rejectsBlankContextAndContextThatIsTooLong() {
        assertDenied(422, "INVALID_CONTEXT_ID", () -> quarantine.upload(patientA,
                new UploadRequest(" ", "notes.pdf", "application/pdf", "25504446", 8, CHECKSUM)));
        assertDenied(422, "INVALID_CONTEXT_ID", () -> quarantine.upload(patientA,
                new UploadRequest("x".repeat(65), "notes.pdf", "application/pdf", "25504446", 8, CHECKSUM)));
    }

    // --- A2: server-side binding, roles and tenant isolation -----------------

    @Test
    void bindsPatientAndContextFromTheServerAuthorization() {
        Artifact byPatient = quarantine.upload(patientA, pdfUpload());
        Artifact byStaff = quarantine.upload(receptionA, pdfUpload());
        assertEquals(PATIENT, byPatient.patientId());
        assertEquals(PATIENT, byStaff.patientId());
        assertEquals("patient-a", byPatient.uploaderSubject());
        assertEquals("reception-a", byStaff.uploaderSubject());
    }

    @Test
    void deniesUnknownInactiveAndForeignAuthorizations() {
        assertDenied(404, "NOT_FOUND", () -> quarantine.upload(patientA,
                new UploadRequest("missing", "notes.pdf", "application/pdf", "25504446", 8, CHECKSUM)));
        store.putAuthorization(authorization(ORG_A, CONTEXT, PATIENT, false));
        assertDenied(403, "INACTIVE_CONTEXT", () -> quarantine.upload(patientA, pdfUpload()));
        store.putAuthorizationFor(ORG_A, CONTEXT, authorization(ORG_B, CONTEXT, PATIENT, true));
        assertDenied(403, "WRONG_TENANT", () -> quarantine.upload(patientA, pdfUpload()));
        store.putAuthorization(authorization(ORG_A, CONTEXT, PATIENT, true));
    }

    @Test
    void deniesPatientNotLinkedToTheAuthorizedContext() {
        assertDenied(403, "WRONG_OWNER", () -> quarantine.upload(otherPatientA, pdfUpload()));
    }

    @Test
    void deniesMissingRoleAndNullActorWithoutChangingState() {
        assertDenied(403, "FORBIDDEN", () -> quarantine.upload(noRoleA, pdfUpload()));
        assertDenied(403, "FORBIDDEN", () -> quarantine.upload(null, pdfUpload()));
        assertTrue(store.artifacts(ORG_A).isEmpty());
    }

    @Test
    void concealsArtifactsAcrossTenantsWithIdenticalLocalIds() {
        Artifact mine = artifact(ORG_A, "same-id", ArtifactState.COMPLETED, PATIENT);
        Artifact theirs = artifact(ORG_B, "same-id", ArtifactState.COMPLETED, PATIENT);
        store.putArtifact(mine);
        store.putArtifact(theirs);
        assertNotEquals(theirs, quarantine.view(patientA, "same-id").artifact());
        assertEquals(1, store.artifacts(ORG_A).size());
        assertEquals(ORG_A, quarantine.view(patientA, "same-id").artifact().organizationId());
        assertEquals(ORG_B, quarantine.view(tenantB, "same-id").artifact().organizationId());
        store.putArtifact(artifact(ORG_B, "only-b", ArtifactState.COMPLETED, PATIENT));
        assertEquals(2, store.artifacts(ORG_B).size());
        assertDenied(404, "NOT_FOUND", () -> quarantine.view(patientA, "only-b"));
    }

    // --- A3: quarantine lifecycle and mediated download ----------------------

    @Test
    void scanMovesQuarantinedArtifactToCleanOrFailed() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        Artifact clean = quarantine.scan(scannerA, id, new ScanRequest("clean", null), 1);
        assertEquals(ArtifactState.CLEAN, clean.state());
        assertEquals(ScanVerdict.CLEAN, clean.verdict());
        assertEquals(2, clean.version());
        String failedId = quarantine.upload(patientA, pdfUpload()).id();
        Artifact failed = quarantine.scan(scannerA, failedId, new ScanRequest("failed", "threat"), 1);
        assertEquals(ArtifactState.FAILED, failed.state());
        assertEquals(ScanVerdict.FAILED, failed.verdict());
    }

    @Test
    void rejectsScanOnInvalidStateStaleVersionAndInvalidVerdict() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        assertDenied(412, "STALE_VERSION", () -> quarantine.scan(scannerA, id, new ScanRequest("clean", null), 2));
        assertDenied(422, "INVALID_VERDICT", () -> ArtifactInputs.verdict("pending"));
        assertDenied(422, "INVALID_VERDICT", () -> ArtifactInputs.verdict(null));
        Artifact clean = quarantine.scan(scannerA, id, new ScanRequest("clean", null), 1);
        assertDenied(409, "INVALID_SCAN_STATE", () -> quarantine.scan(scannerA, id, new ScanRequest("clean", null), 2));
        String failedId = quarantine.upload(patientA, pdfUpload()).id();
        quarantine.scan(scannerA, failedId, new ScanRequest("failed", "threat"), 1);
        assertDenied(409, "INVALID_SCAN_STATE", () -> quarantine.scan(scannerA, failedId, new ScanRequest("clean", null), 2));
        assertEquals(ArtifactState.CLEAN, clean.state());
    }

    @Test
    void completionRequiresCleanStateAnAuthorizedRoleAndFreshVersion() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        assertDenied(409, "NOT_CLEAN", () -> quarantine.complete(reviewerA, id, new CompleteRequest("ok"), 1));
        quarantine.scan(scannerA, id, new ScanRequest("clean", null), 1);
        assertDenied(403, "FORBIDDEN", () -> quarantine.complete(scannerA, id, new CompleteRequest("ok"), 2));
        assertDenied(412, "STALE_VERSION", () -> quarantine.complete(reviewerA, id, new CompleteRequest("ok"), 1));
        assertDenied(422, "INVALID_DETAIL", () -> quarantine.complete(reviewerA, id,
                new CompleteRequest("x".repeat(201)), 2));
        seedObject(id);
        Artifact completed = quarantine.complete(reviewerA, id, new CompleteRequest("ok"), 2);
        assertEquals(ArtifactState.COMPLETED, completed.state());
        assertEquals(3, completed.version());
    }

    @Test
    void removeRejectsUploaderMismatchAndTerminalStates() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        assertDenied(403, "NOT_UPLOADER", () -> quarantine.remove(receptionA, id, new RemoveRequest("x"), 1));
        assertDenied(412, "STALE_VERSION", () -> quarantine.remove(patientA, id, new RemoveRequest("x"), 2));
        assertDenied(422, "INVALID_DETAIL", () -> quarantine.remove(patientA, id,
                new RemoveRequest("x".repeat(201)), 1));
        Artifact removed = quarantine.remove(patientA, id, new RemoveRequest("wrong upload"), 1);
        assertEquals(ArtifactState.REMOVED, removed.state());
        assertDenied(409, "INVALID_REMOVE_STATE", () -> quarantine.remove(patientA, id, new RemoveRequest("again"), 2));
        String completedId = completedArtifact().id();
        assertDenied(409, "INVALID_REMOVE_STATE", () -> quarantine.remove(patientA, completedId,
                new RemoveRequest("published"), 3));
    }

    @Test
    void mediatedDownloadRequiresCompletedCleanAndAuthorizedParticipant() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        assertDenied(409, "NOT_COMPLETED", () -> quarantine.download(patientA, id, 1));
        quarantine.scan(scannerA, id, new ScanRequest("clean", null), 1);
        assertDenied(409, "NOT_COMPLETED", () -> quarantine.download(patientA, id, 2));
        seedObject(id);
        quarantine.complete(reviewerA, id, new CompleteRequest("ok"), 2);
        assertDenied(403, "WRONG_OWNER", () -> quarantine.download(otherPatientA, id, 3));
        assertDenied(403, "FORBIDDEN", () -> quarantine.download(noRoleA, id, 3));
        assertDenied(412, "STALE_VERSION", () -> quarantine.download(patientA, id, 2));
        assertDenied(404, "NOT_FOUND", () -> quarantine.download(patientA, "missing", 1));
        MediatedDownload handle = quarantine.download(patientA, id, 3);
        assertEquals(MediaType.PDF.contentType(), handle.contentType());
        assertEquals(CHECKSUM, handle.checksum());
        assertEquals(128, handle.sizeBytes());
        assertTrue(handle.objectKey().startsWith("quarantine/" + ORG_A + "/"));
        assertEquals(1, handle.objectVersion());
        assertNotNull(quarantine.download(reviewerA, id, 3));
    }

    @Test
    void downloadHandleAndFactsCarryNoPayloadFileNameOrUrl() {
        String id = completedArtifact().id();
        MediatedDownload handle = quarantine.download(patientA, id, 3);
        String rendered = handle.toString();
        assertFalse(rendered.contains("notes.pdf"));
        assertFalse(rendered.contains("http"));
        for (DocumentEvent fact : quarantine.view(patientA, id).events()) {
            if (fact.detail() != null) {
                assertFalse(fact.detail().contains("notes.pdf"));
                assertFalse(fact.detail().contains(CHECKSUM));
                assertFalse(fact.detail().contains("http"));
            }
            assertNotEquals("notes.pdf", fact.resourceId());
        }
    }

    @Test
    void viewConcealsUnpublishedArtifactsFromOtherPatientsAndShowsFactTrail() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        assertDenied(404, "NOT_FOUND", () -> quarantine.view(otherPatientA, id));
        assertEquals(id, quarantine.view(patientA, id).artifact().id());
        assertEquals(1, quarantine.view(patientA, id).events().size());
        assertDenied(404, "NOT_FOUND", () -> quarantine.view(patientA, "missing"));
        assertDenied(403, "FORBIDDEN", () -> quarantine.view(noRoleA, id));
    }

    // --- A4: asynchronous PDF jobs, immutable binding and release ------------

    @Test
    void requestRenderCreatesRequestedJobAndIsIdempotent() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        assertEquals(PdfJobState.REQUESTED, job.state());
        assertEquals(1, job.version());
        assertEquals("soap-en", job.templateId());
        assertEquals(Language.EN, job.language());
        assertEquals(NOW, job.createdAt());
        PdfJob again = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        assertEquals(job.jobId(), again.jobId());
        assertEquals(1, store.pdfJobs(ORG_A).size());
        PdfJob otherRevision = pdf.requestRender(clinicianA, new RenderRequest(
                new SourceRevision(ORG_A, "rev-2", 1, CHECKSUM), "soap-en", "en"));
        assertNotEquals(job.jobId(), otherRevision.jobId());
        PdfJob otherLanguage = pdf.requestRender(clinicianA,
                new RenderRequest(revision, "soap-en", "ar"));
        assertNotEquals(job.jobId(), otherLanguage.jobId());
        assertDenied(403, "FORBIDDEN", () -> pdf.requestRender(scannerA, new RenderRequest(revision, "soap-en", "en")));
    }

    @Test
    void requestRenderRejectsForeignRevisionBadLanguageTemplateAndVersion() {
        assertDenied(403, "WRONG_TENANT", () -> pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_B, 1, CHECKSUM), "soap-en", "en")));
        assertDenied(422, "INVALID_LANGUAGE", () -> pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, CHECKSUM), "soap-en", "de")));
        assertDenied(422, "INVALID_TEMPLATE_ID", () -> pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, CHECKSUM), " ", "en")));
        assertDenied(422, "INVALID_REVISION_VERSION", () -> pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 0, CHECKSUM), "soap-en", "en")));
        assertDenied(422, "INVALID_CHECKSUM", () -> pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, "bad"), "soap-en", "en")));
    }

    @Test
    void finishRenderRequiresAuthorizedRendererAndValidArtifactMetadata() {
        PdfJob job = pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, CHECKSUM), "soap-en", "en"));
        assertDenied(403, "FORBIDDEN", () -> pdf.finishRender(scannerA, job.jobId(),
                finish("pdf/object", CHECKSUM), 1));
        assertDenied(422, "INVALID_OBJECT_KEY", () -> pdf.finishRender(rendererA, job.jobId(),
                finish(" ", CHECKSUM), 1));
        assertDenied(422, "INVALID_OBJECT_VERSION", () -> pdf.finishRender(rendererA, job.jobId(),
                new RenderFinishRequest("pdf/object", 0, CHECKSUM, 128), 1));
        assertDenied(422, "INVALID_SIZE_BYTES", () -> pdf.finishRender(rendererA, job.jobId(),
                new RenderFinishRequest("pdf/object", 1, CHECKSUM, 0), 1));
        assertDenied(422, "INVALID_CHECKSUM", () -> pdf.finishRender(rendererA, job.jobId(),
                finish("pdf/object", "bad"), 1));
        assertDenied(412, "STALE_VERSION", () -> pdf.finishRender(rendererA, job.jobId(),
                finish("pdf/object", CHECKSUM), 2));
        PdfJob complete = pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 1);
        assertEquals(PdfJobState.COMPLETE, complete.state());
        assertEquals(2, complete.version());
        assertEquals("pdf/object", complete.objectKey());
        assertEquals(1, complete.objectVersion());
        assertEquals(CHECKSUM, complete.checksum());
        assertEquals(128, complete.sizeBytes());
        assertEquals("rev-1", complete.revisionId());
        assertEquals(1, complete.revisionVersion());
        assertEquals(CHECKSUM, complete.contentChecksum());
        assertEquals("soap-en", complete.templateId());
        assertEquals(Language.EN, complete.language());
        assertEquals(ORG_A, complete.organizationId());
        assertEquals(NOW, complete.createdAt());
        assertDenied(409, "INVALID_RENDER_STATE", () -> pdf.finishRender(rendererA, job.jobId(),
                finish("pdf/object", CHECKSUM), 2));
    }

    @Test
    void failRenderRecordsFailedJobThatCannotBeFinishedOrBound() {
        PdfJob job = pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, CHECKSUM), "soap-ar", "ar"));
        PdfJob failed = pdf.failRender(rendererA, job.jobId(), "renderer offline", 1);
        assertEquals(PdfJobState.FAILED, failed.state());
        assertDenied(409, "INVALID_RENDER_STATE", () -> pdf.finishRender(rendererA, job.jobId(),
                finish("pdf/object", CHECKSUM), 2));
        assertDenied(409, "INVALID_RENDER_STATE", () -> pdf.failRender(rendererA, job.jobId(), "again", 2));
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.bind(reviewerA,
                new BindRequest(revision(ORG_A, 1, CHECKSUM), job.jobId())));
        PdfJob done = pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 3, CHECKSUM), "soap-en", "en"));
        pdf.finishRender(rendererA, done.jobId(), finish("pdf/object-3", CHECKSUM), 1);
        assertDenied(409, "INVALID_RENDER_STATE", () -> pdf.failRender(rendererA, done.jobId(), "late", 2));
        assertDenied(409, "INVALID_RENDER_STATE", () -> pdf.pickupRender(rendererA, done.jobId(), 2));
    }

    @Test
    void bindRequiresACompleteJobWithTheExactImmutableRevision() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.bind(reviewerA, new BindRequest(revision, job.jobId())));
        pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 1);
        assertDenied(409, "REVISION_MISMATCH", () -> pdf.bind(reviewerA,
                new BindRequest(revision(ORG_A, 2, CHECKSUM), job.jobId())));
        assertDenied(409, "REVISION_MISMATCH", () -> pdf.bind(reviewerA,
                new BindRequest(revision(ORG_A, 1, OTHER_CHECKSUM), job.jobId())));
        PdfBinding binding = pdf.bind(reviewerA, new BindRequest(revision, job.jobId()));
        assertEquals(job.jobId(), binding.jobId());
        assertEquals(1, binding.revisionVersion());
        assertEquals(CHECKSUM, binding.contentChecksum());
        assertEquals(NOW, binding.boundAt());
        assertEquals(ORG_A, binding.organizationId());
        assertEquals("rev-1", binding.revisionId());
        assertEquals("pdf/object", binding.objectKey());
        assertEquals(1, binding.objectVersion());
        assertEquals(CHECKSUM, binding.checksum());
        assertEquals(Language.EN, binding.language());
        PdfBinding again = pdf.bind(reviewerA, new BindRequest(revision, job.jobId()));
        assertEquals(binding, again);
    }

    @Test
    void bindRejectsAnotherJobForTheSameRevisionAndUnknownOrForeignJobs() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob first = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        pdf.finishRender(rendererA, first.jobId(), finish("pdf/object", CHECKSUM), 1);
        pdf.bind(reviewerA, new BindRequest(revision, first.jobId()));
        PdfJob other = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-ar", "ar"));
        pdf.finishRender(rendererA, other.jobId(), finish("pdf/object-ar", OTHER_CHECKSUM), 1);
        assertDenied(409, "PDF_ALREADY_BOUND", () -> pdf.bind(reviewerA, new BindRequest(revision, other.jobId())));
        assertDenied(404, "NOT_FOUND", () -> pdf.bind(reviewerA, new BindRequest(revision, "missing")));
        PdfJob foreign = pdf.requestRender(Actor.staff(ORG_B, "clinician-b", "clinician"),
                new RenderRequest(revision(ORG_B, 1, CHECKSUM), "soap-en", "en"));
        assertDenied(404, "NOT_FOUND", () -> pdf.bind(reviewerA, new BindRequest(revision, foreign.jobId())));
        assertDenied(403, "FORBIDDEN", () -> pdf.bind(scannerA, new BindRequest(revision, first.jobId())));

        SourceRevision correctedContent = revision(ORG_A, 1, OTHER_CHECKSUM);
        PdfJob replaced = pdf.requestRender(clinicianA,
                new RenderRequest(correctedContent, "soap-ar", "ar"));
        pdf.finishRender(rendererA, replaced.jobId(), finish("pdf/object-alt", OTHER_CHECKSUM), 1);
        PdfBinding fresh = pdf.bind(reviewerA, new BindRequest(correctedContent, replaced.jobId()));
        assertEquals(OTHER_CHECKSUM, fresh.contentChecksum());
    }

    @Test
    void aCorrectedRevisionCannotReuseTheOldPdfBinding() {
        SourceRevision first = revision(ORG_A, 1, CHECKSUM);
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(first, "policy-1", true)));
        PdfJob firstJob = pdf.requestRender(clinicianA, new RenderRequest(first, "soap-en", "en"));
        pdf.finishRender(rendererA, firstJob.jobId(), finish("pdf/object", CHECKSUM), 1);
        pdf.bind(reviewerA, new BindRequest(first, firstJob.jobId()));
        assertNotNull(pdf.release(clinicianA, new ReleaseRequest(first, "policy-1", true)));

        SourceRevision corrected = revision(ORG_A, 2, OTHER_CHECKSUM);
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(corrected, "policy-1", true)));
        PdfJob correctedJob = pdf.requestRender(clinicianA, new RenderRequest(corrected, "soap-en", "en"));
        pdf.finishRender(rendererA, correctedJob.jobId(), finish("pdf/object-2", OTHER_CHECKSUM), 1);
        pdf.bind(reviewerA, new BindRequest(corrected, correctedJob.jobId()));
        assertNotNull(pdf.release(clinicianA, new ReleaseRequest(corrected, "policy-1", true)));
    }

    @Test
    void releaseIsIdempotentAndCanSkipPdfWhenNotRequired() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        ReleaseDecision decision = pdf.release(clinicianA, new ReleaseRequest(revision, "policy-1", false));
        assertEquals("policy-1", decision.policyVersion());
        assertEquals("clinician-a", decision.releasedBy());
        assertEquals(NOW, decision.releasedAt());
        assertEquals(ORG_A, decision.organizationId());
        assertEquals("rev-1", decision.revisionId());
        assertEquals(1, decision.revisionVersion());
        assertEquals(CHECKSUM, decision.contentChecksum());
        assertEquals(decision, pdf.release(clinicianA, new ReleaseRequest(revision, "policy-1", false)));
        assertNotEquals(decision, pdf.release(clinicianA, new ReleaseRequest(revision, "policy-2", false)));
        pdf.release(Actor.staff(ORG_B, "clinician-b", "clinician"),
                new ReleaseRequest(revision(ORG_B, 1, CHECKSUM), "policy-1", false));
        assertEquals(2, store.releases(ORG_A).size());
        assertEquals(1, store.releases(ORG_B).size());
    }

    @Test
    void releaseRejectsUnauthorizedActorForeignRevisionAndBadPolicyVersion() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        assertDenied(403, "FORBIDDEN", () -> pdf.release(scannerA,
                new ReleaseRequest(revision, "policy-1", false)));
        assertDenied(403, "WRONG_TENANT", () -> pdf.release(clinicianA,
                new ReleaseRequest(revision(ORG_B, 1, CHECKSUM), "policy-1", false)));
        assertDenied(422, "INVALID_POLICY_VERSION", () -> pdf.release(clinicianA,
                new ReleaseRequest(revision, " ", false)));
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 1);
        pdf.bind(reviewerA, new BindRequest(revision, job.jobId()));
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(revision(ORG_A, 1, OTHER_CHECKSUM), "policy-1", true)));
        store.removeObject(ORG_A, "pdf/object", 1);
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(revision, "policy-1", true)));
        assertTrue(store.releases(ORG_A).isEmpty());
    }

    @Test
    void immutableSourceRevisionIsNeverMutatedByBindingOrRendering() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 1);
        pdf.bind(reviewerA, new BindRequest(revision, job.jobId()));
        pdf.release(clinicianA, new ReleaseRequest(revision, "policy-1", true));
        assertEquals(1, revision.revisionVersion());
        assertEquals(CHECKSUM, revision.contentChecksum());
        assertEquals(ORG_A, revision.organizationId());
    }

    // --- A5: helpers, facts and defensive copies -----------------------------

    @Test
    void enumWireKeysAndPureHelpersHaveStableSemantics() {
        assertEquals("application/pdf", MediaType.PDF.contentType());
        assertEquals(MediaType.PDF, MediaType.fromContentType("APPLICATION/PDF"));
        assertNull(MediaType.fromContentType(null));
        assertNull(MediaType.fromContentType("text/plain"));
        assertEquals("25504446", MediaType.PDF.magicPrefix());
        assertEquals("pending", ScanVerdict.PENDING.wire());
        assertEquals("ar", Language.AR.wire());
        assertEquals(Language.EN, Language.from("en"));
        assertNull(Language.from(null));
        assertEquals("quarantined", ArtifactState.QUARANTINED.wire());
        assertTrue(ArtifactState.COMPLETED.downloadable());
        assertFalse(ArtifactState.CLEAN.downloadable());
        assertEquals("requested", PdfJobState.REQUESTED.wire());
        assertEquals("uploaded", EventType.UPLOAD_ACCEPTED.wire());
        assertEquals(CHECKSUM, ArtifactInputs.checksum(CHECKSUM));
        assertEquals(3, ArtifactInputs.positiveVersion(3, "revisionVersion"));
        assertDenied(422, "INVALID_OBJECT_VERSION", () -> ArtifactInputs.positiveVersion(0, "objectVersion"));
        assertEquals("pdf/object", ArtifactInputs.verifiedObjectKey(" pdf/object ", "objectKey"));
        assertDenied(422, "INVALID_OBJECT_KEY", () -> ArtifactInputs.verifiedObjectKey(" ", "objectKey"));
        assertDenied(422, "INVALID_OBJECT_KEY", () -> ArtifactInputs.verifiedObjectKey(null, "objectKey"));
        assertEquals("25504446", ArtifactInputs.magicHex("25504446"));
        assertDenied(422, "INVALID_MAGIC_HEX", () -> ArtifactInputs.magicHex(null));
        assertDenied(422, "INVALID_MAGIC_HEX", () -> ArtifactInputs.magicHex("a".repeat(33)));
        assertDenied(422, "INVALID_CHECKSUM", () -> ArtifactInputs.checksum(null));
        assertDenied(422, "INVALID_SIZE_BYTES", () -> ArtifactInputs.size(-1));
        assertEquals("a-1", ArtifactInputs.key(" a-1 ", "test"));
        assertDenied(422, "INVALID_KEY", () -> ArtifactInputs.key(null, "key"));
        assertDenied(422, "INVALID_KEY", () -> ArtifactInputs.key(" ", "key"));
        assertEquals("detail", ArtifactInputs.detail(" detail ", 20));
        assertNull(ArtifactInputs.detail("  ", 20));
        assertDenied(422, "INVALID_DETAIL", () -> ArtifactInputs.detail("x".repeat(21), 20));
        String uuid = ArtifactInputs.newId();
        assertEquals(uuid, ArtifactInputs.uuid(uuid, "test"));
        assertDenied(422, "INVALID_TEST", () -> ArtifactInputs.uuid("bad", "test"));
        assertDenied(422, "INVALID_TEST", () -> ArtifactInputs.uuid(null, "test"));
        assertTrue(ArtifactInputs.objectKey(ORG_A, "a", 1).startsWith("quarantine/"));
        assertTrue(ArtifactInputs.pdfObjectKey(ORG_A, "r1", 1, "en").startsWith("pdf/"));
        assertEquals(Set.of("x"), new Actor(ORG_A, "s", Set.of("x"), null).roles());
    }

    @Test
    void viewDefensivelyCopiesTheFactList() {
        List<DocumentEvent> mutable = new ArrayList<>();
        mutable.add(fact("a"));
        View view = new View(artifact(ORG_A, "a", ArtifactState.COMPLETED, PATIENT), mutable);
        mutable.clear();
        assertEquals(1, view.events().size());
        assertNotSame(mutable, view.events());
    }

    @Test
    void factsAreAppendOnlyAndTenantScopedAndNeverCarryPayloads() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        quarantine.upload(tenantB, pdfUpload());
        assertEquals(1, store.events(ORG_A).size());
        assertEquals(1, store.events(ORG_B).size());
        DocumentEvent fact = store.events(ORG_A).get(0);
        assertEquals(EventType.UPLOAD_ACCEPTED, fact.eventType());
        assertEquals("success", fact.outcome());
        assertEquals("patient-a", fact.actorSubject());
        assertEquals(ORG_A, fact.organizationId());
        assertNull(fact.detail());
        assertNotNull(fact.id());
        assertEquals(NOW, fact.occurredAt());
        assertDenied(404, "NOT_FOUND", () -> quarantine.view(patientA, "missing"));
    }

    @Test
    void downloadByAuditorIsDeniedButByClinicianIsAllowed() {
        String id = completedArtifact().id();
        assertDenied(403, "FORBIDDEN", () -> quarantine.download(auditorA, id, 3));
        assertNotNull(quarantine.download(clinicianA, id, 3));
    }

    @Test
    void completionAndDownloadVerifyTheObjectBoundary() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        quarantine.scan(scannerA, id, new ScanRequest("clean", null), 1);
        assertDenied(409, "OBJECT_UNVERIFIED", () -> quarantine.complete(reviewerA, id,
                new CompleteRequest("ok"), 2));
        seedObject(id);
        Artifact completed = quarantine.complete(reviewerA, id, new CompleteRequest("ok"), 2);
        store.removeObject(ORG_A, completed.objectKey(), completed.objectVersion());
        assertDenied(409, "OBJECT_UNVERIFIED", () -> quarantine.download(patientA, id, 3));
        assertNotNull(quarantine.view(patientA, id));
    }

    @Test
    void renderJobIsPickedUpByTheRendererBeforeCompletion() {
        PdfJob job = pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, CHECKSUM), "soap-en", "en"));
        PdfJob rendering = pdf.pickupRender(rendererA, job.jobId(), 1);
        assertEquals(PdfJobState.RENDERING, rendering.state());
        assertEquals(2, rendering.version());
        assertDenied(403, "FORBIDDEN", () -> pdf.pickupRender(scannerA, job.jobId(), 2));
        assertDenied(412, "STALE_VERSION", () -> pdf.pickupRender(rendererA, job.jobId(), 1));
        assertDenied(409, "INVALID_RENDER_STATE", () -> pdf.pickupRender(rendererA, job.jobId(), 2));
        PdfJob complete = pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 2);
        assertEquals(PdfJobState.COMPLETE, complete.state());
    }

    @Test
    void storeWritesAreCompareAndSetAndReleaseInsertIsAtomic() {
        Artifact artifact = quarantine.upload(patientA, pdfUpload());
        Artifact stale = new Artifact(ORG_A, artifact.id(), 9, artifact.uploaderSubject(),
                artifact.contextId(), artifact.patientId(), artifact.fileName(), artifact.fileType(),
                artifact.sizeBytes(), artifact.checksum(), artifact.objectKey(), artifact.objectVersion(),
                artifact.verdict(), artifact.state(), artifact.createdAt());
        assertFalse(store.replaceArtifactIfVersion(ORG_A, artifact.id(), 9, stale));
        assertFalse(store.replaceArtifactIfVersion(ORG_A, "unknown", 1, stale));
        assertTrue(store.replaceArtifactIfVersion(ORG_A, artifact.id(), 1, artifact));
        assertFalse(store.verifyObject(ORG_A, "missing", 1, CHECKSUM));
        store.putObject(ORG_A, "verified", 1, CHECKSUM, 128);
        assertFalse(store.verifyObject(ORG_A, "verified", 1, OTHER_CHECKSUM));
        assertTrue(store.verifyObject(ORG_A, "verified", 1, CHECKSUM));

        PdfJob job = pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, CHECKSUM), "soap-en", "en"));
        PdfJob staleJob = new PdfJob(ORG_A, job.jobId(), job.revisionId(), job.revisionVersion(),
                job.contentChecksum(), job.templateId(), job.language(), PdfJobState.FAILED, 9,
                null, 0, null, 0, job.createdAt());
        assertFalse(store.replacePdfJobIfVersion(ORG_A, job.jobId(), 9, staleJob));
        assertFalse(store.replacePdfJobIfVersion(ORG_A, "unknown", 1, staleJob));
        assertTrue(store.replacePdfJobIfVersion(ORG_A, job.jobId(), 1, job));

        ReleaseDecision decision = new ReleaseDecision(ORG_A, "rev-1", 1, CHECKSUM,
                "policy-1", "clinician-a", NOW);
        assertTrue(store.insertReleaseIfAbsent(decision));
        assertFalse(store.insertReleaseIfAbsent(decision));
    }

    @Test
    void competingWritesAreRejectedRatherThanOverwritingCommittedState() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        quarantine.scan(scannerA, id, new ScanRequest("clean", null), 1);
        seedObject(id);
        store.failNextArtifactReplace();
        assertDenied(412, "STALE_VERSION", () -> quarantine.complete(reviewerA, id,
                new CompleteRequest("ok"), 2));

        PdfJob job = pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_A, 1, CHECKSUM), "soap-en", "en"));
        store.failNextJobReplace();
        assertDenied(412, "STALE_VERSION", () -> pdf.pickupRender(rendererA, job.jobId(), 1));

        store.failNextReleaseInsert();
        assertDenied(409, "RELEASE_CONFLICT", () -> pdf.release(clinicianA,
                new ReleaseRequest(revision(ORG_A, 1, CHECKSUM), "policy-race", false)));
        assertTrue(store.releases(ORG_A).isEmpty());
    }

    @Test
    void actionPolicyGrantsOnlyBoundedRoles() {
        assertTrue(DocumentActions.allows(Action.UPLOAD, "patient"));
        assertTrue(DocumentActions.allows(Action.UPLOAD, "clinical_assistant"));
        assertTrue(DocumentActions.allows(Action.RELEASE, "clinician"));
        assertTrue(DocumentActions.allows(Action.PICKUP_RENDER, "pdf_renderer"));
        assertTrue(DocumentActions.allows(Action.READ, "privacy_auditor"));
        assertTrue(DocumentActions.allows(Action.RELEASE, "privacy_auditor"));
        assertFalse(DocumentActions.allows(Action.READ, null));
        assertFalse(DocumentActions.allows(Action.UPLOAD, "security_scanner"));
        assertFalse(DocumentActions.allows(Action.RELEASE, "reception"));
        assertFalse(DocumentActions.allows(Action.FINISH_RENDER, "clinician"));
        for (Action action : Action.values()) {
            assertFalse(DocumentActions.allows(action, "unrecognized"));
        }
    }

    // --- helpers -------------------------------------------------------------

    private Artifact completedArtifact() {
        String id = quarantine.upload(patientA, pdfUpload()).id();
        quarantine.scan(scannerA, id, new ScanRequest("clean", null), 1);
        seedObject(id);
        return quarantine.complete(reviewerA, id, new CompleteRequest("ok"), 2);
    }

    private void seedObject(String artifactId) {
        Artifact artifact = store.artifact(ORG_A, artifactId).orElseThrow();
        store.putObject(ORG_A, artifact.objectKey(), artifact.objectVersion(),
                artifact.checksum(), artifact.sizeBytes());
    }

    private UploadRequest pdfUpload() {
        return upload(MediaType.PDF, "25504446", CHECKSUM);
    }

    private UploadRequest upload(MediaType type, String magic, String checksum) {
        String name = "notes." + switch (type) {
            case PDF -> "pdf";
            case JPEG -> "jpg";
            case PNG -> "png";
        };
        return new UploadRequest(CONTEXT, name, type.contentType(), magic, 128, checksum);
    }

    private static DocumentAuthorization authorization(String org, String context, String patient, boolean active) {
        return new DocumentAuthorization(org, context, patient, active);
    }

    private static SourceRevision revision(String org, int version, String checksum) {
        return new SourceRevision(org, "rev-1", version, checksum);
    }

    private static RenderFinishRequest finish(String objectKey, String checksum) {
        return new RenderFinishRequest(objectKey, 1, checksum, 128);
    }

    private static Artifact artifact(String org, String id, ArtifactState state, String patient) {
        return new Artifact(org, id, 3, "uploader", CONTEXT, patient, "notes.pdf", MediaType.PDF,
                128, CHECKSUM, "quarantine/" + org + "/" + id + "/v1", 1,
                ScanVerdict.CLEAN, state, NOW);
    }

    private static DocumentEvent fact(String id) {
        return new DocumentEvent(id, ORG_A, "resource", EventType.UPLOAD_ACCEPTED,
                "actor", "success", null, NOW);
    }

    private static void assertDenied(int status, String code, Supplier<?> action) {
        ApiException failure = assertThrows(ApiException.class, action::get);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }
}
