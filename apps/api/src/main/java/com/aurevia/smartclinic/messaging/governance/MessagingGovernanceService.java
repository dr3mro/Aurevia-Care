package com.aurevia.smartclinic.messaging.governance;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ConnectionReference;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.CreateExportRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.LinkToChartRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.MessagingResource;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RecordAccessPolicy;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RecordActionRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RequestCopyRequest;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionPolicy;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * MSG-07 governance domain service.
 *
 * <p>Every protected method derives the organization and subject from the
 * request context, re-checks active membership and role, and appends a separate
 * non-sensitive audit fact for the action and its outcome. Retention never
 * removes a resource under legal hold, chart links keep only provenance, and a
 * revoked connection fails closed for new messaging and future attachment
 * access while readability of earlier conversations follows the approved
 * record-access policy.</p>
 */
@Service
public class MessagingGovernanceService {

    /** Purpose stamped on every MSG-07 audit fact. */
    public static final String PURPOSE = "MSG_GOVERNANCE";

    private static final long DEFAULT_TTL_SECONDS = 3600L;
    private static final long MIN_TTL_SECONDS = 60L;
    private static final long MAX_TTL_SECONDS = 86_400L;
    private static final int MAX_REASON_LENGTH = 256;
    private static final int MAX_SCOPE_LENGTH = 128;
    private static final int MAX_PURPOSE_LENGTH = 128;
    private static final String SYSTEM_SCOPE = "SYSTEM";
    private static final String AUDIT_RESOURCE = "AUDIT";
    private static final String ROUTED = "ROUTED";
    private static final String SOURCE_MESSAGE = "message";
    private static final String SOURCE_INSTRUCTION = "instruction";

    private final MessagingGovernanceStore store;
    private final MessagingGovernanceActions actions;
    private final Clock clock;

