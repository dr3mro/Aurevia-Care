package com.aurevia.smartclinic.staffmsg.directory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.authz.access.AccessRepository;
import com.aurevia.smartclinic.authz.access.AccessRules;
import com.aurevia.smartclinic.tenancy.Facility;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Injectable guard for cross-facility instant and mail messages between staff (MSG-02, ARC-07).
 *
 * <p>The guard is deliberately independent of a send path: a future caller resolves
 * its destination in the tenant directory and calls it before publishing. It refuses
 * cross-organization attempts unconditionally because that connection is an unapproved
 * proposal. Within one organization a destination inside the actor's home facility is
 * allowed; the membership-facility scope fold detects a crossing, and only then is an
 * approved tenant flag required. Every read runs in one tenant-guarded transaction;
 * without a bound context the answer is denial, and an absent flag closes the crossing.</p>
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
            // The V93 fold tells us whether this destination needs a cross-facility
            // decision at all: every invited member has exactly one home assignment,
            // so an out-of-scope answer means the actor is crossing facilities. That
            // crossing is authorized only by an approved tenant flag; an absent flag
            // is false, so an unconfigured tenant stays closed. The destination is
            // already tenant-scoped by the RLS-guarded directory lookup above.
            if (access.outsideAssignedFacilities(context.subject(), facility.code())
                    && !policy.allowsCrossFacility(AccessRules.isEnabled(access.effectiveConfig(POLICY_KEY)))) {
                throw new ApiException(403, "CROSS_FACILITY_MESSAGING_DISABLED",
                        "cross-facility messaging is not approved for this tenant");
            }
            return null;
        });
    }
}
