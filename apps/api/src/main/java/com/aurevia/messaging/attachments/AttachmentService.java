package com.aurevia.messaging.attachments;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentState;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.EventType;
import com.aurevia.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.messaging.attachments.AttachmentContract.FileRequest;
import com.aurevia.messaging.attachments.AttachmentContract.MediatedDownload;
import com.aurevia.messaging.attachments.AttachmentContract.PublishRequest;
import com.aurevia.messaging.attachments.AttachmentContract.RemoveRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.messaging.attachments.AttachmentContract.View;

/**
 * Domain service for message attachments.
 *
 * <p>An upload is accepted into private quarantine only after its declared
 * content type, leading bytes, size and checksum all validate. The unit and
 * patient binding come from a server-side verified connection grant, never
 * from the upload command, and the fresh caller snapshot is re-checked on
 * every command. A payload becomes downloadable by another participant only after
 * an independent clean verdict and explicit publication with a validated message
 * identifier; the uploader can still view its own quarantined draft.
 * Message existence is not checked in this slice.</p>
 *
 * <p>The integrating layer must rebuild the {@code Actor} from active
 * membership and verified link state on each protected request. Refusals raised
 * with an actor are recorded as append-only facts; concealed or missing resources
 * are not. File names never enter a fact; no payload bytes or URLs are returned,
 * and only the private object key is exposed for retrieval through the object
 * boundary.</p>
 */
@Service
public final class AttachmentService {

    private final AttachmentStore store;
    private final Clock clock;

