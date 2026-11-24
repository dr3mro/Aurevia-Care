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
import com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantView;
import com.aurevia.authz.access.AccessContracts.GrantRow;
import com.aurevia.authz.access.AccessContracts.ReviewRequest;
import com.aurevia.authz.access.AccessContracts.RevokeRequest;
import com.aurevia.tenant.TenantContext;

/**
 * Emergency access invariants: a qualified clinician may open a short, reasoned,
 * own-organization grant that is alerted, independently reviewed and never stacked.
 *
 * <p>Membership, role, site approval and facility scope are re-read inside each
 * protected transaction, so revocation or deactivation denies the next request. A
 * denial is audited and committed by returning the failure from the transaction and
 * throwing only after it completes.</p>
 */
@Service
public class EmergencyAccessService {

    /** Business purpose stamped on every emergency access audit event (OPS-02). */
    public static final String PURPOSE = "EMERGENCY_ACCESS";
    /** Site configuration key that must be approved before emergency access is available. */
    public static final String CONFIG_KEY = "access.emergency.enabled";

    private static final String GRANT_TYPE = "EMERGENCY_ACCESS_GRANT";
    private static final String REVIEW_TYPE = "EMERGENCY_ACCESS_REVIEW";
    private static final String CLINICIAN = "clinician";
    private static final String UNKNOWN_FACILITY = "SYSTEM";

