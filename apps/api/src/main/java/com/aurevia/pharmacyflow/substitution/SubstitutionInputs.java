package com.aurevia.pharmacyflow.substitution;

import java.util.UUID;

import com.aurevia.api.ApiException;

/**
 * Pure boundary validation for pharmacy substitution commands (RX-01, RX-02, ARC-03).
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * before any proposal or revision is recorded, so malformed identifiers,
 * reasons or notes can never reach the substitution trail.</p>
 */
public final class SubstitutionInputs {

    /** Maximum length of an optional pharmacy or prescriber note. */
    private static final int MAX_NOTE_LENGTH = 500;

    private SubstitutionInputs() {
    }

    /**
     * Require a non-blank text field at the trust boundary.
     *
     * @param value   supplied value
     * @param code    stable error code for a rejection
     * @param message non-sensitive rejection message
     * @throws ApiException when the value is null or blank
     */
    public static void requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, message);
        }
    }

    /**
     * Require a positive version precondition.
     *
     * @param value   supplied version
     * @param code    stable error code for a rejection
     * @param message non-sensitive rejection message
     * @throws ApiException when the version is below one
     */
    public static void requirePositive(int value, String code, String message) {
        if (value < 1) {
            throw new ApiException(422, code, message);
        }
    }

    /**
     * Normalize an optional note, rejecting only values longer than the bound.
     *
     * @param value   supplied note, possibly null
     * @param code    stable error code for a rejection
     * @param message non-sensitive rejection message
     * @return the original note or null
     * @throws ApiException when the note exceeds the bound
     */
    public static String optionalNote(String value, String code, String message) {
        if (value != null && value.length() > MAX_NOTE_LENGTH) {
            throw new ApiException(422, code, message);
        }
        return value;
    }

    /**
     * Create an opaque identifier for a proposal or signed revision.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
