package com.aurevia.smartclinic.staffmsg.attachments;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.documents.DocumentContract.MediaType;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AttachmentAudit;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.AuditType;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.DownloadedFile;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.ScanRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.SendRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffActor;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.UploadRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.View;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ScanState;

/**
 * Domain service for staff-message attachments (MSG-03, CLN-07).
 *
 * <p>An upload is accepted only after the declared media type, the leading
 * payload bytes, the file extension, the size and the client integrity claim all
 * agree, and is bound to the uploader and a chosen conversation or mail draft.
 * A file stays in private quarantine until an independent clean verdict; a
 * quarantined file can never be sent. Publication requires an explicit send that
 * names the pending files and their recipients. Download is authorized per
 * recipient, verifies the original bytes against the stored checksum and records
 * an append-only audit fact. No payload bytes or object URLs enter an audit.</p>
 */
@Service
public final class StaffAttachmentService {

    private final StaffAttachmentStore store;
    private final Clock clock;
    private final StaffAttachmentLimits limits;

    /**
     * Create the service over a tenant-scoped store, a clock and configured bounds.
     *
     * @param store  tenant-scoped persistence boundary
     * @param clock  source of server timestamps
     * @param limits configured size and count limits
     */
    public StaffAttachmentService(StaffAttachmentStore store, Clock clock, StaffAttachmentLimits limits) {
        this.store = store;
        this.clock = clock;
        this.limits = limits;
    }

    /**
     * Accept a validated payload into private quarantine bound to one draft.
     *
     * @param actor   freshly derived uploader identity
     * @param request upload command carrying a draft selector and original bytes
     * @return the quarantined attachment
     * @throws ApiException on an invalid file, a full draft, a forbidden actor or a stale claim
     */
    public StaffAttachment upload(StaffActor actor, UploadRequest request) {
        String draftId = StaffAttachmentInputs.key(request.draftId(), "draftId");
        authorize(actor, StaffAttachmentActions.UPLOAD, draftId);
        if (store.countForDraft(actor.organizationId(), draftId) >= limits.maxFilesPerDraft()) {
            throw invalid(actor, draftId, "FILE_LIMIT_REACHED", "the draft already holds the maximum number of files");
        }
        byte[] content = request.content();
        if (content == null || content.length == 0) {
            throw invalid(actor, draftId, "INVALID_CONTENT", "the payload is empty");
        }
        long size = StaffAttachmentInputs.size(content.length, limits.maxSizeBytes());
        MediaType type = StaffAttachmentInputs.contentType(request.contentType());
        String magic = StaffAttachmentInputs.magicHex(content);
        if (!type.matchesMagic(magic)) {
            throw invalid(actor, draftId, "CONTENT_MISMATCH", "payload bytes do not match the declared type");
        }
        String fileName = StaffAttachmentInputs.fileName(request.fileName());
        if (!type.matchesExtension(fileName)) {
            throw invalid(actor, draftId, "EXTENSION_MISMATCH", "file extension does not match the declared type");
        }
        String checksum = StaffAttachmentInputs.checksum(content);
        StaffAttachmentInputs.requireMatchingChecksum(request.checksum(), checksum);

        String id = StaffAttachmentInputs.newId();
        String documentId = StaffAttachmentInputs.newId();
        String objectKey = StaffAttachmentInputs.objectKey(actor.organizationId(), documentId, 1);
        store.putBytes(actor.organizationId(), objectKey, content);
        StaffAttachment created = new StaffAttachment(actor.organizationId(), id, actor.subject(),
                draftId, documentId, fileName, type.contentType(), size, checksum, objectKey, 1,
                ScanState.PENDING, null, false, clock.instant());
        store.putAttachment(created);
        audit(actor, id, AuditType.UPLOADED, "success", null);
        return created;
    }

