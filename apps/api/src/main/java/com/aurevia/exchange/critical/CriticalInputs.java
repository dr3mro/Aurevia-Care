package com.aurevia.exchange.critical;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * Pure, total validation of every critical-result command field.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any SQL runs, so a malformed identifier or duration
 * can never reach a database cast or a durable write.</p>
 */
public final class CriticalInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final Pattern CHANNEL = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final int MAX_NOTE = 500;

    private CriticalInputs() {
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
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), field + " must be a UUID");
        }
        return value;
    }

    /**
     * Parse an ISO-8601 instant.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the parsed instant
     * @throws ApiException when the value is absent or not an instant
     */
    public static Instant instant(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be an ISO-8601 instant");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be an ISO-8601 instant");
        }
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
     * Validate a subject key used as a responsible owner or backup recipient.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated subject
     * @throws ApiException when the value is not a bounded lowercase subject
     */
    public static String subject(String value, String field) {
        if (value == null || !SUBJECT.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a lowercase subject key");
        }
        return value;
    }

    /**
     * Validate an approved channel key.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated channel
     * @throws ApiException when the value is not a bounded uppercase channel
     */
    public static String channel(String value, String field) {
        if (value == null || !CHANNEL.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be an uppercase channel key");
        }
        return value;
    }

    /**
     * Validate a notification outcome.
     *
     * @param value supplied outcome
     * @return the validated outcome
     * @throws ApiException when the outcome is neither delivered nor failed
     */
    public static String outcome(String value) {
        if (!"delivered".equals(value) && !"failed".equals(value)) {
            throw new ApiException(422, "INVALID_OUTCOME", "outcome must be delivered or failed");
        }
        return value;
    }

    /**
     * Normalize an optional Boolean flag, treating absence as false.
     *
     * @param value supplied flag
     * @return the effective flag
     */
    public static boolean flag(Boolean value) {
        return Boolean.TRUE.equals(value);
    }

    /**
     * Normalize an optional note.
     *
     * @param value supplied note
     * @param max   maximum accepted length
     * @return the trimmed note or {@code null}
     * @throws ApiException when the note is too long
     */
    public static String note(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > max) {
            throw new ApiException(422, "INVALID_NOTE", "note must be 1-" + max + " characters");
        }
        return normalized;
    }

    /**
     * Parse and validate a future acknowledgment deadline.
     *
     * @param value supplied ISO-8601 instant
     * @param now   current application instant
     * @return the validated future instant
     * @throws ApiException when the value is not an instant in the future
     */
    public static Instant dueAt(String value, Instant now) {
        Instant due = instant(value, "dueAt");
        if (!due.isAfter(now)) {
            throw new ApiException(422, "INVALID_DUE_AT", "dueAt must be in the future");
        }
        return due;
    }

    /**
     * Create an opaque identifier for a new critical result.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
