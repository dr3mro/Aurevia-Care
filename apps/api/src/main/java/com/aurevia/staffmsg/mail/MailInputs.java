package com.aurevia.staffmsg.mail;

import java.util.List;
import java.util.UUID;

import com.aurevia.api.ApiException;

/**
 * Boundary validation shared by the mail commands.
 *
 * <p>Every method fails closed with a machine-readable command error so a caller
 * cannot turn a missing or blank value into a stored message. Identifiers are
 * generated server side; clients only ever supply an opaque idempotency key.</p>
 */
public final class MailInputs {

    private MailInputs() {
    }

    /**
     * Require a non-blank text value.
     *
     * @param value   value to check
     * @param code    error code when the value is missing
     * @param message non-sensitive detail for the error body
     * @return the original value
     * @throws ApiException {@code 422} when the value is null or blank
     */
    public static String requireText(String value, String code, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, code, message);
        }
        return value;
    }

    /**
     * Require a positive one-based version.
     *
     * @param value   version to check
     * @param code    error code when the version is missing
     * @param message non-sensitive detail for the error body
     * @return the original version
     * @throws ApiException {@code 422} when the version is less than one
     */
    public static int requirePositive(int value, String code, String message) {
        if (value < 1) {
            throw new ApiException(422, code, message);
        }
        return value;
    }

    /**
     * Normalize an optional client list to an immutable list.
     *
     * @param value client-supplied list, possibly null
     * @param <T>   element type
     * @return an immutable list, empty when the client omitted the field
     */
    public static <T> List<T> orEmpty(List<T> value) {
        if (value == null) {
            return List.of();
        }
        return List.copyOf(value);
    }

    /**
     * Freeze an optional client list, preserving an omitted field as null.
     *
     * @param value client-supplied list, possibly null
     * @param <T>   element type
     * @return an immutable copy, or null when the field was omitted
     */
    public static <T> List<T> freeze(List<T> value) {
        return value == null ? null : List.copyOf(value);
    }

    /**
     * Generate an opaque, tenant-local identifier.
     *
     * @return a fresh random UUID as text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
