package com.aurevia.documents;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aurevia.documents.DocumentActions.Action;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.DocumentContract.BindRequest;
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
 * Domain service for asynchronous PDF rendering, immutable revision binding
 * and release gating (ARC-06).
 *
 * <p>A render job is requested for an exact signed revision, picked up and
 * completed by an authorized renderer with a verified private object. A PDF
 * can be bound only to that exact revision; a corrected revision needs a fresh
 * job and binding. A release that requires a PDF is refused without a decision
 * when the binding is missing, stale or backed by an unverified object.</p>
 */
@Service
public final class PdfBindingService {

    private final DocumentStore store;
    private final DocumentGuard guard;
    private final Clock clock;

    /**
     * Create the service over shared tenant persistence and collaborators.
     *
     * @param store tenant-scoped persistence boundary
     * @param guard shared authorization and audit guard
     * @param clock source of server timestamps
     */
    public PdfBindingService(DocumentStore store, DocumentGuard guard, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.clock = clock;
    }

    /**
     * Request an asynchronous render for one exact immutable revision.
     *
     * <p>Repeating the same revision, template and language returns the
     * original job, so a retried request cannot start duplicate work.</p>
     *
     * @param actor   freshly derived caller identity
     * @param request revision, approved template and language
     * @return the requested or existing render job
     * @throws ApiException on denial or invalid input
     */
    public PdfJob requestRender(Actor actor, RenderRequest request) {
        guard.authorize(actor, Action.RENDER, "RENDER");
        SourceRevision revision = validateRevision(actor, request.revision());
        String templateId = ArtifactInputs.key(request.templateId(), "templateId");
        Language language = ArtifactInputs.language(request.language());
        for (PdfJob existing : store.pdfJobs(actor.organizationId())) {
            if (sameWork(existing, revision, templateId, language)) {
                return existing;
            }
        }
        String jobId = ArtifactInputs.newId();
        PdfJob job = new PdfJob(actor.organizationId(), jobId, revision.revisionId(),
                revision.revisionVersion(), revision.contentChecksum(), templateId, language,
                PdfJobState.REQUESTED, 1, null, 0, null, 0, clock.instant());
        store.putPdfJob(job);
        guard.record(actor, jobId, EventType.RENDER_REQUESTED, "success", null);
        return job;
    }

    /**
     * Pick up a requested render job.
     *
     * @param actor           authorized renderer identity
     * @param jobId           job identifier
     * @param expectedVersion version read by the caller
     * @return the rendering job
     * @throws ApiException on denial, stale version or an illegal state
     */
    public PdfJob pickupRender(Actor actor, String jobId, int expectedVersion) {
        guard.authorize(actor, Action.PICKUP_RENDER, jobId);
        PdfJob current = loadJob(actor, jobId);
        requireVersion(actor, current, expectedVersion);
        if (current.state() != PdfJobState.REQUESTED) {
            throw guard.conflict(actor, jobId, "INVALID_RENDER_STATE", "only a requested job can be picked up");
        }
        PdfJob updated = withState(current, PdfJobState.RENDERING, current.version() + 1);
        replaceJob(actor, current, updated);
        guard.record(actor, jobId, EventType.RENDER_PICKED_UP, "success", null);
        return updated;
    }

    /**
     * Complete a render with a verified private object version.
     *
     * @param actor           authorized renderer identity
     * @param jobId           job identifier
     * @param request         verified object metadata
     * @param expectedVersion version read by the caller
     * @return the completed job
     * @throws ApiException on denial, invalid metadata, stale version or an illegal state
     */
    public PdfJob finishRender(Actor actor, String jobId, RenderFinishRequest request, int expectedVersion) {
        guard.authorize(actor, Action.FINISH_RENDER, jobId);
        PdfJob current = loadJob(actor, jobId);
        requireVersion(actor, current, expectedVersion);
        if (current.state() != PdfJobState.REQUESTED && current.state() != PdfJobState.RENDERING) {
            throw guard.conflict(actor, jobId, "INVALID_RENDER_STATE", "only an active job can be completed");
        }
        String objectKey = ArtifactInputs.verifiedObjectKey(request.objectKey(), "objectKey");
        int objectVersion = ArtifactInputs.positiveVersion(request.objectVersion(), "objectVersion");
        String checksum = ArtifactInputs.checksum(request.checksum());
        long sizeBytes = ArtifactInputs.size(request.sizeBytes());
        PdfJob updated = new PdfJob(current.organizationId(), current.jobId(), current.revisionId(),
                current.revisionVersion(), current.contentChecksum(), current.templateId(), current.language(),
                PdfJobState.COMPLETE, current.version() + 1, objectKey, objectVersion, checksum, sizeBytes,
                current.createdAt());
        store.putObject(actor.organizationId(), objectKey, objectVersion, checksum, sizeBytes);
        replaceJob(actor, current, updated);
        guard.record(actor, jobId, EventType.RENDER_COMPLETE, "success", null);
        return updated;
    }

