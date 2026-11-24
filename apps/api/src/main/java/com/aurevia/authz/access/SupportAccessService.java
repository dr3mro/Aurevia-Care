package com.aurevia.authz.access;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessContracts.AccessDecision;
import com.aurevia.authz.access.AccessContracts.IncidentRow;
import com.aurevia.authz.access.AccessContracts.SupportCheckRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentView;
import com.aurevia.tenant.TenantContext;

/**
 * Platform support access is denied by default and only enabled by an approved,
 * unexpired incident for the exact requesting subject in the active organization.
 *
 * <p>Incident creation and approval are separate commands bound to different roles,
 * and the database forbids self-approval. Every check is evaluated against the
 * current fold, so deactivation, approval loss and expiry deny the next request.</p>
 */
@Service
public class SupportAccessService {

    /** Business purpose stamped on every platform support audit event (OPS-02). */
    public static final String PURPOSE = "SUPPORT_ACCESS";

    private static final String INCIDENT_TYPE = "SUPPORT_ACCESS_INCIDENT";
    private static final String SUPPORT_ROLE = "platform_support";

    private final AccessRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded access repository
     * @param transactions tenant-guarded transaction template
     * @param clock        injected UTC clock for deterministic windows
     */
    public SupportAccessService(AccessRepository repository, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Open a pending support incident bound to the authenticated requester.
     *
     * @param request validated reference, reason and window
     * @return the pending incident projection
     * @throws ApiException on invalid input, denied access or a missing active membership
     */
    public SupportIncidentView createIncident(SupportIncidentRequest request) {
        AccessRules.validateReference(request.reference());
        AccessRules.validateReason(request.reason(), "INVALID_REASON");
        AccessRules.validateWindow(request.windowMinutes());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        SupportIncidentView result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String scope = "tenant_admin:" + request.reference();
            ApiException denial = authorize(context, "SUPPORT_INCIDENT_CREATE", scope,
                    request.reference(), "tenant_admin");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Instant now = clock.instant();
            IncidentRow row = repository.insertIncident(context.organizationId(), request.reference(),
                    context.subject(), request.reason(), now,
                    now.plusSeconds(request.windowMinutes() * 60L));
            repository.appendAudit(context, PURPOSE, "SUPPORT_INCIDENT_CREATE", scope, INCIDENT_TYPE,
                    row.reference(), "SUCCESS");
            return view(row);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Approve a pending incident from a role separate from the requester.
     *
     * @param reference tenant-local incident reference from the path
     * @return the approved incident projection
     * @throws ApiException on invalid input, denied access or an unusable incident
     */
    public SupportIncidentView approveIncident(String reference) {
        AccessRules.validateReference(reference);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        SupportIncidentView result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String scope = "privacy_auditor:" + reference;
            ApiException denial = authorize(context, "SUPPORT_INCIDENT_APPROVE", scope,
                    reference, "privacy_auditor");
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.approveIncident(reference, context.subject(), clock.instant()) == 0) {
                repository.appendAudit(context, PURPOSE, "SUPPORT_INCIDENT_APPROVE", scope, INCIDENT_TYPE,
                        reference, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT",
                        "incident not found, already approved or expired"));
                return null;
            }
            IncidentRow row = repository.findIncident(reference).orElse(null);
            if (row == null) {
                repository.appendAudit(context, PURPOSE, "SUPPORT_INCIDENT_APPROVE", scope, INCIDENT_TYPE,
                        reference, "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "incident not found"));
                return null;
            }
            repository.appendAudit(context, PURPOSE, "SUPPORT_INCIDENT_APPROVE", scope, INCIDENT_TYPE,
                    row.reference(), "SUCCESS");
            return view(row);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Decide whether platform support access to clinical data is currently authorized.
     *
     * @param request incident reference to evaluate
     * @return an explicit default-deny decision and stable reason code
     * @throws ApiException on an invalid reference
     */
    public AccessDecision checkAccess(SupportCheckRequest request) {
        AccessRules.validateReference(request.reference());
        return transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String scope = SUPPORT_ROLE + ":" + request.reference();
            if (!repository.hasActiveMembership(context.subject())) {
                repository.appendAudit(context, PURPOSE, "SUPPORT_ACCESS_CHECK", scope, INCIDENT_TYPE,
                        request.reference(), "DENIED");
                return new AccessDecision(false, "NO_ACTIVE_MEMBERSHIP");
            }
            Optional<IncidentRow> incident = repository.activeIncident(
                    request.reference(), context.subject(), clock.instant());
            if (incident.isEmpty()) {
                repository.appendAudit(context, PURPOSE, "SUPPORT_ACCESS_CHECK", scope, INCIDENT_TYPE,
                        request.reference(), "DENIED");
                return new AccessDecision(false, "NO_APPROVED_INCIDENT");
            }
            if (incident.get().approvedBy().equals(context.subject())) {
                repository.appendAudit(context, PURPOSE, "SUPPORT_ACCESS_CHECK", scope, INCIDENT_TYPE,
                        request.reference(), "DENIED");
                return new AccessDecision(false, "SELF_APPROVAL");
            }
            repository.appendAudit(context, PURPOSE, "SUPPORT_ACCESS_CHECK", scope, INCIDENT_TYPE,
                    incident.get().reference(), "SUCCESS");
            return new AccessDecision(true, "APPROVED_INCIDENT");
        });
    }

    private ApiException authorize(TenantContext context, String action, String scope,
                                   String reference, String requiredRole) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, PURPOSE, action, scope, INCIDENT_TYPE, reference, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        List<String> roles = repository.activeRoles(context.subject());
        if (!roles.contains(requiredRole)) {
            repository.appendAudit(context, PURPOSE, action, scope, INCIDENT_TYPE, reference, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing action permission");
        }
        return null;
    }

    private SupportIncidentView view(IncidentRow row) {
        return new SupportIncidentView(row.reference(), row.subject(), row.reason(), row.status(),
                row.createdAt().toString(), row.approvedBy(),
                row.approvedAt() == null ? null : row.approvedAt().toString(), row.expiresAt().toString());
    }
}