    /**
     * Create the governance service.
     *
     * @param store   tenant-scoped governance store
     * @param actions bounded action matrix
     * @param clock   application clock for deterministic timestamps and retention
     */
    public MessagingGovernanceService(MessagingGovernanceStore store, MessagingGovernanceActions actions,
                                      Clock clock) {
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * Record one audited conversation or file action.
     *
     * <p>This is a post-hoc audit recorder for authorized workflow services,
     * not an authorization decision. Enforcement of "revocation stops new
     * messaging and future attachment access" lives in
     * {@code requireMessagingAllowed} and {@code requireAttachmentAccess}, which re-check the
     * connection state and derive the outcome server-side. A caller cannot
     * supply an outcome; failures append their own denial facts. Only workflow
     * actions can be recorded here; governance commands are performed and
     * audited solely by their dedicated endpoints, so they cannot be
     * self-reported as successful through this recorder.</p>
     *
     * @param request validated action command
     * @return the appended audit fact
     * @throws ApiException on invalid input, missing context or denial
     */
    public AuditEvent recordAction(RecordActionRequest request) {
        AuditAction action = MessagingGovernanceInputs.workflowAction(request.action());
        String resourceId = MessagingGovernanceInputs.uuid(request.resourceId(), "resourceId");
        String unitId = MessagingGovernanceInputs.text(request.unitId(), "unitId", MAX_SCOPE_LENGTH);
        TenantContext context = requireContext();
        authorize(context, action, resourceId);
        // The outcome is server-derived: an accepted command means the action
        // was performed. Failures append their own DENIED/NOT_FOUND/CONFLICT
        // facts, so a caller cannot self-report a successful action.
        return append(context, action, resourceId, unitId, Outcome.SUCCESS);
    }

    /**
     * Read the tenant's audit trail, optionally scoped to one resource.
     *
     * <p>The read itself is appended as a separate audit fact so audit access
     * is never invisible.</p>
     *
     * @param resourceId optional exact resource filter, or {@code null} for all
     * @return audit facts in insertion order
     * @throws ApiException on invalid input, missing context or denial
     */
    public List<AuditEvent> auditTrail(String resourceId) {
        String filter = resourceId == null ? null : MessagingGovernanceInputs.uuid(resourceId, "resourceId");
        TenantContext context = requireContext();
        String resource = filter == null ? AUDIT_RESOURCE : filter;
        authorize(context, AuditAction.AUDIT_READ, resource);
        // Append the audit-of-read before reading so the returned trail includes
        // the fact that it was reviewed.
        append(context, AuditAction.AUDIT_READ, resource, SYSTEM_SCOPE, Outcome.SUCCESS);
        return filter == null
                ? store.auditEvents(context.organizationId())
                : store.auditEvents(context.organizationId(), filter);
    }

    /**
     * Place a legal hold that suspends retention for one resource.
     *
     * @param request validated hold command
     * @return the created active hold
     * @throws ApiException on invalid input, missing context, denial, concealment or duplicate
     */
    public LegalHold placeHold(PlaceHoldRequest request) {
        String resourceId = MessagingGovernanceInputs.uuid(request.resourceId(), "resourceId");
        String reason = MessagingGovernanceInputs.text(request.reason(), "reason", MAX_REASON_LENGTH);
        TenantContext context = requireContext();
        authorize(context, AuditAction.HOLD_PLACE, resourceId);
        if (store.resources(context.organizationId()).stream()
                .noneMatch(resource -> resourceId.equals(resource.id()))) {
            throw deny(context, AuditAction.HOLD_PLACE, resourceId, 404, "NOT_FOUND", "resource not found");
        }
        for (LegalHold existing : store.legalHolds(context.organizationId())) {
            if (resourceId.equals(existing.resourceId()) && existing.active()) {
                throw deny(context, AuditAction.HOLD_PLACE, resourceId, 409, "HOLD_EXISTS",
                        "an active legal hold already covers this resource");
            }
        }
        LegalHold hold = new LegalHold(MessagingGovernanceInputs.newId(), context.organizationId(),
                resourceId, reason, context.subject(), clock.instant(), null, null, null);
        store.putLegalHold(context.organizationId(), hold);
        append(context, AuditAction.HOLD_PLACE, resourceId, SYSTEM_SCOPE, Outcome.SUCCESS);
        return hold;
    }

    /**
     * Release a legal hold, recording the releasing actor and instant.
     *
     * @param holdId hold identifier
     * @param reason non-sensitive release reason
     * @return the released hold
     * @throws ApiException on invalid input, missing context, denial, concealment or repeat release
     */
    public LegalHold releaseHold(String holdId, String reason) {
        String id = MessagingGovernanceInputs.uuid(holdId, "holdId");
        String releaseReason = MessagingGovernanceInputs.text(reason, "reason", MAX_REASON_LENGTH);
        TenantContext context = requireContext();
        authorize(context, AuditAction.HOLD_RELEASE, id);
        LegalHold current = store.legalHolds(context.organizationId()).stream()
                .filter(hold -> id.equals(hold.id()))
                .findFirst()
                .orElseThrow(() -> deny(context, AuditAction.HOLD_RELEASE, id, 404,
                        "NOT_FOUND", "legal hold not found"));
        if (!current.active()) {
            throw deny(context, AuditAction.HOLD_RELEASE, id, 409, "HOLD_ALREADY_RELEASED",
                    "the legal hold is already released");
        }
        LegalHold released = new LegalHold(current.id(), current.organizationId(), current.resourceId(),
                current.reason(), current.placedBy(), current.placedAt(), context.subject(),
                clock.instant(), releaseReason);
        store.putLegalHold(context.organizationId(), released);
        append(context, AuditAction.HOLD_RELEASE, id, SYSTEM_SCOPE, Outcome.SUCCESS);
        return released;
    }

    /**
     * Decide whether one resource may be removed by retention.
     *
     * @param resourceId resource identifier
     * @return an explicit decision with a stable reason code
     * @throws ApiException on invalid input, missing context, denial or concealment
     */
    public RetentionDecision retentionDecision(String resourceId) {
        String id = MessagingGovernanceInputs.uuid(resourceId, "resourceId");
        TenantContext context = requireContext();
        authorize(context, AuditAction.AUDIT_READ, id);
        MessagingResource resource = store.resources(context.organizationId()).stream()
                .filter(candidate -> id.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> deny(context, AuditAction.AUDIT_READ, id, 404,
                        "NOT_FOUND", "resource not found"));
        RetentionPolicy policy = store.retentionPolicy(context.organizationId(), resource.resourceType())
                .orElseThrow(() -> new ApiException(422, "NO_RETENTION_POLICY",
                        "no approved retention policy for this resource type"));
        RetentionDecision decision;
        if (isHeld(context, id)) {
            decision = new RetentionDecision(id, false, "LEGAL_HOLD");
        } else if (resource.createdAt().plus(policy.retentionDays(), ChronoUnit.DAYS).isAfter(clock.instant())) {
            decision = new RetentionDecision(id, false, "RETENTION_WINDOW");
        } else {
            decision = new RetentionDecision(id, true, "ELIGIBLE");
        }
        // A retention decision is itself auditable; resource IDs are
        // non-sensitive governance facts.
        append(context, AuditAction.AUDIT_READ, id, SYSTEM_SCOPE, Outcome.SUCCESS);
        return decision;
    }

    /**
     * Apply retention to resources whose window has passed and that are not held.
     *
     * @param asOf instant used as the decision clock for deterministic verification
     * @return identifiers of resources that are eligible for removal
     * @throws ApiException on missing context or denial
     */
    public List<String> applyRetention(Instant asOf) {
        TenantContext context = requireContext();
        authorize(context, AuditAction.RETENTION_APPLY, AUDIT_RESOURCE);
        List<String> eligible = new ArrayList<>();
        for (MessagingResource resource : store.resources(context.organizationId())) {
            Optional<RetentionPolicy> policy = store.retentionPolicy(
                    context.organizationId(), resource.resourceType());
            if (policy.isEmpty() || isHeld(context, resource.id())) {
                continue;
            }
            if (!resource.createdAt().plus(policy.orElseThrow().retentionDays(), ChronoUnit.DAYS).isAfter(asOf)) {
                eligible.add(resource.id());
                // Record which resource was deemed eligible so the audit trail
                // can explain a later removal.
                append(context, AuditAction.RETENTION_APPLY, resource.id(), SYSTEM_SCOPE, Outcome.SUCCESS);
            }
        }
        return List.copyOf(eligible);
    }

    /**
     * Create a scoped, expiring export with an explicit audit fact.
     *
     * @param request validated export command
     * @return the created export record
     * @throws ApiException on invalid input, missing context or denial
     */
    public ExportRecord createExport(CreateExportRequest request) {
        String scope = MessagingGovernanceInputs.exportScope(request.scope());
        long ttl = request.ttlSeconds() == null ? DEFAULT_TTL_SECONDS : request.ttlSeconds();
        if (ttl < MIN_TTL_SECONDS || ttl > MAX_TTL_SECONDS) {
            throw new ApiException(422, "INVALID_TTL", "export TTL must be between 60 and 86400 seconds");
        }
        TenantContext context = requireContext();
        authorize(context, AuditAction.EXPORT, scope);
        ExportProvenance provenance = validatedProvenance(request.provenance(), context);
        Instant now = clock.instant();
        ExportRecord export = new ExportRecord(MessagingGovernanceInputs.newId(), context.organizationId(),
                scope, context.subject(), now, now.plusSeconds(ttl), provenance);
        store.putExport(context.organizationId(), export);
        append(context, AuditAction.EXPORT, scope, SYSTEM_SCOPE, Outcome.SUCCESS);
        return export;
    }

    /**
     * Link a clinically relevant message or instruction to the chart, keeping
     * only source provenance in the governance record.
     *
     * @param request validated provenance command
     * @return the persisted provenance link
     * @throws ApiException on invalid input, missing context or denial
     */
    public ProvenanceLink linkToChart(LinkToChartRequest request) {
        String conversationId = MessagingGovernanceInputs.uuid(request.conversationId(), "conversationId");
        String messageId = MessagingGovernanceInputs.uuid(request.messageId(), "messageId");
        String patientId = MessagingGovernanceInputs.uuid(request.patientId(), "patientId");
        String chartId = MessagingGovernanceInputs.uuid(request.chartId(), "chartId");
        String sourceType = MessagingGovernanceInputs.text(request.sourceType(), "sourceType", MAX_SCOPE_LENGTH);
        String sourceReference = MessagingGovernanceInputs.text(request.sourceReference(), "sourceReference",
                MAX_SCOPE_LENGTH);
        String revision = MessagingGovernanceInputs.text(request.revision(), "revision", MAX_SCOPE_LENGTH);
        if (!SOURCE_MESSAGE.equals(sourceType) && !SOURCE_INSTRUCTION.equals(sourceType)) {
            throw new ApiException(422, "INVALID_SOURCETYPE", "sourceType must be message or instruction");
        }
        TenantContext context = requireContext();
        authorize(context, AuditAction.CHART_LINK, messageId);
        // Object-level binding: the chart may only receive provenance for a
        // patient with a verified connection in this tenant. A clinician who
        // cannot see an active connection for this patient cannot file to the
        // chart, so a message from one patient cannot be linked to another.
        boolean verifiedPatient = store.allConnections(context.organizationId()).stream()
                .anyMatch(connection -> patientId.equals(connection.patientId()));
        if (!verifiedPatient) {
            throw deny(context, AuditAction.CHART_LINK, messageId, 404, "NOT_FOUND",
                    "no verified connection for this patient in the active organization");
        }
        ProvenanceLink link = new ProvenanceLink(MessagingGovernanceInputs.newId(), context.organizationId(),
                conversationId, messageId, patientId, chartId, sourceType, sourceReference, revision,
                context.subject(), clock.instant());
        store.putProvenanceLink(context.organizationId(), link);
        append(context, AuditAction.CHART_LINK, messageId, SYSTEM_SCOPE, Outcome.SUCCESS);
        return link;
    }

    /**
     * Record the effects of a revoked connection on new messaging, future
     * attachments and readability of earlier conversations.
     *
     * @param connectionId revoked connection identifier
     * @return the recorded effect
     * @throws ApiException on invalid input, missing context, denial, concealment or an active connection
     */
    public RevocationEffect recordRevocation(String connectionId) {
        String id = MessagingGovernanceInputs.uuid(connectionId, "connectionId");
        TenantContext context = requireContext();
        authorize(context, AuditAction.RESTRICT, id);
        ConnectionReference connection = store.connection(context.organizationId(), id)
                .orElseThrow(() -> deny(context, AuditAction.RESTRICT, id, 404,
                        "NOT_FOUND", "connection not found"));
        if (connection.revokedAt() == null) {
            throw deny(context, AuditAction.RESTRICT, id, 409, "CONNECTION_ACTIVE",
                    "the connection is not revoked");
        }
        RecordAccessPolicy policy = store.recordAccessPolicy(context.organizationId());
        RevocationEffect effect = new RevocationEffect(MessagingGovernanceInputs.newId(),
                context.organizationId(), id, true, true, policy.priorConversationsReadable(),
                policy.policyVersion(), clock.instant());
        store.putRevocationEffect(context.organizationId(), effect);
        append(context, AuditAction.RESTRICT, id, SYSTEM_SCOPE, Outcome.SUCCESS);
        return effect;
    }

    /**
     * Authorize and audit one mediated attachment access, refusing it when the
     * connection has been revoked.
     *
     * @param connectionId connection used for the access
     * @param resourceId   attachment resource identifier
     * @throws ApiException on invalid input, missing context, denial, concealment or revocation
     */
    public void requireAttachmentAccess(String connectionId, String resourceId) {
        String connectionRef = MessagingGovernanceInputs.uuid(connectionId, "connectionId");
        String resource = MessagingGovernanceInputs.uuid(resourceId, "resourceId");
        TenantContext context = requireContext();
        authorize(context, AuditAction.DOWNLOAD, resource);
        ConnectionReference connection = store.connection(context.organizationId(), connectionRef)
                .orElseThrow(() -> deny(context, AuditAction.DOWNLOAD, resource, 404,
                        "NOT_FOUND", "connection not found"));
        if (connection.revokedAt() != null) {
            throw deny(context, AuditAction.DOWNLOAD, resource, 403, "ATTACHMENT_ACCESS_REVOKED",
                    "attachment access through a revoked connection is not permitted");
        }
        append(context, AuditAction.DOWNLOAD, resource, connection.unitId(), Outcome.SUCCESS);
    }

    /**
     * Authorize and audit a new message or instruction on a connection,
     * refusing it when the connection has been revoked.
     *
     * <p>Revocation stops new messaging: this guard is the enforced entry point
     * that conversation and instruction services call before committing a send.</p>
     *
     * @param connectionId connection used for the message
     * @param resourceId   conversation or instruction resource identifier
     * @throws ApiException on invalid input, missing context, denial, concealment or revocation
     */
    public void requireMessagingAllowed(String connectionId, String resourceId) {
        String connectionRef = MessagingGovernanceInputs.uuid(connectionId, "connectionId");
        String resource = MessagingGovernanceInputs.uuid(resourceId, "resourceId");
        TenantContext context = requireContext();
        authorize(context, AuditAction.SEND, resource);
        ConnectionReference connection = store.connection(context.organizationId(), connectionRef)
                .orElseThrow(() -> deny(context, AuditAction.SEND, resource, 404,
                        "NOT_FOUND", "connection not found"));
        if (connection.revokedAt() != null) {
            throw deny(context, AuditAction.SEND, resource, 403, "MESSAGING_REVOKED",
                    "new messaging through a revoked connection is not permitted");
        }
        append(context, AuditAction.SEND, resource, connection.unitId(), Outcome.SUCCESS);
    }

    /**
     * Read the preserved sender and recipient retention duties for a resource.
     *
     * @param resourceId resource identifier
     * @return the immutable duty pair
     * @throws ApiException on invalid input, missing context, denial or concealment
     */
    public com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.RetentionDuty retentionDuty(
            String resourceId) {
        String id = MessagingGovernanceInputs.uuid(resourceId, "resourceId");
        TenantContext context = requireContext();
        authorize(context, AuditAction.AUDIT_READ, id);
        return store.retentionDuty(context.organizationId(), id)
                .orElseThrow(() -> deny(context, AuditAction.AUDIT_READ, id, 404,
                        "NOT_FOUND", "retention duties not found"));
    }

    /**
     * Route a request for copies to the site's record-access procedure.
     *
     * @param request validated copy command
     * @return the routed copy request
     * @throws ApiException on invalid input, missing context or denial
     */
    public CopyRequest requestCopy(RequestCopyRequest request) {
        String resourceId = MessagingGovernanceInputs.uuid(request.resourceId(), "resourceId");
        String purpose = MessagingGovernanceInputs.text(request.purpose(), "purpose", MAX_PURPOSE_LENGTH);
        TenantContext context = requireContext();
        authorize(context, AuditAction.COPY_REQUEST, resourceId);
        CopyRequest copy = new CopyRequest(MessagingGovernanceInputs.newId(), context.organizationId(),
                resourceId, context.subject(), purpose, ROUTED, clock.instant());
        store.putCopyRequest(context.organizationId(), copy);
        append(context, AuditAction.COPY_REQUEST, resourceId, SYSTEM_SCOPE, Outcome.SUCCESS);
        return copy;
    }

    /**
     * Validate the provenance an export must carry and bind it to a real source.
     *
     * <p>Provenance is a server-durable trace of the source material and the
     * exact revision a copy was derived from. The source must be a resource the
     * governance store already tracks in the active tenant, otherwise a caller
     * could attribute a copy to material that was never exported. Free-text
     * fields are reduced to allowlisted codes and bounded identifier tokens so
     * patient identifiers cannot be written into the immutable record (F2).</p>
     *
     * @param provenance caller-supplied provenance
     * @param context    active tenant used for the source binding
     * @return validated provenance naming a tracked resource
     * @throws ApiException when provenance is absent, malformed or names an untracked source
     */
    private ExportProvenance validatedProvenance(ExportProvenance provenance, TenantContext context) {
        if (provenance == null) {
            throw new ApiException(422, "INVALID_PROVENANCE", "export provenance is required");
        }
        String sourceReference = MessagingGovernanceInputs.uuid(
                provenance.sourceReference(), "sourceReference");
        String sourceRevision = MessagingGovernanceInputs.exportRevision(provenance.sourceRevision());
        String purpose = MessagingGovernanceInputs.exportPurpose(provenance.purpose());
        boolean tracked = store.resources(context.organizationId()).stream()
                .anyMatch(resource -> sourceReference.equals(resource.id()));
        if (!tracked) {
            throw deny(context, AuditAction.EXPORT, sourceReference, 404,
                    "NOT_FOUND", "export source resource not found in the active organization");
        }
        return new ExportProvenance(sourceReference, sourceRevision, purpose);
    }

    private boolean isHeld(TenantContext context, String resourceId) {
        for (LegalHold hold : store.legalHolds(context.organizationId())) {
            if (resourceId.equals(hold.resourceId()) && hold.active()) {
                return true;
            }
        }
        return false;
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private void authorize(TenantContext context, AuditAction action, String resourceId) {
        if (!store.hasActiveMembership(context.organizationId(), context.subject())) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "no active membership");
        }
        boolean permitted = store.activeRoles(context.organizationId(), context.subject()).stream()
                .anyMatch(role -> actions.allows(action, role));
        if (!permitted) {
            throw deny(context, action, resourceId, 403, "FORBIDDEN", "missing " + action + " permission");
        }
    }

    private ApiException deny(TenantContext context, AuditAction action, String resourceId,
                              int status, String code, String message) {
        Outcome outcome = status == 404 ? Outcome.NOT_FOUND
                : status == 409 ? Outcome.CONFLICT : Outcome.DENIED;
        append(context, action, resourceId, SYSTEM_SCOPE, outcome);
        return new ApiException(status, code, message);
    }

    private AuditEvent append(TenantContext context, AuditAction action, String resourceId,
                              String unitId, Outcome outcome) {
        AuditEvent event = new AuditEvent(MessagingGovernanceInputs.newId(), context.organizationId(),
                context.subject(), PURPOSE, action, resourceId, unitId, context.correlationId(),
                outcome, clock.instant());
        store.appendAudit(event);
        return event;
    }
}
