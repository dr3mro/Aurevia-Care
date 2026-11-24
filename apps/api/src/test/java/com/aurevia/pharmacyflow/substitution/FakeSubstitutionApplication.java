package com.aurevia.pharmacyflow.substitution;

import com.aurevia.prescription.PrescriptionContract.DrugEntry;

/** In-test recorder for the authoritative prescription application port. */
final class FakeSubstitutionApplication implements SubstitutionApplication {

    private int calls;
    private DrugEntry lastReplacement;
    private RuntimeException failure;

    @Override
    public void apply(String organizationId, String subject, String prescriptionId, int expectedVersion,
                      DrugEntry replacement) {
        if (failure != null) {
            throw failure;
        }
        calls++;
        lastReplacement = replacement;
    }

    int calls() {
        return calls;
    }

    DrugEntry lastReplacement() {
        return lastReplacement;
    }

    void failWith(RuntimeException exception) {
        this.failure = exception;
    }
}
