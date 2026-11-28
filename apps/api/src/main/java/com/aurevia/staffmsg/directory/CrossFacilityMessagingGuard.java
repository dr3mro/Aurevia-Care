package com.aurevia.staffmsg.directory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.authz.access.AccessRules;
import com.aurevia.tenancy.Facility;
import com.aurevia.tenant.TenantContext;

/**
 * Injectable guard for cross-facility instant and mail messages between staff.
 *
 * <p>Any caller resolving a destination in the tenant directory must invoke this
 * guard before publishing. Cross-organization attempts are refused unconditionally:
 * no configuration flag can relax tenant isolation. Within one organization the
 * facility-scope fold reports whether the destination is outside the actor's
 * assigned facilities; only that crossing requires an approved tenant flag.
 * A subject with no recorded assignments keeps organization-wide scope, and
 * oversight roles are exempt from the fold's out-of-scope answer. Every read runs
 * in one tenant-guarded transaction; without a bound context the answer is denial,
 * and an absent flag closes the crossing.</p>
 */
@Component
public final class CrossFacilityMessagingGuard {

    /** Tenant configuration key carrying the approved cross-facility policy flag. */
    static final String POLICY_KEY = "messaging.crossFacility";

    private final DirectoryPolicy policy;
    private final AccessRepository access;
    private final StaffDirectoryService directory;
    private final ActiveStaffGuard gate;
    private final TransactionTemplate transactions;

    /**
     * Create the guard with its tenant-scoped collaborators.
     *
     * @param policy       same-organization and cross-facility policy decisions
     * @param access       tenant configuration and facility-scope fold boundary
     * @param directory    tenant-scoped facility resolver
     * @param gate         active-staff authorization rule
     * @param transactions tenant-guarded transaction template
     */
    public CrossFacilityMessagingGuard(DirectoryPolicy policy, AccessRepository access,
                                       StaffDirectoryService directory, ActiveStaffGuard gate,
                                       TransactionTemplate transactions) {
        this.policy = policy;
        this.access = access;
        this.directory = directory;
        this.gate = gate;
        this.transactions = transactions;
    }

    /**
     * Require that a cross-facility message to one tenant facility is allowed.
     *
     * <p>The destination organization is taken from the tenant-scoped facility record,
     * never from a request field. A facility outside the tenant is invisible under
     * forced RLS and therefore fails closed as a cross-organization refusal.</p>
     *
     * @param facilityCode tenant-local code of the destination facility
     * @throws ApiException with status 422 for an invalid code, 403 for a missing
     *         context, an absent or foreign facility, an inactive actor, or a
     *         cross-facility destination without an approved tenant flag
     */
    public void requireCrossFacilityAllowed(String facilityCode) {
        AccessRules.validateFacility(facilityCode);
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "MISSING_TENANT_CONTEXT", "an authenticated tenant context is required");
        }
        transactions.execute(status -> {
            gate.require(context);
            Facility facility = directory.findFacility(facilityCode).orElseThrow(() ->
                    new ApiException(403, "CROSS_ORGANIZATION_MESSAGING_DISABLED",
                            "destination facility is not in the active organization"));
            policy.requireSameOrganization(context.organizationId(), facility.organizationId());
            // The fold only reports a crossing for a subject with recorded
            // assignments; an unassigned legacy member stays organization-wide and
            // oversight roles are exempt. A crossing is authorized only by an
            // approved tenant flag; an absent flag evaluates false, so an
            // unconfigured tenant stays closed. The destination itself is already
            // tenant-scoped by the guarded directory lookup above.
            if (access.outsideAssignedFacilities(context.subject(), facility.code())
                    && !policy.allowsCrossFacility(AccessRules.isEnabled(access.effectiveConfig(POLICY_KEY)))) {
                throw new ApiException(403, "CROSS_FACILITY_MESSAGING_DISABLED",
                        "cross-facility messaging is not approved for this tenant");
            }
            return null;
        });
    }
}
