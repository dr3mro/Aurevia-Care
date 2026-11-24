package com.aurevia.documents.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentGuard;
import com.aurevia.documents.InMemoryDocumentStore;
import com.aurevia.documents.PdfBindingService;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.DocumentContract.BindRequest;
import com.aurevia.documents.DocumentContract.DocumentAuthorization;
import com.aurevia.documents.DocumentContract.EventType;
import com.aurevia.documents.DocumentContract.Language;
import com.aurevia.documents.DocumentContract.PdfBinding;
import com.aurevia.documents.DocumentContract.PdfJob;
import com.aurevia.documents.DocumentContract.PdfJobState;
import com.aurevia.documents.DocumentContract.ReleaseDecision;
import com.aurevia.documents.DocumentContract.ReleaseRequest;
import com.aurevia.documents.DocumentContract.RenderFinishRequest;
import com.aurevia.documents.DocumentContract.RenderRequest;
import com.aurevia.documents.DocumentContract.SourceRevision;

/**
 * ARC-06 clause 4 acceptance at the public domain-service boundary: PDFs are
 * rendered by an asynchronous, versioned job, bound only to the exact immutable
 * revision they were rendered for, and a PDF-required release is refused until
 * that binding is backed by a verified object.
 *
 * <p>Every identifier, subject and checksum is synthetic. Only the public
 * render, bind and release commands of {@link com.aurevia.documents.PdfBindingService}
 * are exercised over a tenant-scoped in-memory store and a fixed clock, so the
 * proof needs no external infrastructure, real patient data or credentials.</p>
 */
