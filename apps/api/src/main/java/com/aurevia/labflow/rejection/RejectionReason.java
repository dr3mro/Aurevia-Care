package com.aurevia.labflow.rejection;

import com.aurevia.api.ApiException;

/**
 * Bounded reasons a collected specimen may be rejected for (LAB-01).
 *
 * <p>Five stable wire values are accepted. The {@code other} value is the only
 * one that must carry free-text detail, which is retained on the rejected
 * specimen together with the reason code so the clinical record keeps why the
 * sample was not usable.</p>
 */
public enum RejectionReason {

    /** The sample haemolysed during collection or transport. */
    HAEMOLYSED("haemolysed"),
    /** The wrong collection tube was used. */
    WRONG_TUBE("wrong_tube"),
    /** The sample arrived without an identifiable label. */
    UNLABELLED("unlabelled"),
    /** Not enough sample was collected for the requested tests. */
    INSUFFICIENT_VOLUME("insufficient_volume"),
    /** Another reason, described by bounded free text. */
    OTHER("other");

    private final String wire;

    RejectionReason(String wire) {
        this.wire = wire;
    }

    /**
     * Parse a client-supplied reason code.
     *
     * @param value reason code, never {@code null} or blank
     * @return the matching rejection reason
     * @throws com.aurevia.api.ApiException when the value is missing or unrecognised
     */
    public static RejectionReason fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "REASON_REQUIRED", "a rejection reason is required");
        }
        for (RejectionReason candidate : values()) {
            if (candidate.wire.equals(value)) {
                return candidate;
            }
        }
        throw new ApiException(422, "INVALID_REASON", "rejection reason is not recognised");
    }

    /**
     * Decide whether this reason needs the free-text detail field.
     *
     * @return {@code true} only for the {@code other} reason
     */
    public boolean requiresDetail() {
        return this == OTHER;
    }

    /**
     * Return the stable wire value stored with the specimen.
     *
     * @return the lowercase reason code
     */
    public String wire() {
        return wire;
    }
}