    /**
     * Record an independent quarantine scan verdict.
     *
     * @param actor   freshly derived scanner identity
     * @param id      attachment identifier
     * @param request verdict command
     * @return the attachment with its updated scan state
     * @throws ApiException on denial, an unknown attachment or a non-pending file
     */
    public StaffAttachment scan(StaffActor actor, String id, ScanRequest request) {
        authorize(actor, StaffAttachmentActions.SCAN, id);
        StaffAttachment current = load(actor, id);
        if (current.scanState() != ScanState.PENDING) {
            throw conflict(actor, id, "INVALID_SCAN_STATE", "only a pending file can be scanned");
        }
        boolean clean = StaffAttachmentInputs.cleanVerdict(request.verdict());
        String detail = StaffAttachmentInputs.detail(request.detail(), 200);
        ScanState next = clean ? ScanState.CLEAN : ScanState.QUARANTINED;
        StaffAttachment updated = withState(current, next);
        store.putAttachment(updated);
        audit(actor, id, clean ? AuditType.SCANNED_CLEAN : AuditType.SCANNED_QUARANTINED, "success", detail);
        return updated;
    }

    /**
     * List the pending, unsent files of one draft so the sender can review them.
     *
     * @param actor   freshly derived sender identity
     * @param draftId conversation or mail-draft identifier
     * @return unsent attachments in this draft, including quarantined files and their reason
     * @throws ApiException when the actor may not read this draft's files
     */
    public List<StaffAttachment> pending(StaffActor actor, String draftId) {
        String draft = StaffAttachmentInputs.key(draftId, "draftId");
        authorize(actor, StaffAttachmentActions.READ, draft);
        return store.attachmentsForDraft(actor.organizationId(), draft).stream()
                .filter(attachment -> !attachment.sent())
                .toList();
    }

    /**
     * Explicitly send named clean files, after validating every member before any
     * member changes (MSG-03 explicit send).
     *
     * @param actor   freshly derived sender identity
     * @param request identifiers of the pending files, the message and the recipients
     * @return the sent attachments in request order
     * @throws ApiException on an unconfirmed or empty command, a quarantined file,
     *                      a wrong uploader or draft, or a stale state
     */
    public List<StaffAttachment> send(StaffActor actor, SendRequest request) {
        String messageId = StaffAttachmentInputs.key(request.messageId(), "messageId");
        authorize(actor, StaffAttachmentActions.SEND, messageId);
        if (!request.confirm()) {
            throw invalid(actor, messageId, "CONFIRM_REQUIRED", "explicit send confirmation is required");
        }
        List<String> ids = request.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            throw invalid(actor, messageId, "EMPTY_ATTACHMENTS", "at least one file id is required");
        }
        if (ids.stream().distinct().count() != ids.size()) {
            throw invalid(actor, messageId, "DUPLICATE_ATTACHMENTS", "file ids must be distinct");
        }
        String draftId = StaffAttachmentInputs.key(request.draftId(), "draftId");
        Set<String> recipients = request.recipients();
        if (recipients == null || recipients.isEmpty()) {
            throw invalid(actor, messageId, "EMPTY_RECIPIENTS", "at least one recipient is required");
        }

        List<StaffAttachment> pending = new ArrayList<>();
        for (String candidate : ids) {
            StaffAttachment current = load(actor, candidate);
            if (current.scanState() == ScanState.QUARANTINED) {
                String reason = failureReason(actor.organizationId(), current.id());
                throw conflict(actor, current.id(), "QUARANTINED_FILE", "file is quarantined: " + reason);
            }
            if (current.scanState() != ScanState.CLEAN) {
                throw conflict(actor, current.id(), "NOT_CLEAN", "only a clean file can be sent");
            }
            if (!current.uploaderSubject().equals(actor.subject())) {
                throw deny(actor, current.id(), "NOT_UPLOADER", "only the uploader may send this file");
            }
            if (!draftId.equals(current.draftId())) {
                throw deny(actor, current.id(), "WRONG_DRAFT", "the file is bound to a different draft");
            }
            if (current.sent()) {
                throw conflict(actor, current.id(), "ALREADY_SENT", "the file has already been sent");
            }
            pending.add(current);
        }

