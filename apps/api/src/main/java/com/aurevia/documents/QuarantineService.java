package com.aurevia.documents;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aurevia.documents.DocumentActions.Action;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.ArtifactState;
import com.aurevia.documents.DocumentContract.CompleteRequest;
import com.aurevia.documents.DocumentContract.DocumentAuthorization;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.EventType;
import com.aurevia.documents.DocumentContract.MediatedDownload;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.documents.DocumentContract.RemoveRequest;
import com.aurevia.documents.DocumentContract.ScanRequest;
import com.aurevia.documents.DocumentContract.ScanVerdict;
import com.aurevia.documents.DocumentContract.UploadRequest;
import com.aurevia.documents.DocumentContract.View;

/**
 * Domain service for X-03 upload validation, private quarantine and mediated
 * download (ARC-06).
 *
 * <p>An upload is accepted only after its content type, leading bytes, file
 * name, size and checksum all validate. Patient and context bindings come from
 * a server-side authorization that is re-checked on every command. An artifact
 * becomes downloadable only after an independent clean verdict, an authorized
 * completion and a verified object version, and is then returned as a metadata
 * handle with no bytes, original name or URL.</p>
 */
@Service
public final class QuarantineService {

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
    public QuarantineService(DocumentStore store, DocumentGuard guard, Clock clock) {
        this.store = store;
        this.guard = guard;
        this.clock = clock;
    }

    /**
     * Accept a validated payload into private quarantine.
     *
     * @param actor   freshly derived caller identity
     * @param request upload command carrying content metadata only
     * @return the quarantined artifact
     * @throws ApiException on invalid content, an unknown or inactive context,
     *                      a stale binding or a forbidden actor
     */
    public Artifact upload(Actor actor, UploadRequest request) {
        guard.authorize(actor, Action.UPLOAD, request.contextId());
        String contextId = ArtifactInputs.key(request.contextId(), "contextId");
        DocumentAuthorization grant = store.authorization(actor.organizationId(), contextId)
                .orElseThrow(guard::notFound);
        if (!grant.active()) {
            throw guard.deny(actor, contextId, "INACTIVE_CONTEXT", "the upload context is no longer active");
        }
        if (!grant.organizationId().equals(actor.organizationId())) {
            throw guard.deny(actor, contextId, "WRONG_TENANT", "the grant belongs to another organization");
        }
        if (!boundToContext(actor, grant)) {
            throw guard.deny(actor, contextId, "WRONG_OWNER", "the actor is not bound to this patient context");
        }
        MediaType type = ArtifactInputs.contentType(request.contentType());
        String magic = ArtifactInputs.magicHex(request.magicHex());
        if (!type.matchesMagic(magic)) {
            throw guard.invalid(actor, contextId, "CONTENT_MISMATCH", "payload bytes do not match the declared type");
        }
        String fileName = ArtifactInputs.fileName(request.fileName());
        if (!type.matchesExtension(fileName)) {
            throw guard.invalid(actor, contextId, "EXTENSION_MISMATCH", "file extension does not match the declared type");
        }
        long size = ArtifactInputs.size(request.sizeBytes());
        String checksum = ArtifactInputs.checksum(request.checksum());
        String id = ArtifactInputs.newId();
        Artifact created = new Artifact(actor.organizationId(), id, 1, actor.subject(), contextId,
                grant.patientId(), fileName, type, size, checksum,
                ArtifactInputs.objectKey(actor.organizationId(), id, 1), 1,
                ScanVerdict.PENDING, ArtifactState.QUARANTINED, clock.instant());
        store.putArtifact(created);
        guard.record(actor, id, EventType.UPLOAD_ACCEPTED, "success", null);
        return created;
    }

    /**
     * Record an independent quarantine scan verdict.
     *
     * @param actor           freshly derived scanner identity
     * @param id              artifact identifier
     * @param request         verdict command
     * @param expectedVersion version read by the caller
     * @return the artifact with its recorded verdict
     * @throws ApiException on denial, stale version, unknown artifact or an illegal state
     */
    public Artifact scan(Actor actor, String id, ScanRequest request, int expectedVersion) {
        guard.authorize(actor, Action.SCAN, id);
        Artifact current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        if (current.state() != ArtifactState.QUARANTINED) {
            throw guard.conflict(actor, id, "INVALID_SCAN_STATE", "only a quarantined file can be scanned");
        }
        ScanVerdict verdict = ArtifactInputs.verdict(request.verdict());
        String detail = ArtifactInputs.detail(request.detail(), 200);
        ArtifactState next = verdict == ScanVerdict.CLEAN ? ArtifactState.CLEAN : ArtifactState.FAILED;
        Artifact updated = withState(current, next, verdict, current.version() + 1);
        replace(actor, current, updated);
        guard.record(actor, id, verdict == ScanVerdict.CLEAN
                ? EventType.SCAN_CLEAN : EventType.SCAN_FAILED, "success", detail);
        return updated;
    }

