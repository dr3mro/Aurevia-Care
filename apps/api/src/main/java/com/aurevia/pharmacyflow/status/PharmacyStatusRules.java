package com.aurevia.pharmacyflow.status;

import java.util.Set;

import com.aurevia.authz.specialty.SpecialtyRoles;

/**
 * Bounded permission rule for recording pharmacy status facts (RX-02, IAM-01).
 *
 * <p>The check reuses the shared specialty role vocabulary so the status API
 * cannot drift into a second permission model. Roles are supplied by the caller
 * from the authoritative active-membership fold of the current organization and
 * must be re-derived on every command; presenting a role in a request never
 * grants it.</p>
 */
public final class PharmacyStatusRules {

    private PharmacyStatusRules() {
    }

    /**
     * Decide whether the active roles permit a status fact to be recorded.
     *
     * @param roles role keys active in the current organization
     * @return {@code true} only for an explicitly permitted role
     */
    public static boolean mayRecord(Set<String> roles) {
        return roles.contains(SpecialtyRoles.PHARMACY_STAFF);
    }
}
