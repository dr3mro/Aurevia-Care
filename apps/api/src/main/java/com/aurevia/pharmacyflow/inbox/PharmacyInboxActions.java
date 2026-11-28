package com.aurevia.pharmacyflow.inbox;

import org.springframework.stereotype.Component;

import com.aurevia.authz.specialty.SpecialtyRoles;

/**
 * Bounded action matrix for the pharmacy inbox read slice.
 *
 * <p>Reviewing explicitly routed prescriptions belongs to pharmacy staff. A
 * prescribing clinician, administrator or auditor may not use this inbox to read
 * routed clinical content; the role matrix is paired with a fresh membership and
 * role check on every request in the domain service.</p>
 */
@Component
public class PharmacyInboxActions {

    /** Read the routed-prescription inbox or one routed prescription. */
    public static final String READ = "PHARMACY_INBOX_READ";

    /**
     * Decide whether a role may perform an inbox action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} only for an explicitly permitted pair
     */
    public boolean allows(String action, String role) {
        return READ.equals(action) && SpecialtyRoles.PHARMACY_STAFF.equals(role);
    }
}
