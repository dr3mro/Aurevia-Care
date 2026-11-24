package com.aurevia.pharmacyflow.status;

import java.time.Instant;
import java.util.UUID;

import com.aurevia.api.ApiException;

/**
 * Pure, total validation of every pharmacy status command field (RX-02, ARC-03).
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * before any state changes, so a malformed identifier, actor, reason or time can
 * never reach an append-only trail. The checks are deliberately small and shared
 * by all four status facts so a rule cannot drift between accept, reject,
 * clarification and dispensing evidence.</p>
 */
public final class PharmacyStatusInputs {

    private PharmacyStatusInputs() {
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
     * Require an occurrence time.
     *
     * @param value   supplied instant
     * @param code    stable error code for a rejection
     * @param message non-sensitive rejection message
     * @throws ApiException {@code 422} when the value is null
     */
    public static void requireInstant(Instant value, String code, String message) {
        if (value == null) {
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
     * Create an opaque identifier for a new status event.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
