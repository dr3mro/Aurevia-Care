package com.aurevia.radflow.worklist;

import java.util.Set;

import com.aurevia.radiology.RadRules;

/**
 * Pure role-boundary rules for the RAD-01 performing-site worklist.
 *
 * <p>The checks reuse the radiology role vocabulary so the worklist cannot drift
 * into a second, incompatible permission model. Roles are supplied by the caller
 * from the active tenant membership and re-derived on every protected request,
 * so a deactivated grant is denied on the next command.</p>
 */
public interface WorklistRules {

    /**
     * Whether the subject may receive an order onto the worklist.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for reception or radiology staff
     */
    static boolean mayReceive(Set<String> roles) {
        return roles.contains(RadRules.RECEPTION) || roles.contains(RadRules.RADIOLOGY_STAFF);
    }

    /**
     * Whether the subject may schedule a received order.
     *
     * @param roles roles active in the current organization
     * @return {@code true} when the subject may receive an order
     */
    static boolean maySchedule(Set<String> roles) {
        return mayReceive(roles);
    }

    /**
     * Whether the subject may link a study and mark it performed.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for radiology staff
     */
    static boolean mayLinkStudy(Set<String> roles) {
        return roles.contains(RadRules.RADIOLOGY_STAFF);
    }

    /**
     * Whether the subject may hand a performed study off as reported.
     *
     * @param roles roles active in the current organization
     * @return {@code true} for an interpreter or radiology staff
     */
    static boolean mayReport(Set<String> roles) {
        return roles.contains(RadRules.INTERPRETER) || roles.contains(RadRules.RADIOLOGY_STAFF);
    }
}
