package com.aurevia.lab.accession;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.IssuerCode;

/**
 * Validation of every client-supplied laboratory field at the API boundary.
 *
 * <p>Each value is bounded before a transaction opens, so a malformed command
 * cannot consume a connection or reach a domain invariant. Patterns are strict
 * allowlists; free text remains bounded and never interprets markup.</p>
 */
public final class LabInputs {

    private static final Pattern ACCESSION = Pattern.compile("[A-Za-z0-9_.:/-]{3,64}");
    private static final Pattern ORDER_REF = Pattern.compile("[A-Za-z0-9_.:/-]{2,128}");
    private static final Pattern TEXT = Pattern.compile("[\\p{L}\\p{M}0-9 '.,/:@_-]{2,200}");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private LabInputs() {
    }

    /** @return the bounded accession number */
    public static String accessionNo(String value) {
        return require(value, ACCESSION, "INVALID_ACCESSION", "accessionNo is malformed");
    }

    /** @return the bounded accession issuer */
    public static String issuer(String value) {
        return issuerCode(value, "INVALID_ISSUER", "accessionIssuer is malformed");
    }

    /** @return the bounded source order reference */
    public static String sourceOrderRef(String value) {
        return require(value, ORDER_REF, "INVALID_ORDER_REF", "sourceOrderRef is malformed");
    }

    /** @return the bounded specimen type code */
    public static String specimenType(String value) {
        return issuerCode(value, "INVALID_SPECIMEN_TYPE", "specimenType is malformed");
    }

    /** @return the bounded collection site code */
    public static String siteCode(String value) {
        return issuerCode(value, "INVALID_SITE", "siteCode is malformed");
    }

    /** @return the bounded collector name */
    public static String collector(String value) {
        return require(value, TEXT, "INVALID_COLLECTOR", "collector is malformed");
    }

    /** @return the bounded quality code */
    public static String quality(String value) {
        return issuerCode(value, "INVALID_QUALITY", "quality is malformed");
    }

    /** @return the bounded rejection reason */
    public static String reason(String value) {
        return require(value, TEXT, "INVALID_REASON", "reason is required and must be bounded text");
    }

    /** @return the bounded notification target */
    public static String notification(String value) {
        return require(value, TEXT, "INVALID_NOTIFICATION", "notification is required and must be bounded text");
    }

    /**
     * Validate an opaque accession identifier.
     *
     * @param value client-supplied identifier
     * @return the canonical identifier text
     */
    public static String id(String value) {
        return require(value, UUID, "INVALID_ID", "id must be a UUID");
    }

    /**
     * Validate a patient identifier.
     *
     * @param value client-supplied identifier
     * @return the canonical identifier text
     */
    public static String patientId(String value) {
        return require(value, UUID, "INVALID_PATIENT", "patientId must be a UUID");
    }

    /**
     * Validate an optimistic-concurrency version.
     *
     * @param value client-supplied version
     * @return the validated version
     */
    public static int version(int value) {
        if (value < 1) {
            throw new ApiException(422, "INVALID_VERSION", "expectedVersion must be positive");
        }
        return value;
    }

    /**
     * Parse an ISO-8601 UTC instant.
     *
     * @param value client-supplied timestamp
     * @param field field name used in the error message
     * @return the parsed instant
     */
    public static Instant instant(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_TIMESTAMP", field + " is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_TIMESTAMP", field + " must be an ISO-8601 instant");
        }
    }

    /**
     * Validate an issuer-style value with the shared primitive and translate its rejection
     * into this field's machine-readable error.
     *
     * @param value client-supplied value
     * @param code field-specific error code
     * @param message field-specific detail message
     * @return the validated value
     * @throws ApiException when the value is outside the shared issuer/code boundary
     */
    private static String issuerCode(String value, String code, String message) {
        try {
            return IssuerCode.issuerCode(value).value();
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(422, code, message);
        }
    }

    private static String require(String value, Pattern pattern, String code, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new ApiException(422, code, message);
        }
        return value;
    }
}
