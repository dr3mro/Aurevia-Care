package com.aurevia.prescription.rx;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.CanonicalUuid;

/**
 * Pure, total validation of every command field.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any SQL runs, so a malformed identifier, channel or
 * outcome can never reach a database cast or a durable write.</p>
 */
public final class RxInputs {

    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final Pattern CHANNEL = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final int MAX_QUANTITY = 999;

    private RxInputs() {
    }

    /**
     * Validate an opaque tenant-owned identifier.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated canonical lowercase UUID text
     * @throws ApiException when the value is not a canonical UUID
     */
    public static String uuid(String value, String field) {
        try {
            return CanonicalUuid.uuid(value).value();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT), field + " must be a UUID");
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
     * Normalize an optional non-sensitive detail.
     *
     * @param value supplied detail
     * @param max   maximum accepted length
     * @return the trimmed detail or {@code null}
     * @throws ApiException when the detail is too long
     */
    public static String detail(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > max) {
            throw new ApiException(422, "INVALID_DETAIL", "detail must be 1-" + max + " characters");
        }
        return normalized;
    }

    /**
     * Validate a subject key used as the prescribing clinician subject.
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
     * Validate an approved channel key shape.
     *
     * @param value value supplied by the caller
     * @param field field name used in the error code
     * @return the validated channel key
     * @throws ApiException when the value is not a bounded uppercase channel key
     */
    public static String channel(String value, String field) {
        if (value == null || !CHANNEL.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be an uppercase channel key");
        }
        return value;
    }

    /**
     * Validate an electronic transmission outcome.
     *
     * @param value supplied outcome
     * @return the validated outcome
     * @throws ApiException when the outcome is neither submitted nor ambiguous
     */
    public static String transmissionOutcome(String value) {
        if (!"submitted".equals(value) && !"ambiguous".equals(value)) {
            throw new ApiException(422, "INVALID_OUTCOME", "transmission outcome must be submitted or ambiguous");
        }
        return value;
    }

    /**
     * Validate a three-valued partner status for ambiguity reconciliation.
     *
     * @param value supplied status
     * @return the validated status
     * @throws ApiException when the status is not present, absent or unknown
     */
    public static String partnerStatus(String value) {
        if (!"present".equals(value) && !"absent".equals(value) && !"unknown".equals(value)) {
            throw new ApiException(422, "INVALID_STATUS", "partner status must be present, absent or unknown");
        }
        return value;
    }

    /**
     * Validate a pharmacy response outcome.
     *
     * @param value supplied outcome
     * @return the validated outcome
     * @throws ApiException when the outcome is not accepted, rejected or clarification
     */
    public static String pharmacyOutcome(String value) {
        if (!"accepted".equals(value) && !"rejected".equals(value) && !"clarification".equals(value)) {
            throw new ApiException(422, "INVALID_OUTCOME",
                    "pharmacy outcome must be accepted, rejected or clarification");
        }
        return value;
    }

    /**
     * Validate a printable-channel outcome.
     *
     * @param value supplied outcome
     * @return the validated {@code printed} outcome
     * @throws ApiException when the outcome is not printed
     */
    public static String printOutcome(String value) {
        if (!"printed".equals(value)) {
            throw new ApiException(422, "INVALID_OUTCOME", "printable outcome must be printed");
        }
        return value;
    }

    /**
     * Validate a positive, bounded prescribed quantity.
     *
     * @param value supplied quantity
     * @return the validated quantity
     * @throws ApiException when the quantity is absent, not positive or too large
     */
    public static int quantity(Integer value) {
        if (value == null || value < 1 || value > MAX_QUANTITY) {
            throw new ApiException(422, "INVALID_QUANTITY", "quantity must be 1-" + MAX_QUANTITY);
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
     * Create an opaque identifier for a new prescription.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Create the stable submission id bound to one prescription submission.
     *
     * @return random UUID text
     */
    public static String newSubmissionId() {
        return UUID.randomUUID().toString();
    }
}
