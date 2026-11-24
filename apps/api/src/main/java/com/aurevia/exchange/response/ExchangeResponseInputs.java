package com.aurevia.exchange.response;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * Trust-boundary validation for XCH-03 commands (ARC-03).
 *
 * <p>Every value is trimmed, bounded and checked here before a transaction
 * opens, so an invalid command never reaches durable state. Optional blank
 * values become {@code null}.</p>
 */
public final class ExchangeResponseInputs {

    /** Approved retention policies; a legal hold suspends scheduled expiry. */
    public static final Set<String> RETENTION_POLICIES = Set.of("STANDARD", "EXTENDED", "LEGAL_HOLD");

    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-fA-F]{64}");

    private ExchangeResponseInputs() {
    }

    /**
     * Validate an opaque identifier.
     *
     * @param value value to check
     * @param field field name used in the error message
     * @return the validated identifier
     * @throws ApiException {@code 422} when malformed
     */
    public static String uuid(String value, String field) {
        if (value == null) {
            throw invalid(field);
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(field);
        }
        return value;
    }

    /**
     * Validate a bounded free-text value.
     *
     * @param value    value to check
     * @param field    field name used in the error message
     * @param max      maximum trimmed length
     * @param required whether a missing or blank value is an error
     * @return the trimmed value, or {@code null} when optional and blank
     * @throws ApiException {@code 422} when required and missing or when too long
     */
    public static String text(String value, String field, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new ApiException(422, "REQUIRED", field + " is required");
            }
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ApiException(422, "INVALID_INPUT", field + " is too long");
        }
        return trimmed;
    }

    /**
     * Validate a bounded code such as a permitted purpose or retention policy.
     *
     * @param value value to check
     * @param field field name used in the error message
     * @return the trimmed code
     * @throws ApiException {@code 422} when malformed
     */
    public static String code(String value, String field) {
        String trimmed = text(value, field, 64, true);
        if (!CODE.matcher(trimmed).matches()) {
            throw new ApiException(422, "INVALID_INPUT", field + " has invalid characters");
        }
        return trimmed;
    }

    /**
     * Validate a lowercase or uppercase hex content checksum.
     *
     * @param value value to check
     * @return the trimmed checksum
     * @throws ApiException {@code 422} when malformed
     */
    public static String checksum(String value) {
        String trimmed = text(value, "contentChecksum", 64, true);
        if (!CHECKSUM.matcher(trimmed).matches()) {
            throw new ApiException(422, "INVALID_CHECKSUM", "contentChecksum must be a sha-256 digest");
        }
        return trimmed;
    }

    /**
     * Validate an approved retention policy.
     *
     * @param value value to check
     * @return the policy code
     * @throws ApiException {@code 422} when the policy is not approved
     */
    public static String retentionPolicy(String value) {
        String policy = code(value, "retentionPolicy");
        if (!RETENTION_POLICIES.contains(policy)) {
            throw new ApiException(422, "INVALID_RETENTION_POLICY", "retentionPolicy is not approved");
        }
        return policy;
    }

    /**
     * Generate a fresh opaque identifier for a new ledger row.
     *
     * @return a random UUID as text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    private static ApiException invalid(String field) {
        return new ApiException(422, "INVALID_INPUT", field + " is malformed");
    }
}
