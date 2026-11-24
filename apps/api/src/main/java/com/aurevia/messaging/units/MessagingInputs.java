package com.aurevia.messaging.units;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;

/**
 * Pure, total validation of every MSG-01 / ARC-07 command field.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any domain or store call, so a malformed identifier
 * or context can never reach a durable record.</p>
 */
public final class MessagingInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private MessagingInputs() {
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
     * Validate a required human-readable text field.
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
     * Parse and validate a verified relationship context.
     *
     * @param value supplied context wire key
     * @return the validated context
     * @throws ApiException when the key does not name an eligible relationship kind
     */
    public static ConnectionContext context(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_CONTEXT", "context must be a verified relationship kind");
        }
        try {
            return ConnectionContext.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_CONTEXT", "context must be a verified relationship kind");
        }
    }

    /**
     * Create an opaque identifier for a new connection.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
