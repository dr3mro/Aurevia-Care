package com.aurevia.clinical.notes;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.FacilityCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure trust-boundary validation for clinical note commands (CLN-05).
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code, so malformed identifiers never reach a SQL cast and no
 * caller-supplied identity or checksum reaches the ledger.</p>
 */
public final class NoteInputs {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_RECIPIENTS = 2000;
    private static final int MAX_REASON = 200;

    private NoteInputs() {
    }

    /**
     * Validate an opaque tenant-owned identifier.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the value unchanged
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
     * Validate an optional opaque identifier.
     *
     * @param value value supplied by the caller, possibly absent
     * @param field field name used in the error code
     * @return the trimmed value, or {@code null} when absent
     * @throws ApiException when a supplied value is not a UUID
     */
    public static String optionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return uuid(value.trim(), field);
    }

    /**
     * Validate a required human-readable field.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return the trimmed value
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
     * Validate an upper-case tenant-local code.
     *
     * @param value supplied code
     * @param field field name used in the error code
     * @return the code unchanged
     * @throws ApiException when the code is malformed
     */
    public static String code(String value, String field) {
        try {
            return FacilityCode.facilityCode(value).value();
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    FacilityCode.INVALID_MESSAGE);
        }
    }

    /**
     * Validate a supported note language.
     *
     * @param value language code
     * @return the language unchanged
     * @throws ApiException when the language is not enabled
     */
    public static String language(String value) {
        if (!"en".equals(value) && !"ar".equals(value)) {
            throw new ApiException(422, "INVALID_LANGUAGE", "language must be en or ar");
        }
        return value;
    }

    /**
     * Validate a positive template version.
     *
     * @param value supplied version
     * @return the version
     * @throws ApiException when absent or not positive
     */
    public static int version(Integer value) {
        if (value == null || value < 1) {
            throw new ApiException(422, "INVALID_TEMPLATE_VERSION", "templateVersion must be positive");
        }
        return value;
    }

    /**
     * Parse an effective instant, defaulting to the injected clock.
     *
     * <p>A future instant is rejected: V15 requires {@code signed_at >= effective_at},
     * so a future-effective draft could never be signed and the irreversible sign
     * command would otherwise fail with an unhandled database error.</p>
     *
     * @param value supplied ISO-8601 instant, possibly absent
     * @param now   authoritative instant from the application clock
     * @return the effective instant as ISO-8601 text
     * @throws ApiException when a supplied instant is malformed or in the future
     */
    public static String instantOrNow(String value, Instant now) {
        if (value == null) {
            return now.toString();
        }
        Instant parsed;
        try {
            parsed = Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_EFFECTIVE_AT", "effectiveAt must be an ISO-8601 instant");
        }
        if (parsed.isAfter(now)) {
            throw new ApiException(422, "INVALID_EFFECTIVE_AT", "effectiveAt must not be in the future");
        }
        return parsed.toString();
    }

    /**
     * Normalize the affected-recipient identifier list of an entered-in-error.
     *
     * @param value JSON array of recipient identifiers, possibly absent
     * @return the trimmed array or {@code []}
     * @throws ApiException when the value is not a bounded JSON array
     */
    public static String recipients(String value) {
        if (value == null || value.isBlank()) {
            return "[]";
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_RECIPIENTS) {
            throw new ApiException(422, "INVALID_RECIPIENTS", "affected recipients are too large");
        }
        try {
            JsonNode parsed = JSON.readTree(normalized);
            if (!parsed.isArray()) {
                throw new ApiException(422, "INVALID_RECIPIENTS", "affected recipients must be a JSON array");
            }
        } catch (JacksonException exception) {
            throw new ApiException(422, "INVALID_RECIPIENTS", "affected recipients must be a JSON array");
        }
        return normalized;
    }

    /**
     * Validate a bounded recorded reason.
     *
     * @param value supplied reason
     * @param field field name used in the error code
     * @return the trimmed reason
     * @throws ApiException when absent, blank or too long
     */
    public static String reason(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > MAX_REASON) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 1-" + MAX_REASON + " characters");
        }
        return normalized;
    }
}
