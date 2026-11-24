package com.aurevia.messaging.instructions;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * Bounded validation of MSG-04 inputs at the trust boundary.
 *
 * <p>Every method fails closed with a machine-readable 422. Nothing here
 * interprets clinical content; it only constrains shape, length and time so
 * the domain service can work with already-safe values.</p>
 */
public final class InstructionInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("[a-z]{2}(-[A-Z]{2}|-[A-Z][a-z]{3})?");
    private static final int MAX_ATTACHMENTS = 3;

    private InstructionInputs() {
    }

    /**
     * Validate an opaque identifier supplied as text.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the unchanged identifier
     * @throws ApiException when the value is not a bounded UUID text
     */
    public static String uuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw invalid(field, "must be an opaque identifier");
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
            throw invalid(field, "must be 1-" + max + " characters");
        }
        return normalized;
    }

    /**
     * Validate a tenant-local key such as a unit or template identifier.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated key
     * @throws ApiException when the value is not a bounded lowercase key
     */
    public static String key(String value, String field) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw invalid(field, "must be a lowercase, bounded key");
        }
        return value;
    }

    /**
     * Normalize a BCP-47 language tag, defaulting to English.
     *
     * @param value supplied tag, or {@code null}
     * @return the validated tag
     * @throws ApiException when the tag is malformed
     */
    public static String language(String value) {
        String normalized = value == null || value.isBlank() ? "en" : value.trim();
        if (!LANGUAGE_PATTERN.matcher(normalized).matches()) {
            throw invalid("language", "must be a two-letter language tag");
        }
        return normalized;
    }

    /**
     * Parse an ISO-8601 instant.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the parsed instant
     * @throws ApiException when the value is not an instant
     */
    public static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw invalid(field, "must be an ISO-8601 instant");
        }
    }

    /**
     * Parse an optional action due date that must be in the future.
     *
     * @param value value supplied by the caller, or {@code null}
     * @param now   current application instant
     * @return the validated instant, or {@code null} when absent
     * @throws ApiException when the value is not a future instant
     */
    public static Instant dueAt(String value, Instant now) {
        if (value == null) {
            return null;
        }
        Instant due = instant(value, "actionDueAt");
        if (!due.isAfter(now)) {
            throw new ApiException(422, "INVALID_ACTIONDUEAT", "actionDueAt must be in the future");
        }
        return due;
    }

    /**
     * Validate a bounded list of secure attachment identifiers.
     *
     * @param values supplied identifiers, or {@code null}
     * @return an immutable list of validated identifiers
     * @throws ApiException when the list is too large or an identifier is invalid
     */
    public static List<String> attachments(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > MAX_ATTACHMENTS) {
            throw new ApiException(422, "TOO_MANY_ATTACHMENTS",
                    "at most " + MAX_ATTACHMENTS + " attachments are allowed");
        }
        List<String> copy = new java.util.ArrayList<>(values.size());
        for (String value : values) {
            copy.add(key(value, "attachmentId"));
        }
        return List.copyOf(copy);
    }

    /**
     * Create an opaque thread identifier.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    private static ApiException invalid(String field, String requirement) {
        return new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                field + " " + requirement);
    }
}
