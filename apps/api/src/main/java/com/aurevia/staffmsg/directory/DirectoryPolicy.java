package com.aurevia.staffmsg.directory;

import org.springframework.stereotype.Component;

import com.aurevia.api.ApiException;

/**
 * Pure policy decisions for staff discovery and staff-to-staff messaging (MSG-02, ARC-07).
 *
 * <p>Cross-organization messaging is an unapproved proposal, so the same-organization
 * rule is unconditional: no tenant policy flag can relax it. The cross-facility rule
 * only reports whether an approved tenant flag was supplied; the caller must obtain
 * that value from an authoritative, tenant-scoped source.</p>
 */
@Component
public final class DirectoryPolicy {

    /**
     * Report whether cross-facility messaging is permitted by an approved tenant flag.
     *
     * @param tenantPolicyEnabled value derived from the tenant's approved configuration
     * @return the supplied decision; no default permission is invented
     */
    public boolean allowsCrossFacility(boolean tenantPolicyEnabled) {
        return tenantPolicyEnabled;
    }

    /**
     * Refuse an actor whose organization differs from the destination organization.
     *
     * <p>Both identifiers are required; a missing identifier fails closed. The rule
     * is independent of any client-supplied role or facility field and keeps tenant
     * isolation even if a future implementation starts allowing partner connections.</p>
     *
     * @param actorOrganizationId   organization derived from the authenticated context
     * @param targetOrganizationId  organization derived from the tenant-scoped destination record
     * @throws ApiException with status 403 and code CROSS_ORGANIZATION_MESSAGING_DISABLED
     *         when either identifier is absent or the organizations differ
     */
    public void requireSameOrganization(String actorOrganizationId, String targetOrganizationId) {
        if (actorOrganizationId == null || targetOrganizationId == null
                || !actorOrganizationId.equals(targetOrganizationId)) {
            throw new ApiException(403, "CROSS_ORGANIZATION_MESSAGING_DISABLED",
                    "messaging between organizations is disabled until an approved agreement exists");
        }
    }
}
