package com.aurevia.documents;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.aurevia.api.ApiException;

/**
 * The single typed validation primitive for file-upload command fields.
 *
 * <p>Both the document-artifact and messaging-attachment boundaries validate the
 * same size bound, path-free file name and SHA-256 checksum. Owning those rules
 * once means a tightening of a bound or a traversal guard takes effect at every
 * upload entry point and cannot drift into a file-validation hole. Every method
 * returns a normalized value or raises a stable {@code 422} before durable state
 * changes. Module-specific parsers (media type, language, verdict enum) stay in
 * their own domain and delegate only the shared rule to this class.</p>
 *
 * <p>The primitive is pure and holds no state, so it is safe to share across
 * request threads and application contexts.</p>
 */
public final class UploadValidation {

    /** Largest accepted payload, ten mebibytes; the single value for both upload paths. */
    public static final long MAX_SIZE_BYTES = 10L * 1024L * 1024L;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");
    private static final Pattern CHECKSUM = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{1,63}");
    private static final Pattern FILE_NAME = Pattern.compile("[^/\\\\\\x00-\\x1F]{1,200}");

    private UploadValidation() {
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
     * <p>Path separators, control characters and explicit parent traversal are
     * rejected so the name can never escape the tenant's private object prefix.</p>
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
     * Validate a payload or rendered object size against the shared bound.
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
     * Normalize an optional non-sensitive detail field.
     *
     * @param value supplied detail
     * @param max   maximum accepted length
     * @return the trimmed detail or {@code null} when absent or blank
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
     * Build a unique private quarantine object key for one immutable version.
     *
     * @param organizationId active organization
     * @param id             artifact or attachment identifier
     * @param objectVersion  immutable object version
     * @return a private deterministic object key
     */
    public static String objectKey(String organizationId, String id, int objectVersion) {
        return "quarantine/" + organizationId + "/" + id + "/v" + objectVersion;
    }

    /**
     * Create an opaque artifact, attachment, job, fact or revision identifier.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Validate a terminal scanner-verdict wire value before enum mapping.
     *
     * @param value supplied verdict key
     * @param field field name used in the error code
     * @return the matched {@code clean} or {@code failed} wire value
     * @throws ApiException when the verdict is absent or not terminal
     */
    public static String terminalVerdict(String value, String field) {
        if (value == null || !("clean".equals(value) || "failed".equals(value))) {
            throw invalid(field, "must be clean or failed");
        }
        return value;
    }

    /**
     * Build the stable command failure shared by every caller.
     *
     * @param field field name, rendered upper snake case in the error code
     * @param rule  human-readable, non-sensitive rule detail
     * @return a {@code 422} failure with code {@code INVALID_<FIELD>}
     */
    public static ApiException invalid(String field, String rule) {
        String stableField = field.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        return new ApiException(422, "INVALID_" + stableField, field + " " + rule);
    }
}