    /**
     * Record a render failure so no PDF can be bound from the job.
     *
     * @param actor           authorized renderer identity
     * @param jobId           job identifier
     * @param detail          bounded non-sensitive failure detail
     * @param expectedVersion version read by the caller
     * @return the failed job
     * @throws ApiException on denial, stale version or an illegal state
     */
    public PdfJob failRender(Actor actor, String jobId, String detail, int expectedVersion) {
        guard.authorize(actor, Action.FAIL_RENDER, jobId);
        PdfJob current = loadJob(actor, jobId);
        requireVersion(actor, current, expectedVersion);
        if (current.state() == PdfJobState.COMPLETE || current.state() == PdfJobState.FAILED) {
            throw guard.conflict(actor, jobId, "INVALID_RENDER_STATE", "a terminal job cannot fail again");
        }
        String note = ArtifactInputs.detail(detail, 200);
        PdfJob updated = withState(current, PdfJobState.FAILED, current.version() + 1);
        replaceJob(actor, current, updated);
        guard.record(actor, jobId, EventType.RENDER_FAILED, "success", note);
        return updated;
    }

    /**
     * Bind a completed PDF to one exact immutable revision.
     *
     * @param actor   freshly derived reviewer identity
     * @param request revision selector and completed job identifier
     * @return the immutable binding
     * @throws ApiException on denial, an incomplete job, a revision mismatch,
     *                      a competing binding or stale selectors
     */
    public PdfBinding bind(Actor actor, BindRequest request) {
        guard.authorize(actor, Action.BIND, "BIND");
        SourceRevision revision = validateRevision(actor, request.revision());
        String jobId = ArtifactInputs.key(request.jobId(), "jobId");
        PdfJob job = loadJob(actor, jobId);
        if (job.state() != PdfJobState.COMPLETE) {
            throw guard.conflict(actor, jobId, "PDF_INCOMPLETE", "only a completed render can be bound");
        }
        if (job.revisionVersion() != revision.revisionVersion()
                || !job.contentChecksum().equals(revision.contentChecksum())) {
            throw guard.conflict(actor, jobId, "REVISION_MISMATCH",
                    "the render does not belong to this exact revision");
        }
        PdfBinding existing = store.pdfBinding(actor.organizationId(), revision.revisionId()).orElse(null);
        if (existing != null && sameRevision(existing, revision)) {
            if (existing.jobId().equals(jobId)) {
                return existing;
            }
            throw guard.conflict(actor, revision.revisionId(), "PDF_ALREADY_BOUND",
                    "another completed PDF is already bound to this revision");
        }
        PdfBinding binding = new PdfBinding(actor.organizationId(), revision.revisionId(),
                revision.revisionVersion(), revision.contentChecksum(), jobId, job.objectKey(),
                job.objectVersion(), job.checksum(), job.language(), clock.instant());
        store.putPdfBinding(binding);
        guard.record(actor, revision.revisionId(), EventType.PDF_BOUND, "success", null);
        return binding;
    }

