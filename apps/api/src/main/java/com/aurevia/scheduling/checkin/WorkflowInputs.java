package com.aurevia.scheduling.checkin;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.FacilityCode;

/**
 * Pure, total input guards for the CLN-02 commands.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any SQL runs. Identifier categories are checked
 * here because a name, a shared phone number or a date of birth alone is not
 * proof of identity (IAM-03): two distinct categories are required, at least
 * one of which is issuer-scoped or independently verified.</p>
 */
public final class WorkflowInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Issuer-scoped or independently verifier-backed identifier categories. */
    private static final Set<String> STRONG = Set.of("MRN", "GOV_ID", "PATIENT_ACCOUNT");
    /** All categories that may appear as evidence, including weak confirmers. */
    private static final Set<String> ALLOWED = Set.of(
            "MRN", "GOV_ID", "PATIENT_ACCOUNT", "DOB", "VISIT_DATE");
    private static final int MAX_TEXT = 200;
    private static final int MAX_IDENTIFIERS = 6;

    private WorkflowInputs() {
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
     * Validate a tenant-local code such as a facility code.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the value unchanged
     * @throws ApiException when the value is not a bounded upper-case code
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
     * Validate a required human-readable text field.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the trimmed text
     * @throws ApiException when the value is absent, blank or too long
     */
    public static String text(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty() || normalized.length() > MAX_TEXT) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 1-" + MAX_TEXT + " characters");
        }
        return normalized;
    }

    /**
     * Normalize an optional reason.
     *
     * @param value value supplied by the caller, possibly {@code null} or blank
     * @return the trimmed reason, or {@code null} when nothing was recorded
     * @throws ApiException when the reason exceeds the stored bound
     */
    public static String reason(String value) {
        String normalized = value == null || value.isBlank() ? null : value.trim();
        if (normalized != null && normalized.length() > MAX_TEXT) {
            throw new ApiException(422, "INVALID_REASON", "reason must be 1-" + MAX_TEXT + " characters");
        }
        return normalized;
    }

    /**
     * Validate an ISO-8601 instant.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error message
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
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be an ISO-8601 instant");
        }
    }

    /**
     * Resolve a stored priority from an approved configuration value.
     *
     * @param value configured priority text, possibly absent or malformed
     * @return a priority in 0-9, defaulting to 0 when unconfigured
     */
    public static int priority(String value) {
        if (value == null) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 || parsed > 9 ? 0 : parsed;
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /**
     * Validate the named identity-verification method.
     *
     * @param value value supplied by the caller
     * @return the normalized method key
     * @throws ApiException when the method is unknown
     */
    public static String method(String value) {
        if (!"two_identifiers".equals(value) && !"documented_alternate".equals(value)) {
            throw new ApiException(422, "INVALID_METHOD",
                    "method must be two_identifiers or documented_alternate");
        }
        return value;
    }

    /**
     * Validate and normalize the identifier categories presented at check-in.
     *
     * @param values categories supplied by the caller
     * @return distinct categories in a stable order
     * @throws ApiException when empty, oversized, unknown, duplicated, or lacking
     *                      an issuer-scoped or independently verified category
     */
    public static List<String> identifiers(List<String> values) {
        if (values == null || values.size() < 2 || values.size() > MAX_IDENTIFIERS) {
            throw new ApiException(422, "INSUFFICIENT_IDENTIFIERS",
                    "two to six identifier categories are required");
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String category = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED.contains(category) || normalized.contains(category)) {
                throw new ApiException(422, "INVALID_IDENTIFIERS",
                        "identifier categories must be known and distinct");
            }
            normalized.add(category);
        }
        if (normalized.stream().noneMatch(STRONG::contains)) {
            throw new ApiException(422, "INSUFFICIENT_IDENTIFIERS",
                    "at least one issuer-scoped or verified identifier is required");
        }
        return List.copyOf(normalized);
    }

    /**
     * Validate the named alternate-process code and its reason.
     *
     * @param code    site-approved workflow code
     * @param reason  free-text reason
     * @return the normalized workflow code
     * @throws ApiException when either field is missing or too long
     */
    public static String alternate(String code, String reason) {
        String normalized = code(code == null ? null : code.trim(), "alternateCode");
        text(reason, "alternateReason");
        return normalized;
    }

    /**
     * Opaque identifier for a new record.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
