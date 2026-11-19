package com.aurevia.smartclinic.api.x_03;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.api.x_03.DocumentContract.Language;
import com.aurevia.smartclinic.api.x_03.DocumentContract.MediaType;
import com.aurevia.smartclinic.api.x_03.DocumentContract.ScanVerdict;

/**
 * Pure, total validation of every X-03 command field (ARC-06).
 *
 * <p>Every method either returns a normalized value or raises a stable
 * {@code 422} before durable state changes, so a malformed type, size,
 * checksum or selector can never reach the quarantine or binding store.
 * Server-owned fields such as object keys and versions are never read from a
 * command.</p>
 */
public final class DocumentInputs {

    /** Largest accepted payload, ten mebibytes. */
    public static final long MAX_SIZE_BYTES = 10L * 1024L * 1024L;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,63}");
    private static final Pattern FILE_NAME = Pattern.compile("[^/\\\\\\x00-\\x1F]{1,200}");
    private static final Pattern OBJECT_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_.:-]{1,127}");

    private DocumentInputs() {
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
            throw invalid(field, "must be a UUID");
        }
        return value;
    }

    /**
     * Validate a bounded opaque selector key.
     *
     * @param value supplied key
     * @param field field name used in the error code
     * @return the trimmed key
     * @throws ApiException when the key is blank, malformed or too long
     */
    public static String key(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || !KEY.matcher(normalized).matches()) {
            throw invalid(field, "must be 2-64 letters, digits, _ or -");
        }
        return normalized;
    }

    /**
     * Validate a bounded, path-free original file name.
     *
     * @param value supplied file name
     * @return the trimmed file name
     * @throws ApiException when the name is blank, too long or contains a path
     */
    public static String fileName(String value) {
        if (value == null || !FILE_NAME.matcher(value).matches() || value.contains("..")) {
            throw invalid("fileName", "must be 1-200 characters without a path or control character");
        }
        return value.trim();
    }

    /**
     * Resolve and validate a declared media type.
     *
     * @param value declared MIME type
     * @return the supported file type
     * @throws ApiException when the type is absent or unsupported
     */
    public static MediaType contentType(String value) {
        MediaType type = MediaType.fromContentType(value);
        if (type == null) {
            throw new ApiException(422, "UNSUPPORTED_CONTENT_TYPE",
                    "only PDF, JPEG and PNG payloads are supported");
        }
        return type;
    }

    /**
     * Validate the hexadecimal leading bytes of the payload.
     *
     * @param value supplied leading bytes as hexadecimal
     * @return the lowercased leading bytes
     * @throws ApiException when the value is not 2-32 hex characters
     */
    public static String magicHex(String value) {
        if (value == null || value.length() < 2 || value.length() > 32 || !HEX.matcher(value).matches()) {
            throw invalid("magicHex", "must be 2-32 hexadecimal characters");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * Validate a SHA-256 checksum.
     *
     * @param value supplied checksum
     * @return the validated lowercase checksum
     * @throws ApiException when the value is not 64 lowercase hex characters
     */
    public static String checksum(String value) {
        if (value == null || !CHECKSUM.matcher(value).matches()) {
            throw invalid("checksum", "must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    /**
     * Validate a payload or rendered object size against configured bounds.
     *
     * @param size supplied size in bytes
     * @return the validated size
     * @throws ApiException when the size is outside 1..10 MiB
     */
    public static long size(long size) {
        if (size < 1 || size > MAX_SIZE_BYTES) {
            throw invalid("sizeBytes", "must be 1-" + MAX_SIZE_BYTES + " bytes");
        }
        return size;
    }

    /**
     * Parse a terminal scanner verdict command.
     *
     * @param value supplied verdict key
     * @return {@code CLEAN} or {@code FAILED}
     * @throws ApiException when the verdict is absent or not terminal
     */
    public static ScanVerdict verdict(String value) {
        if (value == null) {
            throw invalid("verdict", "must be clean or failed");
        }
        for (ScanVerdict candidate : new ScanVerdict[] {ScanVerdict.CLEAN, ScanVerdict.FAILED}) {
            if (candidate.wire().equals(value)) {
                return candidate;
            }
        }
        throw invalid("verdict", "must be clean or failed");
    }

    /**
     * Parse a PDF language key.
     *
     * @param value supplied language key
     * @return the supported language
     * @throws ApiException when the language is absent or unsupported
     */
    public static Language language(String value) {
        Language language = Language.from(value);
        if (language == null) {
            throw invalid("language", "must be en or ar");
        }
        return language;
    }

    /**
     * Validate a positive immutable version selector.
     *
     * @param value supplied version
     * @param field field name used in the error code
     * @return the validated version
     * @throws ApiException when the version is not positive
     */
    public static int positiveVersion(int value, String field) {
        if (value < 1) {
            throw invalid(field, "must be positive");
        }
        return value;
    }

    /**
     * Validate a bounded, non-sensitive detail field.
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
        if (normalized.length() > max) {
            throw invalid("detail", "must be at most " + max + " characters");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Validate a bounded private object key supplied by a verified renderer.
     *
     * @param value supplied object key
     * @param field field name used in the error code
     * @return the trimmed object key
     * @throws ApiException when the key is blank, malformed or too long
     */
    public static String verifiedObjectKey(String value, String field) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || !OBJECT_KEY.matcher(normalized).matches()) {
            throw invalid(field, "must be a bounded private object key");
        }
        return normalized;
    }

    /**
     * Build a unique private quarantine object key for one immutable version.
     *
     * @param organizationId active organization
     * @param id             artifact identifier
     * @param objectVersion  immutable object version
     * @return a private deterministic object key
     */
    public static String objectKey(String organizationId, String id, int objectVersion) {
        return "quarantine/" + organizationId + "/" + id + "/v" + objectVersion;
    }

    /**
     * Build a unique private PDF object key for one exact revision version.
     *
     * @param organizationId active organization
     * @param revisionId     immutable revision identifier
     * @param revisionVersion immutable revision version
     * @param language       rendered language key
     * @return a private deterministic object key
     */
    public static String pdfObjectKey(String organizationId, String revisionId,
                                      int revisionVersion, String language) {
        return "pdf/" + organizationId + "/" + revisionId + "/v" + revisionVersion + "/" + language;
    }

    /**
     * Create an opaque artifact, job, fact or revision identifier.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    private static ApiException invalid(String field, String rule) {
        String stableField = field.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        return new ApiException(422, "INVALID_" + stableField, field + " " + rule);
    }
}
