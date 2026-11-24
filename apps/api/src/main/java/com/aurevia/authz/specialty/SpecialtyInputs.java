package com.aurevia.authz.specialty;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * Pure, total validation of every S-G4-06 command field.
 *
 * <p>Every method returns a normalized value or raises a {@code 422} with a
 * stable code before any SQL runs, so a malformed identifier, action or role
 * can never reach a database cast or a durable write.</p>
 */
public final class SpecialtyInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private SpecialtyInputs() {
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
     * Validate an action key against the approved action vocabulary.
     *
     * @param value supplied action key
     * @return the validated action key
     * @throws ApiException when the action is not known
     */
    public static String action(String value) {
        if (value == null || !SpecialtyActions.ALL_ACTIONS.contains(value)) {
            throw new ApiException(422, "INVALID_ACTION", "action must be an approved specialty action key");
        }
        return value;
    }

    /**
     * Validate a declared role key against the approved role vocabulary.
     * Membership is never inferred from this value; it only names the role the
     * caller claims and is checked against the active-role fold in the service.
     *
     * @param value supplied role key
     * @return the validated role key
     * @throws ApiException when the role is not known
     */
    public static String role(String value) {
        if (value == null || !SpecialtyRoles.ALL.contains(value)) {
            throw new ApiException(422, "INVALID_ROLE", "role must be an approved specialty role key");
        }
        return value;
    }

    /**
     * Validate a required bounded text field.
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
     * Create an opaque identifier for a new attestation.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
