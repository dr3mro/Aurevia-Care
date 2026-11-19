package com.aurevia.smartclinic.g4.s_g4_05;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.smartclinic.api.ApiException;

/**
 * Pure, total validation and arithmetic for every FIN-01 command field.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any SQL runs. Money is represented only as integer
 * minor units; overflow is rejected rather than wrapped.</p>
 */
public final class FinanceInputs {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern SUBJECT = Pattern.compile("[a-z0-9][a-z0-9-]{1,63}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final int MAX_REASON = 500;
    private static final int MAX_REFERENCE = 128;

    private FinanceInputs() {
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
     * Normalize and validate a three-letter currency code.
     *
     * @param value supplied currency
     * @return the uppercase currency code
     * @throws ApiException when the value is not three ASCII letters
     */
    public static String currency(String value) {
        String normalized = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || !CURRENCY.matcher(normalized).matches()) {
            throw new ApiException(422, "INVALID_CURRENCY", "currency must be three uppercase letters");
        }
        return normalized;
    }

    /**
     * Validate a required human-readable text field.
     *
     * @param value supplied value
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
     * Normalize an optional text field, treating absence and blank as null.
     *
     * @param value supplied value
     * @param field field name used in the error code
     * @param max   maximum accepted length
     * @return the trimmed text or {@code null}
     * @throws ApiException when the value is too long
     */
    public static String optionalText(String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > max) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be 1-" + max + " characters");
        }
        return normalized;
    }

    /**
     * Validate a subject key used as an approver or actor.
     *
     * @param value supplied subject
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
     * Validate a strictly positive money amount.
     *
     * @param value supplied amount in minor units
     * @param field field name used in the error code
     * @return the validated amount
     * @throws ApiException when the amount is not positive
     */
    public static long positiveAmount(long value, String field) {
        if (value <= 0) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a positive amount");
        }
        return value;
    }

    /**
     * Validate a strictly positive item quantity.
     *
     * @param value supplied quantity
     * @return the validated quantity
     * @throws ApiException when the quantity is absent or not positive
     */
    public static int positiveQuantity(Integer value) {
        if (value == null || value <= 0) {
            throw new ApiException(422, "INVALID_QUANTITY", "quantity must be a positive integer");
        }
        return value;
    }

    /**
     * Validate a non-negative per-unit money amount.
     *
     * @param value supplied amount in minor units
     * @param field field name used in the error code
     * @return the validated amount
     * @throws ApiException when the amount is absent or negative
     */
    public static long nonNegativeAmount(Long value, String field) {
        if (value == null || value < 0) {
            throw new ApiException(422, "INVALID_" + field.toUpperCase(Locale.ROOT),
                    field + " must be a non-negative amount");
        }
        return value;
    }

    /**
     * Validate a required reason for a reversal or adjustment.
     *
     * @param value supplied reason
     * @param field field name used in the error code
     * @return the trimmed reason
     * @throws ApiException when the reason is absent, blank or too long
     */
    public static String requiredReason(String value, String field) {
        return text(value, field, MAX_REASON);
    }

    /**
     * Normalize an optional reason, treating absence and blank as null.
     *
     * @param value supplied reason
     * @param field field name used in the error code
     * @return the trimmed reason or {@code null}
     * @throws ApiException when the reason is too long
     */
    public static String optionalReason(String value, String field) {
        return optionalText(value, field, MAX_REASON);
    }

    /**
     * Validate a stable, tenant-unique payment or adjustment reference.
     *
     * @param value supplied reference
     * @return the trimmed reference
     * @throws ApiException when the reference is absent, blank or too long
     */
    public static String reference(String value) {
        return text(value, "reference", MAX_REFERENCE);
    }

    /**
     * Multiply two amounts, rejecting overflow instead of wrapping.
     *
     * @param left  first factor
     * @param right second factor
     * @param field field name used in the error code
     * @return the exact product
     * @throws ApiException when the product overflows a signed long
     */
    public static long multiply(long left, long right, String field) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ApiException(422, "AMOUNT_OVERFLOW", field + " overflows the money range");
        }
    }

    /**
     * Add two amounts, rejecting overflow instead of wrapping.
     *
     * @param left  first addend
     * @param right second addend
     * @return the exact sum
     * @throws ApiException when the sum overflows a signed long
     */
    public static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new ApiException(422, "AMOUNT_OVERFLOW", "invoice total overflows the money range");
        }
    }

    /**
     * Build the exact-match replay fingerprint of a ledger entry payload. Only
     * the validated type, minor-unit amount and currency take part, so a repeat
     * with identical inputs is recognized while any differing input is a
     * conflict. This is a canonical comparison token, not a lossy hash: an exact
     * string comparison cannot collide and silently accept a different payload.
     *
     * @param type     entry type
     * @param amount   magnitude in minor units
     * @param currency invoice currency snapshot
     * @return canonical finite-length fingerprint
     */
    public static String digest(FinanceContract.EntryType type, long amount, String currency) {
        return type.wire() + "|" + amount + "|" + currency;
    }

    /**
     * Create an opaque identifier for a new finance record.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
