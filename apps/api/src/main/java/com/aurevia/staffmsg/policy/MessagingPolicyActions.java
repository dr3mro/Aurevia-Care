package com.aurevia.staffmsg.policy;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.staffmsg.policy.MessagingPolicyContract.MessagingPolicySettings;
import com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyRevision;
import com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyScope;
import com.aurevia.tenant.TenantContext;

/**
 * Public command and query facade for messaging policy.
 *
 * <p>Other staff messaging services inject this facade to read the policy
 * effective for a message. Reads deliberately carry no admin gate; writes
 * require an administrator. The organization and actor come from the bound
 * tenant context, and roles and facility scope from the authoritative actor
 * fold, so no client-supplied field can widen either boundary. The class is a
 * plain component with constructor injection: its persistence adapter and HTTP
 * route are supplied by sibling components, never created here.</p>
 */
public final class MessagingPolicyActions {

    /** Organization administrator key shared with the specialty role vocabulary. */
    private static final String TENANT_ADMIN_ROLE = SpecialtyRoles.TENANT_ADMIN;

    /**
     * Facility-scoped oversight key. Facility ownership is enforced only by the
     * actor fold's facility check, never inferred from the role key itself.
     */
    private static final String FACILITY_ADMIN_ROLE = "unit_supervisor";

    private final MessagingPolicyService service;
    private final MessagingPolicyActorSource actors;

    /**
     * Create the facade with its kernel and authoritative actor source.
     *
     * @param service tenant-scoped policy kernel
     * @param actors  authoritative source of effective roles and facility scope
     */
    public MessagingPolicyActions(MessagingPolicyService service, MessagingPolicyActorSource actors) {
        this.service = Objects.requireNonNull(service, "service");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    /**
     * Read the policy settings effective at an instant.
     *
     * @param scope        tenant or facility scope
     * @param facilityCode facility code for facility scope, otherwise {@code null}
     * @param at           instant at which the policy is needed
     * @return the effective settings, or the fail-closed default
     * @throws ApiException with status 403 when no tenant context is bound
     */
    public MessagingPolicySettings policyAt(PolicyScope scope, String facilityCode, Instant at) {
        TenantContext context = requireContext();
        return service.policyAt(context.organizationId(), scope, facilityCode, at);
    }

    /**
     * Read the immutable revision history for the active tenant and scope.
     *
     * @param scope        tenant or facility scope
     * @param facilityCode facility code for facility scope, otherwise {@code null}
     * @return the ordered, immutable revision snapshot
     * @throws ApiException with status 403 when no tenant context is bound
     */
    public List<PolicyRevision> history(PolicyScope scope, String facilityCode) {
        TenantContext context = requireContext();
        return service.history(context.organizationId(), scope, facilityCode);
    }

    /**
     * Append a policy revision after checking the actor's administrative role.
     *
     * <p>A tenant administrator may edit tenant and facility policies. A
     * facility administrator may edit only a facility policy, and only for a
     * facility the actor fold places inside that administrator's scope.</p>
     *
     * @param scope        tenant or facility scope
     * @param facilityCode facility code for facility scope, otherwise {@code null}
     * @param settings     validated settings for the new revision
     * @param now          server creation instant
     * @return the appended immutable revision
     * @throws ApiException with status 403 when the context is absent or the actor
     *         is not an authorized administrator, 409 on a duplicate version, or
     *         422 on a back-dated effective instant or a scope/facility mismatch
     */
    public PolicyRevision edit(PolicyScope scope, String facilityCode,
                               MessagingPolicySettings settings, Instant now) {
        TenantContext context = requireContext();
        List<String> roles = actors.activeRoles(context.organizationId(), context.subject());
        boolean tenantAdmin = hasRole(roles, TENANT_ADMIN_ROLE);
        boolean facilityAdmin = scope == PolicyScope.FACILITY
                && hasRole(roles, FACILITY_ADMIN_ROLE)
                && facilityCode != null
                && actors.facilityWithinScope(context.organizationId(), context.subject(), facilityCode);
        if (!tenantAdmin && !facilityAdmin) {
            throw new ApiException(403, "POLICY_ADMIN_REQUIRED",
                    "only a tenant or facility administrator may edit messaging policy");
        }
        return service.edit(context.organizationId(), scope, facilityCode, settings, context.subject(), now);
    }

    /**
     * Test a role key against a possibly absent active-role list.
     *
     * @param roles effective role keys, or {@code null} when the fold yielded none
     * @param role  key to look for
     * @return {@code true} only when the list contains the key
     */
    private static boolean hasRole(List<String> roles, String role) {
        return roles != null && roles.contains(role);
    }

    /**
     * Require a bound tenant context so a missing context fails closed.
     *
     * @return the active tenant context
     * @throws ApiException with status 403 when no context is bound
     */
    private static TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "MISSING_TENANT_CONTEXT",
                    "an authenticated tenant context is required");
        }
        return context;
    }
}
