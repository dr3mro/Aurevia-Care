package com.aurevia.pharmacyflow.substitution;

import java.util.List;

import com.aurevia.prescription.PrescriptionContract.AllergyAssessment;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DrugEntry;

/** Deterministic in-test implementation of the pharmacy read port. */
final class FakeSubstitutionCatalog implements SubstitutionCatalog {

    private Draft draft = SubstitutionFixtures.draft();
    private List<DrugEntry> entries = SubstitutionFixtures.entries();
    private AllergyAssessment allergies = SubstitutionFixtures.noAllergies();
    private String route = "oral";

    @Override
    public Draft draft(String prescriptionId) {
        return draft;
    }

    @Override
    public List<DrugEntry> entries() {
        return entries;
    }

    @Override
    public AllergyAssessment allergies(String patientId) {
        return allergies;
    }

    @Override
    public String route(String entryId, int version) {
        return route;
    }

    void withDraft(Draft draft) {
        this.draft = draft;
    }

    void withEntries(List<DrugEntry> entries) {
        this.entries = entries;
    }

    void withAllergies(AllergyAssessment allergies) {
        this.allergies = allergies;
    }

    void withRoute(String route) {
        this.route = route;
    }
}
