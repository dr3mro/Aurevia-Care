package com.aurevia.pharmacyflow.substitution;

import java.util.Set;

import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;

/**
 * Bounded permission and party policy for substitution proposals.
 *
 * <p>The checks reuse the shared specialty role vocabulary so the substitution
 * API cannot drift into a second permission model. Roles are supplied by the
 * caller from the authoritative active-membership fold of the current
 * organization and are re-derived on every command.</p>
 */
public final class SubstitutionRules {

    private SubstitutionRules() {
    }

    /**
     * Decide whether the active roles permit proposing a substitution.
     *
     * @param roles role keys active in the current organization
     * @return {@code true} only for pharmacy staff
     */
    public static boolean mayPropose(Set<String> roles) {
        return roles.contains(SpecialtyRoles.PHARMACY_STAFF);
    }

    /**
     * Decide whether the active roles permit approving or declining.
     *
     * @param roles role keys active in the current organization
     * @return {@code true} only for a clinician
     */
    public static boolean mayDecide(Set<String> roles) {
        return roles.contains(SpecialtyRoles.CLINICIAN);
    }

    /**
     * Decide whether a subject is one of the two proposal parties.
     *
     * @param subject  authenticated subject
     * @param proposal proposal being read
     * @return {@code true} for the pharmacy proposer or the named prescriber
     */
    public static boolean isParty(String subject, Proposal proposal) {
        return subject.equals(proposal.proposedBy()) || subject.equals(proposal.prescriberSubject());
    }
}
