package com.aurevia.pharmacyflow.substitution;

import java.time.Instant;
import java.util.List;

import com.aurevia.prescription.PrescriptionContract.Alternative;
import com.aurevia.prescription.PrescriptionContract.AlternativeKind;
import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.CheckOutcome;
import com.aurevia.prescription.PrescriptionContract.AllergyEntry;
import com.aurevia.prescription.PrescriptionContract.AllergyStatus;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;
import com.aurevia.prescription.PrescriptionContract.Ingredient;

/** Synthetic catalog, draft and allergy fixtures shared by the substitution tests. */
final class SubstitutionFixtures {

    static final Instant AT = Instant.parse("2026-11-21T12:00:00Z");
    static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    static final String ORG = "11111111-1111-1111-1111-111111111111";
    static final String OTHER_ORG = "99999999-9999-9999-9999-999999999999";
    static final String PRESCRIPTION = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "patient-1";
    static final String PHARMACIST = "pharm-substitution";
    static final String PRESCRIBER = "dr-substitution";
    static final String OTHER_CLINICIAN = "dr-other-substitution";
    static final String ORIGINAL_ID = "drug-original";
    static final String ALTERNATIVE_ID = "drug-alternative";
    static final String OTHER_DRUG_ID = "drug-unrelated";
    static final String ATTESTATION = "I reviewed the proposed medicine, strength and route";

    private SubstitutionFixtures() {
    }

    static DrugEntry original() {
        return drug(ORIGINAL_ID, "Paracetamol", "Panadol", "tablet", "500", "mg",
                List.of(new Ingredient("sub-paracetamol", "analgesic", 1)));
    }

    static DrugEntry alternative() {
        return drug(ALTERNATIVE_ID, "Paracetamol", "Calpol", "tablet", "500", "mg",
                List.of(new Ingredient("sub-paracetamol", "analgesic", 1)));
    }

    static DrugEntry strongerAlternative() {
        return drug(ALTERNATIVE_ID, "Paracetamol", "Calpol", "tablet", "50", "mg",
                List.of(new Ingredient("sub-paracetamol", "analgesic", 1)));
    }

    static DrugEntry alternativeWithDifferentUnit() {
        return drug(ALTERNATIVE_ID, "Paracetamol", "Calpol", "tablet", "500", "mcg",
                List.of(new Ingredient("sub-paracetamol", "analgesic", 1)));
    }

    static DrugEntry differentIdentityAlternative() {
        return drug(ALTERNATIVE_ID, "Ibuprofen", "Nurofen", "tablet", "500", "mg",
                List.of(new Ingredient("sub-ibuprofen", "nsaid", 1)));
    }

    static DrugEntry unrelated() {
        return drug(OTHER_DRUG_ID, "Amoxicillin", "Amoxil", "capsule", "250", "mg",
                List.of(new Ingredient("sub-amoxicillin", "antibiotic", 1)));
    }

    private static DrugEntry drug(String id, String generic, String trade, String formulation,
                                  String strengthValue, String strengthUnit, List<Ingredient> ingredients) {
        return new DrugEntry(id, 1, generic, trade, formulation, strengthValue, strengthUnit,
                ingredients, true, FROM, null, "catalog-v3-approved");
    }

    static Alternative tagged(DrugEntry entry) {
        return new Alternative(AlternativeKind.SUGGESTION, entry, "same generic name",
                "catalog-v3-approved", true);
    }

    static List<DrugEntry> entries() {
        return List.of(original(), alternative(), unrelated());
    }

    static Draft draft() {
        return new Draft(PRESCRIPTION, 1, PATIENT, "draft", ORIGINAL_ID, 1, "Paracetamol",
                "500", "mg", "oral", "twice daily", "60", "tablet", null,
                "take with water", "pharmacy-a", null, PRESCRIBER, FROM);
    }

    static AllergyAssessment noAllergies() {
        return new AllergyAssessment(AllergyStatus.VERIFIED_NONE, List.of());
    }

    static AllergyCheck noConflictCheck() {
        return new AllergyCheck(CheckOutcome.NO_CONFLICT, AllergyStatus.VERIFIED_NONE, List.of(),
                "catalog-v3-approved");
    }

    static AllergyAssessment conflictingAllergies() {
        return new AllergyAssessment(AllergyStatus.VERIFIED_LIST, List.of(new AllergyEntry(
                "sub-paracetamol", null, 0, "verified", "synthetic-chart", FROM)));
    }
}
