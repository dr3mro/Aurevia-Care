package com.aurevia.finance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * Pure, total validation and arithmetic for every FIN-01 command field.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any SQL runs. Money is represented only as integer
 * minor units; overflow is rejected rather than wrapped.</p>
 */
public final class FinanceInputs {

    private static final HexFormat HEX = HexFormat.of();
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
     * Build the replay fingerprint of a ledger entry command. The invoice, entry
     * type, minor-unit amount, currency and every optional field take part, so
     * the same reference on a different invoice or any differing payload is a
     * conflict rather than a silent no-op replay. Variable text is length-prefixed
     * and the whole canonical string is reduced to a 128-bit SHA-256 fingerprint,
     * so the token is fixed-width and fits the ledger storage column regardless
     * of how long an allowed reason or subject is. The database
     * {@code UNIQUE (organization_id, reference)} constraint remains the durable
     * business key; this fingerprint detects a different payload reusing a key.
     *
     * @param invoiceId       owning invoice, part of the durable business key
     * @param type            entry type
     * @param amount          magnitude in minor units
     * @param currency        invoice currency snapshot
     * @param reason          recorded reason, or {@code null}
     * @param approverSubject approving subject, or {@code null}
     * @return 64 lowercase hex characters
     * @throws ApiException when the platform does not provide SHA-256
     */
    public static String digest(String invoiceId, FinanceContract.EntryType type, long amount,
                                String currency, String reason, String approverSubject) {
        return digest(invoiceId, type, amount, currency, reason, approverSubject,
                MessageDigest::getInstance);
    }

    /**
     * Build a replay fingerprint with an explicit digest factory.
     *
     * <p>The factory is a seam for a deterministic failure test; production
     * callers use the six-argument {@code digest} overload.</p>
     *
     * @param invoiceId       owning invoice, part of the durable business key
     * @param type            entry type
     * @param amount          magnitude in minor units
     * @param currency        invoice currency snapshot
     * @param reason          recorded reason, or {@code null}
     * @param approverSubject approving subject, or {@code null}
     * @param factory         digest factory keyed by algorithm name
     * @return 64 lowercase hex characters
     * @throws ApiException when the factory cannot provide SHA-256
     */
    public static String digest(String invoiceId, FinanceContract.EntryType type, long amount,
                                String currency, String reason, String approverSubject,
                                MessageDigestFactory factory) {
        String canonical = invoiceId + "|" + type.wire() + "|" + amount + "|" + currency
                + "|" + field(reason) + "|" + field(approverSubject);
        try {
            byte[] bytes = factory.create("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(500, "DIGEST_UNAVAILABLE", "SHA-256 is required for finance replay dedup");
        }
    }

    private static String field(String value) {
        return value == null ? "-" : value.length() + ":" + value;
    }

    /**
     * Creates a message digest for a named algorithm.
     */
    @FunctionalInterface
    public interface MessageDigestFactory {

        /**
         * Create a digest.
         *
         * @param algorithm JCA algorithm name
         * @return a fresh digest
         * @throws NoSuchAlgorithmException when the algorithm is unavailable
         */
        MessageDigest create(String algorithm) throws NoSuchAlgorithmException;
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
