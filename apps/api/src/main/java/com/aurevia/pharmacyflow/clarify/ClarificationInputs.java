package com.aurevia.pharmacyflow.clarify;

import java.util.UUID;

import com.aurevia.api.ApiException;

/**
 * Pure boundary validation for clarification commands (RX-02, ARC-03).
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * before any state changes, so a malformed identifier, question or version can
 * never reach a thread trail.</p>
 */
public final class ClarificationInputs {

    private ClarificationInputs() {
    }

    /**
     * Require a non-blank text field at the trust boundary.
     *
     * @param value   supplied value
     * @param code    stable error code for a rejection
     * @param message non-sensitive rejection message
     * @throws ApiException {@code 422} when the value is null or blank
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
     * @throws ApiException {@code 422} when the version is below one
     */
    public static void requirePositive(int value, String code, String message) {
        if (value < 1) {
            throw new ApiException(422, code, message);
        }
    }

    /**
     * Create an opaque identifier for a new thread or entry.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
