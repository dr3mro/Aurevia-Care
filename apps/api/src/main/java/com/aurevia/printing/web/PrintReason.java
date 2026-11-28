package com.aurevia.printing.web;

import com.aurevia.api.ApiException;

/**
 * The closed reason list recorded with every patient-handout disclosure.
 *
 * <p>A reason explains <em>why</em> a handout was produced or downloaded without
 * recording <em>what</em> it contained. The wire keys are stable and the list is
 * deliberately small so an audit entry cannot be used to smuggle free text.</p>
 */
public enum PrintReason {

    /** The handout is handed directly to the patient. */
    GIVE_TO_PATIENT("give_to_patient"),
    /** The handout supports a referral to another clinician or unit. */
    REFERRAL("referral"),
    /** The patient explicitly asked for a copy. */
    PATIENT_REQUEST("patient_request");

    private final String wire;

    PrintReason(String wire) {
        this.wire = wire;
    }

    /**
     * Return the stable wire key of this reason.
     *
     * @return lowercase reason key
     */
    public String wire() {
        return wire;
    }

    /**
     * Parse one caller-supplied reason, failing closed on anything outside the list.
     *
     * @param value wire key from the request, possibly {@code null}
     * @return the matching approved reason
     * @throws ApiException with 422 when the reason is missing or unknown
     */
    public static PrintReason parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_REASON", "a print reason is required");
        }
        for (PrintReason candidate : values()) {
            if (candidate.wire.equals(value)) {
                return candidate;
            }
        }
        throw new ApiException(422, "INVALID_REASON", "unknown print reason");
    }
}