    /**
     * Create the service over a tenant-scoped store and an injectable clock.
     *
     * @param store tenant-scoped persistence boundary
     * @param clock source of server timestamps
     */
    public AttachmentService(AttachmentStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Accept a validated payload into private quarantine.
     *
     * @param actor   freshly derived caller identity
     * @param request upload command carrying only content metadata and a connection selector
     * @return the quarantined attachment
     * @throws ApiException on invalid content, an unknown or inactive connection,
     *                      wrong-unit access, stale authorization or duplicate input
     */
    public Attachment upload(Actor actor, UploadRequest request) {
        authorize(actor, AttachmentActions.UPLOAD, request.connectionId());
        String connectionId = AttachmentInputs.key(request.connectionId(), "connectionId");
        ConnectionGrant grant = store.grant(actor.organizationId(), connectionId).orElseThrow(notFound());
        if (!grant.active()) {
            throw deny(actor, connectionId, "INACTIVE_CONNECTION", "the connection no longer permits messaging");
        }
        if (!grant.organizationId().equals(actor.organizationId())
                || !boundToConnection(actor, grant)) {
            throw deny(actor, connectionId, "WRONG_UNIT", "the actor is not bound to this verified connection");
        }
        FileType type = AttachmentInputs.contentType(request.contentType());
        String magic = AttachmentInputs.magicHex(request.magicHex());
        if (!type.matchesMagic(magic)) {
            throw invalid(actor, connectionId, "CONTENT_MISMATCH", "payload bytes do not match the declared type");
        }
        String fileName = AttachmentInputs.fileName(request.fileName());
        if (!type.matchesExtension(fileName)) {
            throw invalid(actor, connectionId, "EXTENSION_MISMATCH", "file extension does not match the declared type");
        }
        long size = AttachmentInputs.size(request.sizeBytes());
        String checksum = AttachmentInputs.checksum(request.checksum());
        String id = AttachmentInputs.newId();
        Attachment created = new Attachment(actor.organizationId(), id, 1, actor.subject(), connectionId,
                grant.unitId(), grant.patientId(), fileName, type, size, checksum,
                AttachmentInputs.objectKey(actor.organizationId(), id, 1), 1,
                ScanVerdict.PENDING, AttachmentState.QUARANTINED, null, null, clock.instant());
        store.putAttachment(created);
        event(actor, id, EventType.UPLOADED, "success", null);
        return created;
    }

    /**
     * Record an independent quarantine scan verdict.
     *
     * @param actor           freshly derived scanner identity
     * @param id              attachment identifier
     * @param request         verdict command
     * @param expectedVersion version read by the caller
     * @return the attachment with its recorded verdict
     * @throws ApiException on denial, stale version, unknown attachment or an illegal state
     */
    public Attachment scan(Actor actor, String id, ScanRequest request, int expectedVersion) {
        authorize(actor, AttachmentActions.SCAN, id);
        Attachment current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        if (current.state() != AttachmentState.QUARANTINED && current.state() != AttachmentState.CLEAN) {
            throw conflict(actor, current.id(), "INVALID_SCAN_STATE", "only a pending or clean file can be scanned");
        }
        ScanVerdict verdict = AttachmentInputs.verdict(request.verdict());
        String detail = AttachmentInputs.detail(request.detail(), 200);
        AttachmentState next = verdict == ScanVerdict.CLEAN
                ? AttachmentState.CLEAN : AttachmentState.FAILED;
        Attachment updated = withState(current, next, verdict, null, null, current.version() + 1);
        store.putAttachment(updated);
        event(actor, current.id(), verdict == ScanVerdict.CLEAN
                ? EventType.SCAN_CLEAN : EventType.SCAN_FAILED, "success", detail);
        return updated;
    }

    /**
     * Explicitly publish named clean files to one message and recipient unit.
     *
     * <p>Every file is validated before the first one is changed, so a batch
     * with a single invalid member publishes nothing.</p>
     *
     * @param actor   freshly derived sender identity
     * @param request identifiers of the pending files, message and recipient unit
     * @return the published attachments in request order
     * @throws ApiException on empty or duplicate input, an invalid file, wrong unit or stale state
     */
    public List<Attachment> publish(Actor actor, PublishRequest request) {
        authorize(actor, AttachmentActions.PUBLISH, "BATCH");
        List<String> ids = request.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            throw invalid(actor, "BATCH", "EMPTY_ATTACHMENTS", "at least one file id is required");
        }
        if (ids.stream().distinct().count() != ids.size()) {
            throw invalid(actor, "BATCH", "DUPLICATE_ATTACHMENTS", "file ids must be distinct");
        }
        String messageId = AttachmentInputs.key(request.messageId(), "messageId");
        String recipientUnitId = AttachmentInputs.key(request.recipientUnitId(), "recipientUnitId");

        List<Attachment> pending = new ArrayList<>();
        for (String candidate : ids) {
            Attachment current = load(actor, candidate);
            if (current.state() != AttachmentState.CLEAN) {
                throw conflict(actor, current.id(), "NOT_CLEAN", "only a clean file can be published");
            }
            if (!current.uploaderSubject().equals(actor.subject())) {
                throw deny(actor, current.id(), "NOT_UPLOADER", "only the uploader may publish this file");
            }
            if (!recipientUnitId.equals(current.unitId())) {
                throw deny(actor, current.id(), "WRONG_UNIT", "recipient unit does not match the bound unit");
            }
            if (!participant(actor, current)) {
                throw deny(actor, current.id(), "WRONG_UNIT", "the actor cannot send to this connection");
            }
            pending.add(current);
        }

        List<Attachment> published = new ArrayList<>();
        for (Attachment current : pending) {
            Attachment updated = withState(current, AttachmentState.PUBLISHED, current.verdict(),
                    messageId, recipientUnitId, current.version() + 1);
            store.putAttachment(updated);
            event(actor, current.id(), EventType.PUBLISHED, "success", null);
            published.add(updated);
        }
        return List.copyOf(published);
    }

