package com.aurevia.staffmsg.references;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.references.ReferenceContract.AccessOutcome;
import com.aurevia.staffmsg.references.ReferenceContract.AttachRequest;
import com.aurevia.staffmsg.references.ReferenceContract.AttachResult;
import com.aurevia.staffmsg.references.ReferenceContract.AuditType;
import com.aurevia.staffmsg.references.ReferenceContract.MessageReference;
import com.aurevia.staffmsg.references.ReferenceContract.NotificationDraft;
import com.aurevia.staffmsg.references.ReferenceContract.OpenResult;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceActor;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceAudit;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceHandle;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceKind;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceView;
import com.aurevia.staffmsg.references.ReferenceContract.SubjectWarning;

/**
 * Domain service for typed staff-message references (MSG-07, ARC-07).
 *
 * <p>Attaching validates the target and screens the subject but copies no chart
 * content: the subject is discarded after screening. A view projects the label
 * only. Opening re-runs the recipient's access check on every call, so revocation
 * takes effect immediately; a denied or concealed target returns no target data
 * and appends a denial fact. Only an allowed open dispatches a notification, and
 * that notification carries fixed text and an opaque reference id.</p>
 */
@Service
public final class ReferenceService {

    /** Fixed approved phrase: no clinical detail and no identifier can reach a push payload. */
    private static final String OPENED_TEXT = "A message reference was opened.";

    private final ReferenceAccess access;
    private final ReferenceAuditor auditor;
    private final ReferenceNotifier notifier;
    private final Clock clock;

    /**
     * Create the service over its narrow ports and an injected clock.
     *
     * @param access   recipient access-check port
     * @param auditor  identifier-free audit port
     * @param notifier identifier-free notification port
     * @param clock    source of server timestamps
     */
    @Autowired
    public ReferenceService(ReferenceAccess access, ReferenceAuditor auditor,
                            ReferenceNotifier notifier, Clock clock) {
        this.access = access;
        this.auditor = auditor;
        this.notifier = notifier;
        this.clock = clock;
    }

    /**
     * Validate and describe a reference for inclusion in message content.
     *
     * @param actor   freshly derived sender identity
     * @param request target handles, a non-identifying label and the raw subject
     * @return the opaque reference and a warning that never echoes the subject
     * @throws ApiException on an invalid kind, key or label
     */
    public AttachResult attach(ReferenceActor actor, AttachRequest request) {
        ReferenceKind kind = ReferenceInputs.kind(request.kind());
        String targetId = ReferenceInputs.key(request.targetId(), "targetId");
        String connectionId = ReferenceInputs.key(request.connectionId(), "connectionId");
        String label = ReferenceInputs.label(request.label());
        SubjectWarning warning = ReferenceInputs.screenSubject(request.subject());
        MessageReference reference = new MessageReference(ReferenceInputs.newId(), actor.organizationId(),
                kind, targetId, connectionId, label, clock.instant());
        record(actor, reference.id(), AuditType.ATTACHED, "success");
        return new AttachResult(reference, warning);
    }

    /**
     * Project a reference to the label a recipient may see before opening it.
     *
     * @param actor  freshly derived caller identity
     * @param handle client-held reference descriptor
     * @return the label-only view; never the target or connection handle
     * @throws ApiException on an invalid kind, key or label
     */
    public ReferenceView view(ReferenceActor actor, ReferenceHandle handle) {
        String id = ReferenceInputs.key(handle.id(), "id");
        ReferenceKind kind = ReferenceInputs.kind(handle.kind());
        String label = ReferenceInputs.label(handle.label());
        return new ReferenceView(id, kind.wire(), label);
    }

    /**
     * Open a reference after re-checking the recipient's access on every call.
     *
     * @param actor  freshly derived recipient identity
     * @param handle client-held reference descriptor
     * @return the target locator, returned only after the check passes
     * @throws ApiException 403 when blocked, 404 when concealed, or 422 on invalid input
     */
    public OpenResult open(ReferenceActor actor, ReferenceHandle handle) {
        String id = ReferenceInputs.key(handle.id(), "id");
        ReferenceKind kind = ReferenceInputs.kind(handle.kind());
        String targetId = ReferenceInputs.key(handle.targetId(), "targetId");
        String connectionId = ReferenceInputs.key(handle.connectionId(), "connectionId");
        AccessOutcome outcome = access.check(actor.organizationId(), actor.subject(),
                kind, targetId, connectionId);
        if (outcome == AccessOutcome.ALLOWED) {
            record(actor, id, AuditType.OPENED, "success");
            notifier.dispatch(new NotificationDraft("REFERENCE_OPENED", OPENED_TEXT, id));
            return new OpenResult(id, kind.wire(), targetId);
        }
        if (outcome == AccessOutcome.UNKNOWN_TARGET || outcome == AccessOutcome.WRONG_TENANT) {
            // Concealment: the same generic answer for an absent and a foreign target.
            record(actor, id, AuditType.ACCESS_DENIED, "not_found");
            throw new ApiException(404, "REFERENCE_NOT_FOUND", "reference target is not available");
        }
        record(actor, id, AuditType.ACCESS_DENIED, "blocked");
        throw new ApiException(403, "REFERENCE_BLOCKED", "the reference could not be opened");
    }

    /**
     * Screen a subject line for a sender-side pre-check.
     *
     * @param subject raw subject line, may be null or blank
     * @return a warning with a fixed reason code, never the subject text
     */
    public SubjectWarning screenSubject(String subject) {
        return ReferenceInputs.screenSubject(subject);
    }

    /**
     * Append one fact without ever passing a target handle or subject line.
     *
     * @param actor        freshly derived actor
     * @param referenceId  opaque reference handle
     * @param type         server-derived action
     * @param outcome      server-derived outcome
     */
    private void record(ReferenceActor actor, String referenceId, AuditType type, String outcome) {
        auditor.append(new ReferenceAudit(ReferenceInputs.newId(), actor.organizationId(),
                referenceId, type, actor.subject(), outcome, clock.instant()));
    }
}
