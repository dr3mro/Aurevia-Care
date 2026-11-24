package com.aurevia.messaging.conversations;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.Subject;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;

/**
 * Pure, total validation of every MSG-02 command field.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any SQL runs, so a malformed identifier or oversized
 * payload can never reach a database cast or a durable write.</p>
 */
public final class ConversationInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final int MAX_BODY = 4000;
    private static final int MAX_REASON = 500;

    private ConversationInputs() {
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
     * Validate a bounded opaque unit or connection identifier.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated value
     * @throws ApiException when the value is absent or has an invalid shape
     */
    public static String opaqueId(String value, String field) {
        if (value == null || !OPAQUE_ID.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a bounded identifier");
        }
        return value;
    }

    /**
     * Validate a lowercase subject key against the one canonical rule.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated subject
     * @throws ApiException when the value is not a bounded lowercase subject
     */
    public static String subject(String value, String field) {
        try {
            return Subject.subject(value).value();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a lowercase subject key");
        }
    }

    /**
     * Validate required message text.
     *
     * @param value supplied body
     * @return the trimmed body
     * @throws ApiException when the body is absent, blank or too long
     */
    public static String body(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > MAX_BODY) {
            throw new ApiException(422, "INVALID_BODY",
                    "body must be 1-" + MAX_BODY + " characters");
        }
        return normalized;
    }

    /**
     * Parse a bounded care-unit key.
     *
     * @param value supplied unit key
     * @return the validated unit key
     * @throws ApiException when the value is not an approved unit key
     */
    public static UnitKey unitKey(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_UNITKEY", "unitKey must be an approved care unit");
        }
        try {
            return UnitKey.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_UNITKEY", "unitKey must be an approved care unit");
        }
    }

    /**
     * Validate a required, bounded reason for a work-item transition.
     *
     * @param value supplied reason
     * @return the trimmed reason
     * @throws ApiException when the reason is absent, blank or too long
     */
    public static String reason(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > MAX_REASON) {
            throw new ApiException(422, "INVALID_REASON",
                    "reason must be 1-" + MAX_REASON + " characters");
        }
        return normalized;
    }

    /**
     * Normalize an optional topic.
     *
     * @param value supplied topic
     * @return the trimmed topic or {@code null}
     * @throws ApiException when the topic is too long
     */
    public static String topic(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 128) {
            throw new ApiException(422, "INVALID_TOPIC", "topic must be 1-128 characters");
        }
        return normalized;
    }

    /**
     * Create an opaque identifier for a new conversation.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
