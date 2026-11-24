package com.aurevia.pharmacyflow.substitution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Proposal;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.ProposalView;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.Reason;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.SignedRevision;
import com.aurevia.pharmacyflow.substitution.SubstitutionContract.State;
import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;

/** Domain types: wire keys, provenance validation and defensive copies. */
class SubstitutionContractTest {

    @Test
    void reasonWireKeysParseAndUnknownFails() {
        assertEquals(Reason.STOCK, Reason.from("stock"));
        assertEquals(Reason.BRAND, Reason.from("brand"));
        assertEquals(Reason.FORMULATION, Reason.from("formulation"));
        assertEquals("formulation", Reason.FORMULATION.wire());
        ApiException failure = assertThrows(ApiException.class, () -> Reason.from("colour"));
        assertEquals(422, failure.status());
        assertEquals("INVALID_SUBSTITUTION_REASON", failure.code());
    }

    @Test
    void stateWireKeysAreStable() {
        assertEquals("pending", State.PENDING.wire());
        assertEquals("approved", State.APPROVED.wire());
        assertEquals("declined", State.DECLINED.wire());
    }

    @Test
    void proposalRejectsMissingIdentityReasonCheckAndState() {
        assertEquals(422, assertThrows(ApiException.class, () -> proposal(null, Reason.STOCK, State.PENDING)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> proposal("p", null, State.PENDING)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> proposalWithCheck("p", Reason.STOCK, State.PENDING, null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> proposal("p", Reason.STOCK, null)).status());
    }

    @Test
    void proposalRejectsMissingOriginalAndProposedEntries() {
        assertEquals(422, assertThrows(ApiException.class, () -> new Proposal("p", SubstitutionFixtures.ORG,
                SubstitutionFixtures.PRESCRIPTION, SubstitutionFixtures.PATIENT,
                SubstitutionFixtures.PRESCRIBER, SubstitutionFixtures.PHARMACIST, null,
                SubstitutionFixtures.alternative(), Reason.STOCK, null, null, check(), State.PENDING, 1,
                SubstitutionFixtures.AT)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Proposal("p", SubstitutionFixtures.ORG,
                SubstitutionFixtures.PRESCRIPTION, SubstitutionFixtures.PATIENT,
                SubstitutionFixtures.PRESCRIBER, SubstitutionFixtures.PHARMACIST,
                SubstitutionFixtures.original(), null, Reason.STOCK, null, null, check(), State.PENDING, 1,
                SubstitutionFixtures.AT)).status());
    }

    @Test
    void signedRevisionRejectsMissingProvenanceAttestationCheckAndEntries() {
        assertEquals(422, assertThrows(ApiException.class, () -> revision(null, "catalog-v3", "attested")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> revision("r", null, "attested")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> revision("r", "catalog-v3", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new SignedRevision("r",
                SubstitutionFixtures.PRESCRIPTION, "p", 1, null, SubstitutionFixtures.alternative(),
                Reason.STOCK, SubstitutionFixtures.PRESCRIBER, check(), "attested", "catalog-v3",
                SubstitutionFixtures.AT)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new SignedRevision("r",
                SubstitutionFixtures.PRESCRIPTION, "p", 1, SubstitutionFixtures.original(), null,
                Reason.STOCK, SubstitutionFixtures.PRESCRIBER, check(), "attested", "catalog-v3",
                SubstitutionFixtures.AT)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new SignedRevision("r",
                SubstitutionFixtures.PRESCRIPTION, "p", 1, SubstitutionFixtures.original(),
                SubstitutionFixtures.alternative(), Reason.STOCK, SubstitutionFixtures.PRESCRIBER,
                null, "attested", "catalog-v3", SubstitutionFixtures.AT)).status());
    }

    @Test
    void proposalViewRequiresProposalAndFreezesAlternatives() {
        List<Alternative> supplied = new ArrayList<>(
                List.of(SubstitutionFixtures.tagged(SubstitutionFixtures.alternative())));
        ProposalView view = new ProposalView(proposal("p", Reason.STOCK, State.PENDING), null, supplied);
        supplied.clear();
        assertEquals(1, view.alternatives().size());
        assertThrows(UnsupportedOperationException.class, () -> view.alternatives().clear());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new ProposalView(null, null, List.of())).status());
    }

    private static AllergyCheck check() {
        return new AllergyCheck(CheckOutcome.NO_CONFLICT, AllergyStatus.VERIFIED_NONE, List.of(),
                "catalog-v3-approved");
    }

    private static Proposal proposal(String id, Reason reason, State state) {
        return proposalWithCheck(id, reason, state, check());
    }

    private static Proposal proposalWithCheck(String id, Reason reason, State state, AllergyCheck allergyCheck) {
        return new Proposal(id, SubstitutionFixtures.ORG, SubstitutionFixtures.PRESCRIPTION,
                SubstitutionFixtures.PATIENT, SubstitutionFixtures.PRESCRIBER,
                SubstitutionFixtures.PHARMACIST, SubstitutionFixtures.original(),
                SubstitutionFixtures.alternative(), reason, null, null, allergyCheck,
                state, 1, SubstitutionFixtures.AT);
    }

    private static SignedRevision revision(String id, String provenance, String attestation) {
        return new SignedRevision(id, SubstitutionFixtures.PRESCRIPTION, "p", 1,
                SubstitutionFixtures.original(), SubstitutionFixtures.alternative(), Reason.STOCK,
                SubstitutionFixtures.PRESCRIBER, check(), attestation, provenance, SubstitutionFixtures.AT);
    }
}