class GeneratePdfsAsynchronouslyBindThemImmutableRevisionsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String CONTEXT = "ctx-1";
    private static final String PATIENT = "pat-001";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String OTHER_CHECKSUM = "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final Actor clinicianA = Actor.staff(ORG_A, "clinician-a", "clinician");
    private final Actor reviewerA = Actor.staff(ORG_A, "reviewer-a", "document_reviewer");
    private final Actor rendererA = Actor.staff(ORG_A, "renderer-a", "pdf_renderer");
    private final Actor scannerA = Actor.staff(ORG_A, "scanner-a", "security_scanner");

    private InMemoryDocumentStore store;
    private PdfBindingService pdf;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new InMemoryDocumentStore();
        DocumentGuard guard = new DocumentGuard(store, clock);
        pdf = new PdfBindingService(store, guard, clock);
        store.putAuthorization(new DocumentAuthorization(ORG_A, CONTEXT, PATIENT, true));
        store.putAuthorization(new DocumentAuthorization(ORG_B, CONTEXT, PATIENT, true));
    }

    @Test
    void asyncRenderBindAndReleaseWithRequiredPdfSucceeds() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        assertEquals(PdfJobState.REQUESTED, job.state());
        assertEquals(1, job.version());

        // sameWork is true: a retry of the exact same render returns the original job.
        PdfJob retry = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        assertEquals(job.jobId(), retry.jobId());
        assertEquals(1, store.pdfJobs(ORG_A).size());

        // sameWork is false: a different-language render is a distinct asynchronous job.
        PdfJob arabic = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "ar"));
        assertNotEquals(job.jobId(), arabic.jobId());

        // REQUESTED -> RENDERING -> COMPLETE advances only through authorized renderers.
        PdfJob rendering = pdf.pickupRender(rendererA, job.jobId(), 1);
        assertEquals(PdfJobState.RENDERING, rendering.state());
        assertEquals(2, rendering.version());
        PdfJob complete = pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 2);
        assertEquals(PdfJobState.COMPLETE, complete.state());
        assertEquals(3, complete.version());

        // The completed job binds only to the exact immutable revision it was rendered for.
        PdfBinding binding = pdf.bind(reviewerA, new BindRequest(revision, job.jobId()));
        assertEquals(job.jobId(), binding.jobId());
        assertEquals(1, binding.revisionVersion());
        assertEquals(CHECKSUM, binding.contentChecksum());
        assertEquals("pdf/object", binding.objectKey());
        assertEquals(1, binding.objectVersion());
        assertEquals(Language.EN, binding.language());
        assertEquals(NOW, binding.boundAt());
        assertEquals(ORG_A, binding.organizationId());
        assertEquals(binding, pdf.bind(reviewerA, new BindRequest(revision, job.jobId())));

        // pdfReady is true: a PDF-required release succeeds and is idempotent.
        ReleaseDecision decision = pdf.release(clinicianA, new ReleaseRequest(revision, "policy-1", true));
        assertEquals("policy-1", decision.policyVersion());
        assertEquals("clinician-a", decision.releasedBy());
        assertEquals(NOW, decision.releasedAt());
        assertEquals(decision, pdf.release(clinicianA, new ReleaseRequest(revision, "policy-1", true)));

        assertTrue(store.events(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.RENDER_REQUESTED));
        assertTrue(store.events(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.RENDER_PICKED_UP));
        assertTrue(store.events(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.RENDER_COMPLETE));
        assertTrue(store.events(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.PDF_BOUND));
        assertTrue(store.events(ORG_A).stream().anyMatch(event -> event.eventType() == EventType.RELEASED));
    }

    @Test
    void renderIsRefusedWithoutThePdfRendererRole() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));

        assertDenied(403, "FORBIDDEN",
                () -> pdf.requestRender(scannerA, new RenderRequest(revision, "soap-en", "en")));
        assertDenied(403, "FORBIDDEN", () -> pdf.pickupRender(scannerA, job.jobId(), 1));
        assertDenied(403, "FORBIDDEN", () -> pdf.finishRender(scannerA, job.jobId(),
                finish("pdf/object", CHECKSUM), 1));
        assertDenied(403, "FORBIDDEN", () -> pdf.bind(scannerA, new BindRequest(revision, job.jobId())));
        assertDenied(403, "FORBIDDEN", () -> pdf.release(scannerA,
                new ReleaseRequest(revision, "policy-1", true)));

        assertEquals(PdfJobState.REQUESTED, store.pdfJob(ORG_A, job.jobId()).orElseThrow().state());
    }

    @Test
    void foreignRevisionAndForeignJobAreRefused() {
        assertDenied(403, "WRONG_TENANT", () -> pdf.requestRender(clinicianA,
                new RenderRequest(revision(ORG_B, 1, CHECKSUM), "soap-en", "en")));

        PdfJob foreignJob = pdf.requestRender(Actor.staff(ORG_B, "clinician-b", "clinician"),
                new RenderRequest(revision(ORG_B, 1, CHECKSUM), "soap-en", "en"));
        assertDenied(404, "NOT_FOUND", () -> pdf.bind(reviewerA,
                new BindRequest(revision(ORG_A, 1, CHECKSUM), foreignJob.jobId())));
        assertDenied(404, "NOT_FOUND", () -> pdf.pickupRender(rendererA, foreignJob.jobId(), 1));

        // Concealment: notFound() records no fact, so the foreign job id never
        // appears in this tenant's audit ledger and cannot be probed for existence.
        assertTrue(store.events(ORG_A).stream()
                .noneMatch(event -> event.resourceId().equals(foreignJob.jobId())));
    }

    @Test
    void staleJobVersionIsRefused() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));

        assertDenied(412, "STALE_VERSION", () -> pdf.finishRender(rendererA, job.jobId(),
                finish("pdf/object", CHECKSUM), 2));
        assertDenied(412, "STALE_VERSION", () -> pdf.pickupRender(rendererA, job.jobId(), 2));

        pdf.pickupRender(rendererA, job.jobId(), 1);
        pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 2);
        assertDenied(409, "INVALID_RENDER_STATE", () -> pdf.finishRender(rendererA, job.jobId(),
                finish("pdf/object", CHECKSUM), 3));
    }

    @Test
    void incompleteOrUnverifiedPdfCannotBeBoundOrReleased() {
        SourceRevision revision = revision(ORG_A, 1, CHECKSUM);

        // No binding at all: a PDF-required release is refused.
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(revision, "policy-1", true)));
        assertTrue(store.releases(ORG_A).isEmpty());

        // A requested (incomplete) job cannot be bound yet.
        PdfJob job = pdf.requestRender(clinicianA, new RenderRequest(revision, "soap-en", "en"));
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.bind(reviewerA, new BindRequest(revision, job.jobId())));
        pdf.finishRender(rendererA, job.jobId(), finish("pdf/object", CHECKSUM), 1);
        pdf.bind(reviewerA, new BindRequest(revision, job.jobId()));

        // The binding exists but the backing object disappears: release stays refused.
        store.removeObject(ORG_A, "pdf/object", 1);
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(revision, "policy-1", true)));
        assertTrue(store.releases(ORG_A).isEmpty());
    }

    @Test
    void aCorrectedRevisionCannotReuseTheBindingOrASecondJob() {
        SourceRevision original = revision(ORG_A, 1, CHECKSUM);
        PdfJob firstJob = pdf.requestRender(clinicianA, new RenderRequest(original, "soap-en", "en"));
        pdf.finishRender(rendererA, firstJob.jobId(), finish("pdf/object", CHECKSUM), 1);
        pdf.bind(reviewerA, new BindRequest(original, firstJob.jobId()));

        // No prior decision exists at this (revision, version, policy) key, so the
        // denial below can only come from the PDF-readiness gate, not a cached release.
        assertTrue(store.releases(ORG_A).isEmpty());

        // Corrected content at the same version cannot reuse the original job's binding.
        SourceRevision corrected = new SourceRevision(ORG_A, "rev-1", 1, OTHER_CHECKSUM);
        assertDenied(409, "REVISION_MISMATCH", () -> pdf.bind(reviewerA,
                new BindRequest(corrected, firstJob.jobId())));
        assertDenied(409, "PDF_INCOMPLETE", () -> pdf.release(clinicianA,
                new ReleaseRequest(corrected, "policy-1", true)));

        // A second completed job for the same immutable revision cannot replace the binding.
        PdfJob secondJob = pdf.requestRender(clinicianA, new RenderRequest(original, "soap-ar", "ar"));
        assertNotEquals(firstJob.jobId(), secondJob.jobId());
        pdf.finishRender(rendererA, secondJob.jobId(), finish("pdf/object-ar", OTHER_CHECKSUM), 1);
        assertDenied(409, "PDF_ALREADY_BOUND", () -> pdf.bind(reviewerA,
                new BindRequest(original, secondJob.jobId())));

        // A corrected revision needs its own completed job and fresh binding before release.
        PdfJob correctedJob = pdf.requestRender(clinicianA, new RenderRequest(corrected, "soap-en", "en"));
        assertNotEquals(firstJob.jobId(), correctedJob.jobId());
        pdf.finishRender(rendererA, correctedJob.jobId(), finish("pdf/object-corrected", OTHER_CHECKSUM), 1);
        PdfBinding fresh = pdf.bind(reviewerA, new BindRequest(corrected, correctedJob.jobId()));
        assertEquals(OTHER_CHECKSUM, fresh.contentChecksum());
        ReleaseDecision correctedDecision = pdf.release(clinicianA,
                new ReleaseRequest(corrected, "policy-1", true));
        assertNotNull(correctedDecision);
        assertEquals(OTHER_CHECKSUM, correctedDecision.contentChecksum());
        assertEquals(1, store.releases(ORG_A).size());
    }

    private static SourceRevision revision(String organizationId, int version, String checksum) {
        return new SourceRevision(organizationId, "rev-1", version, checksum);
    }

    private static RenderFinishRequest finish(String objectKey, String checksum) {
        return new RenderFinishRequest(objectKey, 1, checksum, 128);
    }

    private static void assertDenied(int status, String code, Supplier<?> action) {
        ApiException failure = assertThrows(ApiException.class, action::get);
        assertEquals(status, failure.status());
        assertEquals(code, failure.code());
    }
}
