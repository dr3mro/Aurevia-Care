package com.aurevia.smartclinic.patient;

import java.util.List;

/**
 * A patient together with synthetic likely-duplicate candidates (IAM-03).
 *
 * @param patient     the effective patient
 * @param duplicates  candidates sharing normalized name and DOB; never auto-linked
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
