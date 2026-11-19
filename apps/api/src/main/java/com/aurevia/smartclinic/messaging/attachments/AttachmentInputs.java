package com.aurevia.smartclinic.messaging.attachments;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.smartclinic.messaging.attachments.AttachmentContract.ScanVerdict;

/**
 * Pure, total validation of every S-G3-06 command field.
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any durable state changes, so a malformed type,
 * size or checksum can never reach the quarantine store.</p>
 */
public final class AttachmentInputs {

    /** Largest accepted payload, ten mebibytes. */
    public static final long MAX_SIZE_BYTES = 10L * 1024L * 1024L;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,63}");
    private static final Pattern FILE_NAME = Pattern.compile("[^/\\\\\\x00-\\x1F]{1,200}");

    private AttachmentInputs() {
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
     * Validate a bounded opaque message or encounter key.
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
     * @throws ApiException when the name is blank, too long or contains a path separator
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
    public static FileType contentType(String value) {
        FileType type = FileType.fromContentType(value);
        if (type == null) {
            throw new ApiException(422, "UNSUPPORTED_CONTENT_TYPE",
                    "only PDF, JPEG and PNG attachments are supported");
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
     * @return the lowercased checksum
     * @throws ApiException when the value is not 64 lowercase hex characters
     */
    public static String checksum(String value) {
        if (value == null || !CHECKSUM.matcher(value).matches()) {
            throw invalid("checksum", "must be 64 lowercase hexadecimal characters");
        }
        return value;
    }

    /**
     * Validate a payload size against the configured bounds.
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
     * Parse a scanner verdict command.
     *
     * @param value supplied verdict key
     * @return {@code ScanVerdict.CLEAN} or {@code ScanVerdict.FAILED}
     * @throws ApiException when the verdict is absent or is not a terminal verdict
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
     * Normalize an optional non-sensitive detail field.
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
     * Build a unique private object key for one immutable version.
     *
     * @param organizationId active organization identifier
     * @param id             attachment identifier
     * @param objectVersion  immutable object version
     * @return a private, deterministic object key
     */
    public static String objectKey(String organizationId, String id, int objectVersion) {
        return "quarantine/" + organizationId + "/" + id + "/v" + objectVersion;
    }

    /**
     * Create an opaque attachment identifier.
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