    /**
     * Remove a draft or failed attachment so the sender can replace it.
     *
     * @param actor           freshly derived uploader identity
     * @param id              attachment identifier
     * @param request         removal command
     * @param expectedVersion version read by the caller
     * @return the removed attachment
     * @throws ApiException on denial, stale version, unknown attachment or a published target
     */
    public Attachment remove(Actor actor, String id, RemoveRequest request, int expectedVersion) {
        authorize(actor, AttachmentActions.REMOVE, id);
        Attachment current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        if (!current.uploaderSubject().equals(actor.subject())) {
            throw deny(actor, current.id(), "NOT_UPLOADER", "only the uploader may remove this file");
        }
        if (current.state() == AttachmentState.PUBLISHED || current.state() == AttachmentState.REMOVED) {
            throw conflict(actor, current.id(), "INVALID_REMOVE_STATE", "a published or removed file cannot be removed");
        }
        String reason = AttachmentInputs.detail(request.reason(), 200);
        Attachment updated = withState(current, AttachmentState.REMOVED, current.verdict(),
                current.messageId(), current.recipientUnitId(), current.version() + 1);
        store.putAttachment(updated);
        event(actor, current.id(), EventType.REMOVED, "success", reason);
        return updated;
    }

    /**
     * Obtain a mediated handle for a published attachment.
     *
     * @param actor           freshly derived recipient identity
     * @param id              attachment identifier
     * @param expectedVersion version read by the caller
     * @return an object handle with no bytes, file name or URL
     * @throws ApiException on denial, stale version, unknown attachment or an unpublished target
     */
    public MediatedDownload download(Actor actor, String id, int expectedVersion) {
        authorize(actor, AttachmentActions.DOWNLOAD, id);
        Attachment current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        if (!current.state().downloadable()) {
            throw conflict(actor, current.id(), "NOT_PUBLISHED", "the file is not available for download");
        }
        if (!participant(actor, current)) {
            throw deny(actor, current.id(), "WRONG_UNIT", "the actor cannot access this conversation");
        }
        event(actor, current.id(), EventType.DOWNLOADED, "success", null);
        return new MediatedDownload(current.objectKey(), current.objectVersion(), current.checksum(),
                current.fileType().contentType(), current.sizeBytes());
    }

    /**
     * File published patient-supplied material in the chart as an authorized reviewer.
     *
     * @param actor           freshly derived clinician identity
     * @param id              attachment identifier
     * @param request         filing command
     * @param expectedVersion version read by the caller
     * @return the unchanged attachment
     * @throws ApiException on denial, stale version, unknown or unpublished attachment
     */
    public Attachment fileIntoChart(Actor actor, String id, FileRequest request, int expectedVersion) {
        authorize(actor, AttachmentActions.FILE, id);
        Attachment current = load(actor, id);
        requireVersion(actor, current, expectedVersion);
        AttachmentInputs.key(request.encounterId(), "encounterId");
        AttachmentInputs.detail(request.note(), 200);
        if (current.state() != AttachmentState.PUBLISHED) {
            throw conflict(actor, current.id(), "NOT_PUBLISHED", "only published material can be filed");
        }
        if (actor.unitId() == null || !actor.unitId().equals(current.unitId())) {
            throw deny(actor, current.id(), "WRONG_UNIT", "the clinician is not assigned to the receiving unit");
        }
        event(actor, current.id(), EventType.FILED_TO_CHART, "success", null);
        return current;
    }

    /**
     * Read one attachment view with its complete fact trail.
     *
     * @param actor freshly derived caller identity
     * @param id    attachment identifier
     * @return the view when the caller is authorized and the file is visible
     * @throws ApiException on denial or concealment
     */
    public View view(Actor actor, String id) {
        authorize(actor, AttachmentActions.READ, id);
        Attachment current = load(actor, id);
        if (!visible(actor, current)) {
            throw notFound().get();
        }
        String fileId = current.id();
        List<AttachmentEvent> trail = store.events(actor.organizationId()).stream()
                .filter(fact -> fileId.equals(fact.attachmentId()))
                .toList();
        return new View(current, trail);
    }

    /**
     * Read the append-only fact trail of one visible attachment.
     *
     * @param actor freshly derived caller identity
     * @param id    attachment identifier
     * @return the ordered facts
     * @throws ApiException on denial or concealment
     */
    public List<AttachmentEvent> events(Actor actor, String id) {
        authorize(actor, AttachmentActions.READ, id);
        Attachment current = load(actor, id);
        if (!visible(actor, current)) {
            throw notFound().get();
        }
        String fileId = current.id();
        return store.events(actor.organizationId()).stream()
                .filter(fact -> fileId.equals(fact.attachmentId()))
                .toList();
    }

