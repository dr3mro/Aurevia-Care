package com.aurevia.documents;

import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * Boundary validation for original-document commands (CLN-07, ARC-03).
 *
 * <p>Every client-supplied field is bounded here before a transaction opens. A
 * checksum is deliberately not computed in Java: the V17 migration derives it
 * with PostgreSQL's core {@code sha256} over the exact retained bytes, so the
 * stored digest can never drift from the stored original.</p>
 */
public final class DocumentInputs {

    /** Upper bound for a synthetic original; production object limits belong to X-03. */
    public static final int MAX_ORIGINAL_BYTES = 1024 * 1024;

    private static final int MAX_LINES = 40;
    private static final int MAX_LINE_LENGTH = 500;
    private static final Set<String> SUBJECT_TYPES = Set.of("chart", "order", "result");
    private static final Set<String> MEDIA_TYPES =
            Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<String> LOCALES = Set.of("en", "ar");
    private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);
    private static final Pattern FILE_NAME = Pattern.compile("[^\\x00-\\x1F/\\\\]{1,180}");

    private DocumentInputs() {
    }

    /**
     * Validate an opaque identifier.
     *
     * @param value value to validate
     * @param field field name used in the error detail
     * @return the validated value
     * @throws ApiException {@code 422} when absent or malformed
     */
    public static String uuid(String value, String field) {
        if (value == null) {
            throw new ApiException(422, "INVALID_ID", field + " must be a UUID");
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_ID", field + " must be a UUID");
        }
    }

    /**
     * Validate the chart, order or result target of an association.
     *
     * @param value value to validate
     * @return the validated subject type
     * @throws ApiException {@code 422} when unsupported
     */
    public static String subjectType(String value) {
        if (value == null || !SUBJECT_TYPES.contains(value)) {
            throw new ApiException(422, "INVALID_SUBJECT_TYPE", "subjectType must be chart, order or result");
        }
        return value;
    }

    /**
     * Validate an original document media type.
     *
     * @param value value to validate
     * @return the validated media type
     * @throws ApiException {@code 422} when unsupported
     */
    public static String mediaType(String value) {
        if (value == null || !MEDIA_TYPES.contains(value)) {
            throw new ApiException(422, "INVALID_MEDIA_TYPE", "mediaType must be PDF, JPEG or PNG");
        }
        return value;
    }

    /**
     * Validate a supported render locale.
     *
     * @param value value to validate
     * @return the validated locale
     * @throws ApiException {@code 422} when unsupported
     */
    public static String locale(String value) {
        if (value == null || !LOCALES.contains(value)) {
            throw new ApiException(422, "INVALID_LOCALE", "locale must be en or ar");
        }
        return value;
    }

    /**
     * Validate presentation-only rotation; a missing value means zero.
     *
     * @param value value to validate, possibly {@code null}
     * @return the validated quarter turn
     * @throws ApiException {@code 422} when unsupported
     */
    public static int rotation(Integer value) {
        int degrees = value == null ? 0 : value;
        if (!ROTATIONS.contains(degrees)) {
            throw new ApiException(422, "INVALID_ROTATION", "rotation must be 0, 90, 180 or 270");
        }
        return degrees;
    }

    /**
     * Validate a bounded, path-free original file name.
     *
     * @param value value to validate
     * @return the validated file name
     * @throws ApiException {@code 422} when absent, path-bearing or overlong
     */
    public static String fileName(String value) {
        if (value == null || value.isBlank() || !FILE_NAME.matcher(value).matches()) {
            throw new ApiException(422, "INVALID_FILE_NAME", "fileName must be a bounded, path-free name");
        }
        return value;
    }

    /**
     * Validate a recorded void reason.
     *
     * @param value value to validate
     * @return the trimmed reason
     * @throws ApiException {@code 422} when absent or overlong
     */
    public static String reason(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 200) {
            throw new ApiException(422, "REASON_REQUIRED", "a bounded void reason is required");
        }
        return value.trim();
    }

    /**
     * Validate a bounded text field.
     *
     * @param value value to validate
     * @param code  stable error-code suffix
     * @param min   minimum length
     * @param max   maximum length
     * @return the validated value
     * @throws ApiException {@code 422} when outside the bounds
     */
    public static String bounded(String value, String code, int min, int max) {
        if (value == null || value.isBlank() || value.length() < min || value.length() > max) {
            throw new ApiException(422, "INVALID_" + code, code + " is outside its bounds");
        }
        return value;
    }

    /**
     * Validate the content lines of a rendered document.
     *
     * @param values values to validate
     * @return an immutable list of validated lines
     * @throws ApiException {@code 422} when empty, blank or overlong
     */
    public static List<String> lines(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > MAX_LINES) {
            throw new ApiException(422, "INVALID_LINES", "lines must contain 1-40 entries");
        }
        for (String line : values) {
            if (line == null || line.isBlank() || line.length() > MAX_LINE_LENGTH) {
                throw new ApiException(422, "INVALID_LINES", "each line must be non-blank and bounded");
            }
        }
        return List.copyOf(values);
    }

    /**
     * Decode and bound original bytes supplied as base64.
     *
     * @param base64 encoded original content
     * @return the decoded bytes
     * @throws ApiException {@code 422} when absent, malformed, empty or oversized
     */
    public static byte[] decode(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new ApiException(422, "INVALID_CONTENT", "content is required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_CONTENT", "content must be valid base64");
        }
        if (decoded.length > MAX_ORIGINAL_BYTES) {
            throw new ApiException(422, "CONTENT_TOO_LARGE", "content exceeds the synthetic size bound");
        }
        return decoded;
    }

    /**
     * Create an opaque identifier for a new association or render.
     *
     * @return a fresh random UUID as text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
