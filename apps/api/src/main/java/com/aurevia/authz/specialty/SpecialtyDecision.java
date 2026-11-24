package com.aurevia.authz.specialty;

/**
 * The three clinical decision classes named by PRD-06.
 *
 * <p>A decision is never caller-supplied on the attestation command: it is
 * derived by {@link com.aurevia.authz.specialty.SpecialtyActions#decisionFor(String)}
 * from the action the
 * human is attesting. This keeps the audit evidence trace unambiguous.</p>
 */
public enum SpecialtyDecision {

    /** A diagnosis or signed interpretation. */
    DIAGNOSIS("DIAGNOSIS"),
    /** A treatment decision, referral acceptance or discharge. */
    TREATMENT("TREATMENT"),
    /** Authorization to release a result or report to the patient. */
    RELEASE("RELEASE");

    private final String wire;

    /**
     * Create a decision with its stable wire key.
     *
     * @param wire uppercase decision key
     */
    SpecialtyDecision(String wire) {
        this.wire = wire;
    }

    /**
     * Return the stable wire value stored in the database.
     *
     * @return uppercase decision key
     */
    public String wire() {
        return wire;
    }

    /**
     * Parse a stored wire value.
     *
     * @param value persisted decision key
     * @return the matching decision
     * @throws IllegalArgumentException when the value is not a known decision
     */
    public static SpecialtyDecision from(String value) {
        for (SpecialtyDecision decision : values()) {
            if (decision.wire.equals(value)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("unknown specialty decision: " + value);
    }
}