    /**
     * List published material that entered one care unit's inbox.
     *
     * @param actor  freshly derived unit staff identity
     * @param unitId unit whose inbox is requested
     * @return published attachments bound to that unit
     * @throws ApiException when a patient calls it or the unit does not match the actor
     */
    public List<Attachment> inbox(Actor actor, String unitId) {
        authorize(actor, AttachmentActions.READ, "INBOX");
        if (actor.patientId() != null) {
            throw deny(actor, "INBOX", "PATIENT_INBOX_UNAVAILABLE", "patients use the patient message list");
        }
        String requested = AttachmentInputs.key(unitId, "unitId");
        if (actor.unitId() == null || !actor.unitId().equals(requested)) {
            throw deny(actor, "INBOX", "WRONG_UNIT", "the actor is not assigned to the requested unit");
        }
        return store.attachments(actor.organizationId()).stream()
                .filter(attachment -> attachment.state() == AttachmentState.PUBLISHED)
                .filter(attachment -> requested.equals(attachment.unitId()))
                .toList();
    }

    private Attachment load(Actor actor, String id) {
        return store.attachment(actor.organizationId(), AttachmentInputs.key(id, "id"))
                .orElseThrow(notFound());
    }

    private boolean participant(Actor actor, Attachment attachment) {
        if (actor.patientId() != null) {
            return actor.patientId().equals(attachment.patientId());
        }
        return actor.unitId() != null && actor.unitId().equals(attachment.unitId());
    }

    private boolean visible(Actor actor, Attachment attachment) {
        if (attachment.state().downloadable()) {
            return participant(actor, attachment);
        }
        return actor.subject().equals(attachment.uploaderSubject());
    }

    private boolean boundToConnection(Actor actor, ConnectionGrant grant) {
        if (actor.patientId() != null) {
            return actor.patientId().equals(grant.patientId());
        }
        return actor.unitId() != null && actor.unitId().equals(grant.unitId());
    }

    private static Attachment withState(Attachment current, AttachmentState state, ScanVerdict verdict,
                                        String messageId, String recipientUnitId, int version) {
        return new Attachment(current.organizationId(), current.id(), version, current.uploaderSubject(),
                current.connectionId(), current.unitId(), current.patientId(), current.fileName(),
                current.fileType(), current.sizeBytes(), current.checksum(), current.objectKey(),
                current.objectVersion(), verdict, state, messageId, recipientUnitId, current.createdAt());
    }

    private void requireVersion(Actor actor, Attachment current, int expectedVersion) {
        if (current.version() != expectedVersion) {
            throw conflict(actor, current.id(), "STALE_VERSION", "the attachment changed since it was read");
        }
    }

    private void authorize(Actor actor, String action, String resourceId) {
        if (actor == null || actor.roles().stream()
                .noneMatch(role -> AttachmentActions.allows(action, role))) {
            if (actor != null) {
                event(actor, resourceId, EventType.ACCESS_DENIED, "denied", action);
            }
            throw new ApiException(403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private ApiException deny(Actor actor, String resourceId, String code, String message) {
        event(actor, resourceId, EventType.ACCESS_DENIED, "denied", code);
        return new ApiException(403, code, message);
    }

    private ApiException conflict(Actor actor, String resourceId, String code, String message) {
        event(actor, resourceId, EventType.ACCESS_DENIED, "conflict", code);
        return new ApiException(409, code, message);
    }

    private ApiException invalid(Actor actor, String resourceId, String code, String message) {
        event(actor, resourceId, EventType.ACCESS_DENIED, "invalid", code);
        return new ApiException(422, code, message);
    }

    private Supplier<ApiException> notFound() {
        return () -> new ApiException(404, "NOT_FOUND", "attachment not found");
    }

    private void event(Actor actor, String attachmentId, EventType type, String outcome, String detail) {
        store.appendEvent(new AttachmentEvent(AttachmentInputs.newId(), actor.organizationId(), attachmentId,
                type, actor.subject(), outcome, detail, clock.instant()));
    }
}
