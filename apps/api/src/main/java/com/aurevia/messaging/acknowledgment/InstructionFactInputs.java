package com.aurevia.messaging.acknowledgment;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * Pure, total validation of every MSG-05 command field.
 *
 * <p>Each method either returns a normalized value or raises a {@code 422} with
 * a stable code before any domain or store access. No clinical text is accepted
 * by this layer: only bounded identifiers, an opaque content reference and an
 * optional due instant.</p>
 */
public final class InstructionFactInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private InstructionFactInputs() {
    }

    /**
     * Validate an opaque tenant-owned identifier.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated value
     * @throws ApiException when the value is not a UUID
     */
    public static String uuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a UUID");
        }
        return value;
    }

    /**
     * Validate a bounded opaque text value such as a content reference.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return the trimmed text
     * @throws ApiException when the value is absent, blank or too long
     */
    public static String text(String value, String field, int max) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > max) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 1-" + max + " characters");
        }
        return normalized;
    }

    /**
     * Validate a positive immutable revision number.
     *
     * @param value supplied revision
     * @return the validated revision
     * @throws ApiException when the revision is not positive
     */
    public static int version(int value) {
        if (value < 1) {
            throw new ApiException(422, "INVALID_REVISION", "revision must be positive");
        }
        return value;
    }

    /**
     * Parse and validate an optional acknowledgment due instant.
     *
     * @param value ISO-8601 instant, or {@code null} when no acknowledgment is requested
     * @param field field name used in the error code
     * @return the parsed instant, or {@code null}
     * @throws ApiException when the value is present but not an ISO-8601 instant
     */
    public static Instant dueAt(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be an ISO-8601 instant");
        }
    }

    /**
     * Create an opaque identifier for a new fact.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