    private final AccessRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param repository   RLS-bounded access repository
     * @param transactions tenant-guarded transaction template
     * @param clock        injected UTC clock for deterministic expiries
     */
    public EmergencyAccessService(AccessRepository repository, TransactionTemplate transactions, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Grant time-limited emergency access to the authenticated clinician.
     *
     * @param request validated facility, reason and lifetime
     * @return the persisted grant projection
     * @throws ApiException on invalid input, denied access, a missing facility or a stacked grant
     */
    public EmergencyGrantView grant(EmergencyGrantRequest request) {
        AccessRules.validateFacility(request.facilityCode());
        AccessRules.validateReason(request.reason(), "INVALID_REASON");
        AccessRules.validateDuration(request.durationMinutes());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        EmergencyGrantView result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String scope = CLINICIAN + ":" + request.facilityCode();
            ApiException denial = authorize(context, "EMERGENCY_GRANT", scope, request.facilityCode(), false);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            ApiException scopeDenial = facilityDenial(context, "EMERGENCY_GRANT", scope,
                    request.facilityCode(), request.facilityCode());
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return null;
            }
            if (!AccessRules.isEnabled(repository.effectiveConfig(CONFIG_KEY))) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_GRANT", scope, GRANT_TYPE,
                        request.facilityCode(), "DENIED");
                failure.set(new ApiException(403, "FORBIDDEN", "emergency access is not approved for this site"));
                return null;
            }
            if (!repository.facilityExists(request.facilityCode())) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_GRANT", scope, GRANT_TYPE,
                        request.facilityCode(), "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "facility is not in the active organization"));
                return null;
            }
            Instant now = clock.instant();
            Optional<GrantRow> latest = repository.latestGrant(context.subject());
            if (latest.isPresent() && isUnexpired(latest.get(), now)) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_GRANT", scope, GRANT_TYPE,
                        latest.get().id(), "CONFLICT");
                failure.set(new ApiException(409, "CONFLICT", "an active emergency grant already exists"));
                return null;
            }
            GrantRow row = repository.insertGrant(context.organizationId(), context.subject(),
                    request.facilityCode(), PURPOSE, request.reason(), CLINICIAN, now,
                    now.plusSeconds(request.durationMinutes() * 60L));
            repository.appendAudit(context, PURPOSE, "EMERGENCY_GRANT", scope, GRANT_TYPE,
                    row.id(), "SUCCESS");
            repository.appendAudit(context, PURPOSE, "EMERGENCY_ALERT", scope, GRANT_TYPE,
                    row.id(), "SUCCESS");
            // The alert is also a durable work item for the privacy auditor, opened
            // in the same guarded transaction so no grant can exist without it.
            repository.openReview(context.organizationId(), row.id(), context.subject(), now);
            repository.appendAudit(context, PURPOSE, "EMERGENCY_REVIEW_OPEN",
                    "privacy_auditor:" + row.facilityCode(), REVIEW_TYPE, row.id(), "SUCCESS");
            return view(row, now);
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Decide whether the authenticated clinician currently holds usable emergency access.
     *
     * @return an explicit decision and stable reason code
     * @throws ApiException when membership or the clinician role is no longer active
     */
    public AccessDecision active() {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        AccessDecision result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "EMERGENCY_ACTIVE_CHECK",
                    CLINICIAN + ":" + UNKNOWN_FACILITY, context.subject(), false);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Instant now = clock.instant();
            Optional<GrantRow> latest = repository.latestGrant(context.subject());
            if (latest.isEmpty()) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_ACTIVE_CHECK",
                        CLINICIAN + ":" + UNKNOWN_FACILITY, GRANT_TYPE, context.subject(), "DENIED");
                return new AccessDecision(false, "NO_GRANT");
            }
            GrantRow row = latest.get();
            String scope = CLINICIAN + ":" + row.facilityCode();
            ApiException scopeDenial = facilityDenial(context, "EMERGENCY_ACTIVE_CHECK", scope,
                    row.id(), row.facilityCode());
            if (scopeDenial != null) {
                failure.set(scopeDenial);
                return null;
            }
            if (row.revokedAt() != null) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_ACTIVE_CHECK", scope, GRANT_TYPE,
                        row.id(), "DENIED");
                return new AccessDecision(false, "REVOKED");
            }
            if (!now.isBefore(row.expiresAt())) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_ACTIVE_CHECK", scope, GRANT_TYPE,
                        row.id(), "DENIED");
                return new AccessDecision(false, "EXPIRED");
            }
            repository.appendAudit(context, PURPOSE, "EMERGENCY_ACTIVE_CHECK", scope, GRANT_TYPE,
                    row.id(), "SUCCESS");
            return new AccessDecision(true, "ACTIVE");
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * End an emergency grant early with a recorded reason.
     *
     * @param id      grant identifier from the path
     * @param request recorded reason
     * @return the updated grant projection
     * @throws ApiException on invalid input, denied access or an unknown or already revoked grant
     */
    public EmergencyGrantView revoke(String id, RevokeRequest request) {
        AccessRules.validateReason(request.reason(), "INVALID_REASON");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        EmergencyGrantView result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            String scope = CLINICIAN + ":" + UNKNOWN_FACILITY;
            ApiException denial = authorize(context, "EMERGENCY_REVOKE", scope, id, false);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (repository.revokeGrant(id, request.reason(), clock.instant()) == 0) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_REVOKE", scope, GRANT_TYPE,
                        id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "emergency grant not found or already revoked"));
                return null;
            }
            GrantRow row = repository.findGrant(id).orElse(null);
            if (row == null) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_REVOKE", scope, GRANT_TYPE,
                        id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "emergency grant not found"));
                return null;
            }
            repository.appendAudit(context, PURPOSE, "EMERGENCY_REVOKE",
                    CLINICIAN + ":" + row.facilityCode(), GRANT_TYPE, row.id(), "SUCCESS");
            return view(row, clock.instant());
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Record an independent review and close its open review row with an explicit
     * outcome; the grantee cannot review their own grant.
     *
     * @param id      grant identifier from the path
     * @param request reviewer finding and closure outcome
     * @return the reviewed grant projection
     * @throws ApiException on invalid input, denied access or an unknown or already reviewed grant
     */
    public EmergencyGrantView review(String id, ReviewRequest request) {
        AccessRules.validateReason(request.note(), "INVALID_NOTE");
        String outcome = request.outcome() == null ? AccessRules.DEFAULT_REVIEW_OUTCOME : request.outcome();
        AccessRules.validateReviewOutcome(outcome);
        AtomicReference<ApiException> failure = new AtomicReference<>();
        EmergencyGrantView result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "EMERGENCY_REVIEW",
                    "reviewer:" + UNKNOWN_FACILITY, id, true);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            List<String> roles = repository.activeRoles(context.subject());
            if (repository.reviewGrant(id, context.subject(), request.note(), clock.instant()) == 0) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_REVIEW",
                        "reviewer:" + UNKNOWN_FACILITY, GRANT_TYPE, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "grant not found or already reviewed"));
                return null;
            }
            repository.closeReview(id, context.subject(), outcome, request.note(), clock.instant());
            GrantRow row = repository.findGrant(id).orElse(null);
            if (row == null) {
                repository.appendAudit(context, PURPOSE, "EMERGENCY_REVIEW",
                        "reviewer:" + UNKNOWN_FACILITY, GRANT_TYPE, id, "NOT_FOUND");
                failure.set(new ApiException(404, "NOT_FOUND", "grant not found"));
                return null;
            }
            String scope = reviewerRole(roles) + ":" + row.facilityCode();
            repository.appendAudit(context, PURPOSE, "EMERGENCY_REVIEW", scope, GRANT_TYPE,
                    row.id(), "SUCCESS");
            return view(row, clock.instant());
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * List every visible grant with unreviewed grants first for the review worklist.
     *
     * @return immutable grant projections
     * @throws ApiException when membership or a reviewer role is no longer active
     */
    public List<EmergencyGrantView> reviewQueue() {
        AtomicReference<ApiException> failure = new AtomicReference<>();
        List<EmergencyGrantView> result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "EMERGENCY_REVIEW_QUEUE",
                    "reviewer:" + UNKNOWN_FACILITY, "-", true);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            Instant now = clock.instant();
            List<EmergencyGrantView> views = repository.listGrants().stream()
                    .map(row -> view(row, now)).toList();
            repository.appendAudit(context, PURPOSE, "EMERGENCY_REVIEW_QUEUE",
                    "reviewer:" + UNKNOWN_FACILITY, GRANT_TYPE, "-", "SUCCESS");
            return views;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return List.copyOf(result);
    }

    /**
     * Apply the IAM-01 facility scope to a facility-aware access decision. The
     * denial is returned so the guarded transaction commits its DENIED audit row
     * before the caller reports the failure.
     *
     * @param context      active tenant context
     * @param action       access action being authorized
     * @param scope        bounded audit scope already computed by the caller
     * @param resourceId   resource addressed by the command, for the audit row
     * @param facilityCode facility the grant is scoped to
     * @return the denial to throw after commit, or {@code null} when in scope
     */
    private ApiException facilityDenial(TenantContext context, String action, String scope,
                                        String resourceId, String facilityCode) {
        if (repository.outsideAssignedFacilities(context.subject(), facilityCode)) {
            repository.appendAudit(context, PURPOSE, action, scope, GRANT_TYPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "facility is outside the assigned scope");
        }
        return null;
    }

    private ApiException authorize(TenantContext context, String action, String scope,
                                   String resourceId, boolean reviewer) {
        if (!repository.hasActiveMembership(context.subject())) {
            repository.appendAudit(context, PURPOSE, action, scope, GRANT_TYPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        List<String> roles = repository.activeRoles(context.subject());
        boolean allowed = reviewer ? AccessRules.isReviewer(roles) : AccessRules.isClinician(roles);
        if (!allowed) {
            repository.appendAudit(context, PURPOSE, action, scope, GRANT_TYPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing action permission");
        }
        return null;
    }

    private boolean isUnexpired(GrantRow row, Instant now) {
        return row.revokedAt() == null && now.isBefore(row.expiresAt());
    }

    private String reviewerRole(List<String> roles) {
        return roles.contains("privacy_auditor") ? "privacy_auditor" : "tenant_admin";
    }

    private EmergencyGrantView view(GrantRow row, Instant now) {
        String status;
        if (row.reviewedAt() != null) {
            status = "reviewed";
        } else if (row.revokedAt() != null) {
            status = "revoked";
        } else if (!now.isBefore(row.expiresAt())) {
            status = "expired";
        } else {
            status = "active";
        }
        return new EmergencyGrantView(row.id(), row.subject(), row.facilityCode(), row.roleKey(),
                row.reason(), row.grantedAt().toString(), row.expiresAt().toString(), status);
    }
}
