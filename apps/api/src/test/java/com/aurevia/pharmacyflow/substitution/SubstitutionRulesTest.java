package com.aurevia.pharmacyflow.substitution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Reason;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.State;

/** Bounded permission and party policy for substitution proposals. */
class SubstitutionRulesTest {

    @Test
    void onlyPharmacyStaffMayPropose() {
        assertTrue(SubstitutionRules.mayPropose(Set.of(SpecialtyRoles.PHARMACY_STAFF)));
        assertFalse(SubstitutionRules.mayPropose(Set.of(SpecialtyRoles.CLINICIAN)));
    }

    @Test
    void onlyCliniciansMayDecide() {
        assertTrue(SubstitutionRules.mayDecide(Set.of(SpecialtyRoles.CLINICIAN)));
        assertFalse(SubstitutionRules.mayDecide(Set.of(SpecialtyRoles.PHARMACY_STAFF)));
    }

    @Test
    void partyCheckCoversProposerPrescriberAndOutsider() {
        Proposal proposal = new Proposal("p", SubstitutionFixtures.ORG,
                SubstitutionFixtures.PRESCRIPTION, SubstitutionFixtures.PATIENT,
                SubstitutionFixtures.PRESCRIBER, SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.original(), SubstitutionFixtures.alternative(), Reason.STOCK,
                null, null, SubstitutionFixtures.noConflictCheck(), State.PENDING, 1,
                SubstitutionFixtures.AT);
        assertTrue(SubstitutionRules.isParty(SubstitutionFixtures.PHARMACIST, proposal));
        assertTrue(SubstitutionRules.isParty(SubstitutionFixtures.PRESCRIBER, proposal));
        assertFalse(SubstitutionRules.isParty(SubstitutionFixtures.OTHER_CLINICIAN, proposal));
    }
}
