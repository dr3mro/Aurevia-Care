package com.aurevia.patient;

import java.util.List;

/**
 * A patient together with synthetic likely-duplicate candidates (IAM-03).
 *
 * @return this record
 */
public record PatientView(Patient patient, List<Patient> duplicates) {

    /**
     * Freeze the duplicate list so it cannot be mutated in memory.
     *
     * @throws NullPointerException when duplicates is null
     */
    public PatientView {
        duplicates = List.copyOf(duplicates);
    }

    /**
     * Return the immutable duplicate candidates.
     *
     * @return likely duplicates ordered by identifier
     */
    @Override
    public List<Patient> duplicates() {
        return List.copyOf(duplicates);
    }
}