    /**
     * Authoritatively complete a clean artifact whose private object is verified.
     *
     * @param actor           freshly derived reviewer identity
     * @param id              artifact identifier
     * @param request         completion command
     * @param expectedVersion version read by the caller
     * @return the completed artifact
     * @throws ApiException on denial, stale version, an unverified object or an illegal state
     */
    public Artifact complete(Actor actor, String id, CompleteRequest request, int expectedVersion) {
        guard.authorize(actor, Action.COMPLETE, id);
        Artifact current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        if (current.state() != ArtifactState.CLEAN) {
            throw guard.conflict(actor, id, "NOT_CLEAN", "only a clean artifact can be completed");
        }
        String note = ArtifactInputs.detail(request.note(), 200);
        verifyObject(actor, current);
        Artifact updated = withState(current, ArtifactState.COMPLETED, current.verdict(), current.version() + 1);
        replace(actor, current, updated);
        guard.record(actor, id, EventType.UPLOAD_COMPLETED, "success", note);
        return updated;
    }

    /**
     * Remove a draft or failed artifact so the uploader can replace it.
     *
     * @param actor           freshly derived uploader identity
     * @param id              artifact identifier
     * @param request         removal command
     * @param expectedVersion version read by the caller
     * @return the removed artifact
     * @throws ApiException on denial, stale version or a terminal state
     */
    public Artifact remove(Actor actor, String id, RemoveRequest request, int expectedVersion) {
        guard.authorize(actor, Action.REMOVE, id);
        Artifact current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        if (!current.uploaderSubject().equals(actor.subject())) {
            throw guard.deny(actor, id, "NOT_UPLOADER", "only the uploader may remove this file");
        }
        if (current.state() == ArtifactState.COMPLETED || current.state() == ArtifactState.REMOVED) {
            throw guard.conflict(actor, id, "INVALID_REMOVE_STATE", "a completed or removed file cannot be removed");
        }
        String reason = ArtifactInputs.detail(request.reason(), 200);
        Artifact updated = withState(current, ArtifactState.REMOVED, current.verdict(), current.version() + 1);
        replace(actor, current, updated);
        guard.record(actor, id, EventType.ARTIFACT_REMOVED, "success", reason);
        return updated;
    }

    /**
     * Obtain a mediated handle for a completed, clean, authorized artifact.
     *
     * @param actor           freshly derived caller identity
     * @param id              artifact identifier
     * @param expectedVersion version read by the caller
     * @return a metadata-only handle with no payload, name or URL
     * @throws ApiException on denial, stale version, an unverified object or an incomplete artifact
     */
    public MediatedDownload download(Actor actor, String id, int expectedVersion) {
        guard.authorize(actor, Action.DOWNLOAD, id);
        Artifact current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        if (!current.state().downloadable()) {
            throw guard.conflict(actor, id, "NOT_COMPLETED", "the artifact is not available for download");
        }
        if (!participant(actor, current)) {
            throw guard.deny(actor, id, "WRONG_OWNER", "the actor cannot access this artifact");
        }
        verifyObject(actor, current);
        guard.record(actor, id, EventType.DOWNLOADED, "success", null);
        return new MediatedDownload(current.objectKey(), current.objectVersion(), current.checksum(),
                current.fileType().contentType(), current.sizeBytes());
    }

    /**
     * Read one artifact view with its complete fact trail.
     *
     * @param actor freshly derived caller identity
     * @param id    artifact identifier
     * @return the view when authorized and the artifact is visible
     * @throws ApiException on denial or concealment
     */
    public View view(Actor actor, String id) {
        guard.authorize(actor, Action.READ, id);
        Artifact current = load(actor, id);
        if (!visible(actor, current)) {
            throw guard.notFound();
        }
        List<DocumentEvent> trail = store.events(actor.organizationId()).stream()
                .filter(fact -> id.equals(fact.resourceId()))
                .toList();
        return new View(current, trail);
    }

    private Artifact load(Actor actor, String id) {
        return store.artifact(actor.organizationId(), ArtifactInputs.key(id, "id"))
                .orElseThrow(guard::notFound);
    }

    private boolean boundToContext(Actor actor, DocumentAuthorization grant) {
        if (actor.patientId() != null) {
            return actor.patientId().equals(grant.patientId());
        }
        return true;
    }

    private boolean participant(Actor actor, Artifact artifact) {
        if (actor.patientId() != null) {
            return actor.patientId().equals(artifact.patientId());
        }
        return true;
    }

    private boolean visible(Actor actor, Artifact artifact) {
        if (artifact.state().downloadable()) {
            return participant(actor, artifact);
        }
        return actor.subject().equals(artifact.uploaderSubject());
    }

    private void verifyObject(Actor actor, Artifact artifact) {
        if (!store.verifyObject(actor.organizationId(), artifact.objectKey(),
                artifact.objectVersion(), artifact.checksum())) {
            throw guard.conflict(actor, artifact.id(), "OBJECT_UNVERIFIED",
                    "the private object version or checksum did not verify");
        }
    }

    private void requireVersion(Actor actor, Artifact current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw guard.stale(actor, current.id(), "STALE_VERSION", "the artifact changed since it was read");
        }
    }

    private void replace(Actor actor, Artifact current, Artifact updated) {
        if (!store.replaceArtifactIfVersion(actor.organizationId(), current.id(), current.version(), updated)) {
            throw guard.stale(actor, current.id(), "STALE_VERSION", "the artifact changed before the write committed");
        }
    }

    private static Artifact withState(Artifact current, ArtifactState state, ScanVerdict verdict, int version) {
        return new Artifact(current.organizationId(), current.id(), version, current.uploaderSubject(),
                current.contextId(), current.patientId(), current.fileName(), current.fileType(),
                current.sizeBytes(), current.checksum(), current.objectKey(), current.objectVersion(),
                verdict, state, current.createdAt());
    }
}