        List<StaffAttachment> sent = new ArrayList<>();
        for (StaffAttachment current : pending) {
            StaffAttachment updated = new StaffAttachment(current.organizationId(), current.id(),
                    current.uploaderSubject(), current.draftId(), current.documentId(), current.fileName(),
                    current.contentType(), current.sizeBytes(), current.checksum(), current.objectKey(),
                    current.objectVersion(), current.scanState(), messageId, true, current.createdAt());
            store.putAttachment(updated);
            audit(actor, current.id(), AuditType.SENT, "success", null);
            sent.add(updated);
        }
        return List.copyOf(sent);
    }

    /**
     * Obtain the verified original bytes for a sent, clean file as an authorized
     * recipient, recording an audited download.
     *
     * @param actor freshly derived recipient identity
     * @param id    attachment identifier
     * @return the original bytes and their metadata
     * @throws ApiException on denial, an unsent or quarantined file or a failed integrity check
     */
    public DownloadedFile download(StaffActor actor, String id) {
        authorize(actor, StaffAttachmentActions.DOWNLOAD, id);
        StaffAttachment current = load(actor, id);
        if (!current.downloadable()) {
            throw conflict(actor, id, "NOT_SENT", "the file is not available for download");
        }
        if (!store.isRecipient(actor.organizationId(), current.messageId(), actor.subject())) {
            throw deny(actor, id, "NOT_RECIPIENT", "the actor is not a recipient of this message");
        }
        byte[] bytes = store.bytes(actor.organizationId(), current.objectKey(), current.checksum())
                .orElseThrow(() -> conflict(actor, id, "OBJECT_MISSING",
                        "the private object is absent or failed integrity verification"));
        audit(actor, id, AuditType.DOWNLOADED, "success", null);
        return new DownloadedFile(bytes, current.checksum(), current.contentType(), current.sizeBytes());
    }

    /**
     * Read one attachment view with its audit trail.
     *
     * @param actor freshly derived caller identity
     * @param id    attachment identifier
     * @return the view when the caller may read this attachment
     * @throws ApiException on denial or concealment
     */
    public View view(StaffActor actor, String id) {
        authorize(actor, StaffAttachmentActions.READ, id);
        StaffAttachment current = load(actor, id);
        return new View(current, auditTrail(actor.organizationId(), id));
    }

    /**
     * Read the audit trail of one attachment in recorded order.
     *
     * @param organizationId active organization
     * @param id             attachment identifier
     * @return immutable audit facts for this attachment
     */
    public List<AttachmentAudit> auditTrail(String organizationId, String id) {
        return store.audits(organizationId).stream()
                .filter(fact -> id.equals(fact.attachmentId()))
                .toList();
    }

    private StaffAttachment load(StaffActor actor, String id) {
        String key = StaffAttachmentInputs.key(id, "id");
        return store.attachment(actor.organizationId(), key)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "attachment not found"));
    }

    private String failureReason(String organizationId, String id) {
        List<AttachmentAudit> failures = auditTrail(organizationId, id).stream()
                .filter(fact -> fact.type() == AuditType.SCANNED_QUARANTINED)
                .toList();
        return failures.isEmpty() ? "scan finding on file " + id
                : failures.get(failures.size() - 1).detail();
    }

    private static StaffAttachment withState(StaffAttachment current, ScanState next) {
        return new StaffAttachment(current.organizationId(), current.id(), current.uploaderSubject(),
                current.draftId(), current.documentId(), current.fileName(), current.contentType(),
                current.sizeBytes(), current.checksum(), current.objectKey(), current.objectVersion(),
                next, current.messageId(), current.sent(), current.createdAt());
    }

    private void authorize(StaffActor actor, String action, String resourceId) {
        if (actor == null || actor.roles().stream()
                .noneMatch(role -> StaffAttachmentActions.allows(action, role))) {
            if (actor != null) {
                audit(actor, resourceId, AuditType.ACCESS_DENIED, "denied", action);
            }
            throw new ApiException(403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private ApiException deny(StaffActor actor, String resourceId, String code, String message) {
        audit(actor, resourceId, AuditType.ACCESS_DENIED, "denied", code);
        return new ApiException(403, code, message);
    }

    private ApiException conflict(StaffActor actor, String resourceId, String code, String message) {
        audit(actor, resourceId, AuditType.ACCESS_DENIED, "conflict", code);
        return new ApiException(409, code, message);
    }

    private ApiException invalid(StaffActor actor, String resourceId, String code, String message) {
        audit(actor, resourceId, AuditType.ACCESS_DENIED, "invalid", code);
        return new ApiException(422, code, message);
    }

    private void audit(StaffActor actor, String attachmentId, AuditType type, String outcome, String detail) {
        store.appendAudit(new AttachmentAudit(StaffAttachmentInputs.newId(), actor.organizationId(),
                attachmentId, type, actor.subject(), outcome, detail, clock.instant()));
    }
}
