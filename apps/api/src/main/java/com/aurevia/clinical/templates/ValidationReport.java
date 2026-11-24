package com.aurevia.clinical.templates;

import java.util.List;

/**
 * Outcome of validating a note against its exact template version.
 *
 * @return this record
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
