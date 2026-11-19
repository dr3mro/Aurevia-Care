package com.aurevia.smartclinic.messaging.governance;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.AuditAction;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;

/**
 * Pure, total validation of every MSG-07 governance command field.
 *
 * <p>Each method either returns a normalized value or raises a {@code 422}
 * before any domain or store call, so a malformed identifier cannot reach a
 * durable record. The audit trail itself carries no user-supplied clinical
 * text: reason, purpose and scope are bounded non-sensitive strings.</p>
 */
public final class MessagingGovernanceInputs {

    private static final int MAX_SCOPE_LENGTH = 128;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Allowlisted, non-sensitive export resource families. */
    private static final Set<String> EXPORT_SCOPES = Set.of(
            "conversation", "message", "instruction", "attachment", "report", "audit");

    private MessagingGovernanceInputs() {
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
     * Validate a required non-sensitive text field.
     *
     * @param value supplied text
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
     * Parse an audited action wire key.
     *
     * @param value supplied action key
     * @return the validated action
     * @throws ApiException when the key is unknown
     */
    public static AuditAction action(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_ACTION", "action is required");
        }
        try {
            return AuditAction.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_ACTION", "unknown audit action");
        }
    }

    /**
     * Parse an outcome wire key.
     *
     * @param value supplied outcome key
     * @return the validated outcome
     * @throws ApiException when the key is unknown
     */
    public static Outcome outcome(String value) {
        if (value == null) {
            throw new ApiException(422, "INVALID_OUTCOME", "outcome is required");
        }
        try {
            return Outcome.from(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_OUTCOME", "unknown audit outcome");
        }
    }

    /**
     * Validate an export scope against the allowlisted resource families.
     *
     * @param value supplied scope
     * @return the validated scope
     * @throws ApiException when the scope is not an allowed non-sensitive family
     */
    public static String exportScope(String value) {
        String scope = text(value, "scope", MAX_SCOPE_LENGTH);
        if (!EXPORT_SCOPES.contains(scope)) {
            throw new ApiException(422, "INVALID_SCOPE", "scope must be an allowed resource family");
        }
        return scope;
    }

    /**
     * Create a new opaque governance fact identifier.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
