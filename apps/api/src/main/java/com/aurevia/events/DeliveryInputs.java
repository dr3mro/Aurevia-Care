package com.aurevia.events;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.Sha256Hex;
import com.aurevia.events.DurableEventContract.AuditAction;
import com.aurevia.events.DurableEventContract.DeliveryState;
import com.aurevia.events.DurableEventContract.Outcome;
import com.aurevia.events.DurableEventContract.ProcessingOutcome;
import com.aurevia.events.DurableEventContract.Resolution;

/**
 * Total, pure validation of every ARC-05 delivery command field.
 *
 * <p>Each method returns a normalized value or raises a {@code 422} before any
 * domain or store call, so a malformed identifier, digest or wire key can never
 * reach a durable record. Fields that would carry clinical content are reduced
 * to a fixed-length digest at the trust boundary.</p>
 */
public final class DeliveryInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int MAX_TEXT_LENGTH = 256;

    private DeliveryInputs() {
    }

    /**
     * Validate an opaque tenant-owned identifier.
     *
     * @param value caller value
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
     * Validate a required non-sensitive text field.
     *
     * @param value caller value
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return the trimmed text
     * @throws ApiException when absent, blank or too long
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
     * Validate a SHA-256 payload digest, normalizing uppercase hex to lowercase.
     *
     * @param value caller value
     * @return the validated digest in canonical lowercase hex
     * @throws ApiException when the value is not 64 hexadecimal characters
     */
    public static String digest(String value) {
        try {
            return Sha256Hex.sha256Hex(value).value();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_DIGEST", "payloadDigest must be 64 hex characters");
        }
    }

    /**
     * Validate a positive aggregate version.
     *
     * @param value caller value
     * @param field field name used in the error code
     * @return the validated version
     * @throws ApiException when the value is not positive
     */
    public static int version(int value, String field) {
        if (value < 1) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a positive integer");
        }
        return value;
    }

    /**
     * Validate a bounded, non-sensitive key or namespace token.
     *
     * @param value caller value
     * @param field field name used in the error code
     * @return the trimmed token
     * @throws ApiException when the value contains unsupported characters or is blank
     */
    public static String token(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || !TOKEN_PATTERN.matcher(normalized).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a bounded non-sensitive token");
        }
        return normalized;
    }

    /**
     * Validate an optional external identifier, allowing {@code null}.
     *
     * @param value caller value, possibly {@code null}
     * @param field field name used in the error code
     * @return {@code null} or the validated UUID
     * @throws ApiException when the value is present but not a UUID
     */
    public static String optionalUuid(String value, String field) {
        return value == null ? null : uuid(value, field);
    }

    /**
     * Parse a delivery state wire key.
     *
     * @param value wire key
     * @return the validated state
     * @throws ApiException when the key is unknown
     */
    public static DeliveryState state(String value) {
        try {
            return DeliveryState.from(text(value, "state", MAX_TEXT_LENGTH));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_STATE", "unknown delivery state");
        }
    }

    /**
     * Parse a processing outcome wire key.
     *
     * @param value wire key
     * @return the validated processing outcome
     * @throws ApiException when the key is unknown
     */
    public static ProcessingOutcome processingOutcome(String value) {
        try {
            return ProcessingOutcome.from(text(value, "outcome", MAX_TEXT_LENGTH));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_OUTCOME", "unknown processing outcome");
        }
    }

    /**
     * Parse an audit action wire key.
     *
     * @param value wire key
     * @return the validated action
     * @throws ApiException when the key is unknown
     */
    public static AuditAction action(String value) {
        try {
            return AuditAction.from(text(value, "action", MAX_TEXT_LENGTH));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_ACTION", "unknown audit action");
        }
    }

    /**
     * Parse an audit outcome wire key.
     *
     * @param value wire key
     * @return the validated outcome
     * @throws ApiException when the key is unknown
     */
    public static Outcome outcome(String value) {
        try {
            return Outcome.from(text(value, "outcome", MAX_TEXT_LENGTH));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_OUTCOME", "unknown audit outcome");
        }
    }

    /**
     * Parse a dead-letter resolution wire key.
     *
     * @param value wire key
     * @return the validated resolution
     * @throws ApiException when the key is unknown
     */
    public static Resolution resolution(String value) {
        try {
            return Resolution.from(text(value, "resolution", MAX_TEXT_LENGTH));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_RESOLUTION", "unknown dead-letter resolution");
        }
    }

    /**
     * Create a new opaque delivery fact identifier.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
