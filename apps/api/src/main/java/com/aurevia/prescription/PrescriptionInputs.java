package com.aurevia.prescription;

import java.util.Locale;
import java.util.UUID;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.CanonicalUuid;

/**
 * Total, synthetic-safe validation of RX-01 request fields (ARC-03).
 *
 * <p>Every method either returns a bounded, normalized value or throws a
 * machine-readable 422; no caller value reaches SQL or a domain object before
 * passing through here.</p>
 */
public final class PrescriptionInputs {

    private static final int MAX_REFILLS = 12;

    private PrescriptionInputs() {
    }

    /**
     * Validate an opaque identifier.
     *
     * @param value supplied identifier
     * @param field field name used in the error code
     * @return canonical lowercase identifier text
     * @throws ApiException when the value is not a UUID
     */
    public static String uuid(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), "identifier required");
        }
        try {
            return CanonicalUuid.uuid(value).value();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), "identifier must be a UUID");
        }
    }

    /**
     * Validate a required text field.
     *
     * @param value supplied text
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return trimmed text
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
     * Normalize an optional text field.
     *
     * @param value supplied text
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return trimmed text or {@code null}
     * @throws ApiException when a present value is too long
     */
    public static String optionalText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be at most " + max + " characters");
        }
        return normalized;
    }

    /**
     * Normalize an optional refill count.
     *
     * @param value supplied refill count
     * @return the count or {@code null}
     * @throws ApiException when the count is negative or exceeds the supported maximum
     */
    public static Integer refills(Integer value) {
        if (value == null) {
            return null;
        }
        if (value < 0 || value > MAX_REFILLS) {
            throw new ApiException(422, "INVALID_REFILLS", "refills must be 0-" + MAX_REFILLS);
        }
        return value;
    }

    /**
     * Create an opaque identifier for a new prescription draft.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
