package com.aurevia.smartclinic.g1.s_g1_04;

import java.util.List;

/**
 * Outcome of validating a note against its exact template version.
 *
 * @param valid      {@code true} when no violations were found
 * @param violations machine-readable violation keys, empty when valid
 */
public record ValidationReport(boolean valid, List<String> violations) {

    /**
     * Freeze the violation list so a report cannot be mutated.
     *
     * @throws NullPointerException when violations is null
     */
    public ValidationReport {
        violations = List.copyOf(violations);
    }

    /**
     * Return the immutable violation keys.
     *
     * @return violation keys
     */
    @Override
    public List<String> violations() {
        return List.copyOf(violations);
    }
}