    /**
     * Record a release decision when every required artifact is complete.
     *
     * @param actor   freshly derived authorized identity
     * @param request exact revision, policy version and PDF requirement
     * @return the original or newly recorded decision
     * @throws ApiException on denial, invalid input or an incomplete required PDF
     */
    public ReleaseDecision release(Actor actor, ReleaseRequest request) {
        guard.authorize(actor, Action.RELEASE, "RELEASE");
        SourceRevision revision = validateRevision(actor, request.revision());
        String policyVersion = ArtifactInputs.key(request.policyVersion(), "policyVersion");
        Optional<ReleaseDecision> prior = store.release(actor.organizationId(), revision.revisionId(),
                revision.revisionVersion(), policyVersion);
        if (prior.isPresent()) {
            return prior.get();
        }
        if (request.requirePdf() && !pdfReady(actor.organizationId(), revision)) {
            guard.record(actor, revision.revisionId(), EventType.RELEASE_BLOCKED, "conflict", null);
            throw guard.conflict(actor, revision.revisionId(), "PDF_INCOMPLETE",
                    "a required PDF is not bound to this exact revision");
        }
        ReleaseDecision decision = new ReleaseDecision(actor.organizationId(), revision.revisionId(),
                revision.revisionVersion(), revision.contentChecksum(), policyVersion,
                actor.subject(), clock.instant());
        if (!store.insertReleaseIfAbsent(decision)) {
            guard.record(actor, revision.revisionId(), EventType.RELEASE_BLOCKED, "conflict", null);
            throw guard.conflict(actor, revision.revisionId(), "RELEASE_CONFLICT",
                    "a competing release decision committed first");
        }
        guard.record(actor, revision.revisionId(), EventType.RELEASED, "success", null);
        return decision;
    }

    private SourceRevision validateRevision(Actor actor, SourceRevision revision) {
        if (!revision.organizationId().equals(actor.organizationId())) {
            throw guard.deny(actor, revision.revisionId(), "WRONG_TENANT",
                    "the revision belongs to another organization");
        }
        String revisionId = ArtifactInputs.key(revision.revisionId(), "revisionId");
        int version = ArtifactInputs.positiveVersion(revision.revisionVersion(), "revisionVersion");
        String checksum = ArtifactInputs.checksum(revision.contentChecksum());
        return new SourceRevision(actor.organizationId(), revisionId, version, checksum);
    }

    private boolean sameWork(PdfJob job, SourceRevision revision, String templateId, Language language) {
        return job.revisionId().equals(revision.revisionId())
                && job.revisionVersion() == revision.revisionVersion()
                && job.contentChecksum().equals(revision.contentChecksum())
                && job.templateId().equals(templateId)
                && job.language() == language;
    }

    private boolean sameRevision(PdfBinding binding, SourceRevision revision) {
        return binding.revisionVersion() == revision.revisionVersion()
                && binding.contentChecksum().equals(revision.contentChecksum());
    }

    private boolean pdfReady(String organizationId, SourceRevision revision) {
        PdfBinding binding = store.pdfBinding(organizationId, revision.revisionId()).orElse(null);
        return binding != null
                && binding.revisionVersion() == revision.revisionVersion()
                && binding.contentChecksum().equals(revision.contentChecksum())
                && store.verifyObject(organizationId, binding.objectKey(),
                        binding.objectVersion(), binding.checksum());
    }

    private PdfJob loadJob(Actor actor, String jobId) {
        return store.pdfJob(actor.organizationId(), ArtifactInputs.key(jobId, "jobId"))
                .orElseThrow(guard::notFound);
    }

    private void requireVersion(Actor actor, PdfJob current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw guard.stale(actor, current.jobId(), "STALE_VERSION", "the render job changed since it was read");
        }
    }

    private void replaceJob(Actor actor, PdfJob current, PdfJob updated) {
        if (!store.replacePdfJobIfVersion(actor.organizationId(), current.jobId(), current.version(), updated)) {
            throw guard.stale(actor, current.jobId(), "STALE_VERSION", "the render job changed before the write committed");
        }
    }

    private static PdfJob withState(PdfJob current, PdfJobState state, int version) {
        return new PdfJob(current.organizationId(), current.jobId(), current.revisionId(),
                current.revisionVersion(), current.contentChecksum(), current.templateId(), current.language(),
                state, version, current.objectKey(), current.objectVersion(), current.checksum(),
                current.sizeBytes(), current.createdAt());
    }
}
