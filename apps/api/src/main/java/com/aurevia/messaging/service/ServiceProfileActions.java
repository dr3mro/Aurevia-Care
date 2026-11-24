package com.aurevia.messaging.service;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for the MSG-06 service-profile slice.
 *
 * <p>Patient self-service reads, staff supervision and tenant administration are
 * deliberately separated. The matrix is paired with a fresh membership, role,
 * unit-assignment and connection check on every protected request in the domain
 * service; a role key alone is never sufficient to cross a tenant or unit
 * boundary.</p>
 */
@Component
public class ServiceProfileActions {

    /** Read the effective service profile of a connected unit. */
    public static final String PROFILE_VIEW = "PROFILE_VIEW";
    /** Read the availability projection of a connected unit. */
    public static final String AVAILABILITY_VIEW = "AVAILABILITY_VIEW";
    /** Read immutable profile version history. */
    public static final String PROFILE_HISTORY = "PROFILE_HISTORY";
    /** Publish a new, unapproved profile version. */
    public static final String PROFILE_PUBLISH = "PROFILE_PUBLISH";
    /** Name-approve a draft profile version. */
    public static final String PROFILE_APPROVE = "PROFILE_APPROVE";
    /** Read derived supervisor counters for an assigned unit. */
    public static final String SUPERVISOR_VIEW = "SUPERVISOR_VIEW";

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case PROFILE_VIEW, AVAILABILITY_VIEW -> "patient".equals(role)
                    || "tenant_admin".equals(role) || "privacy_auditor".equals(role)
                    || "unit_supervisor".equals(role);
            case PROFILE_HISTORY -> "tenant_admin".equals(role) || "privacy_auditor".equals(role);
            case PROFILE_PUBLISH, PROFILE_APPROVE -> "tenant_admin".equals(role);
            case SUPERVISOR_VIEW -> "unit_supervisor".equals(role) || "tenant_admin".equals(role);
            default -> false;
        };
    }
}
