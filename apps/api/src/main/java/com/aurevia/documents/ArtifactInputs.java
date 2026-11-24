package com.aurevia.documents;

import java.util.regex.Pattern;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.Language;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.documents.DocumentContract.ScanVerdict;

/**
 * Pure, total validation of every X-03 document-artifact command field (ARC-06).
 *
 * <p>Every method either returns a normalized value or raises a stable
 * {@code 422} before durable state changes, so a malformed type, size,
 * checksum or selector can never reach the quarantine or binding store.
 * Server-owned fields such as object keys and versions are never read from a
 * command. Shared upload rules (size, file name, checksum, key) are owned by
 * {@code UploadValidation}; only the document-specific parsers remain here.</p>
 */
public final class ArtifactInputs {

    /** Largest accepted payload, ten mebibytes. */
    public static final long MAX_SIZE_BYTES = UploadValidation.MAX_SIZE_BYTES;

    private static final Pattern OBJECT_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9/_.:-]{1,127}");

    private ArtifactInputs() {
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
        return UploadValidation.uuid(value, field);
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
        return UploadValidation.key(value, field);
    }

    /**
     * Validate a bounded, path-free original file name.
     *
     * @param value supplied file name
     * @return the trimmed file name
     * @throws ApiException when the name is blank, too long or contains a path
     */
    public static String fileName(String value) {
        return UploadValidation.fileName(value);
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
        return UploadValidation.magicHex(value);
    }

    /**
     * Validate a SHA-256 checksum.
     *
     * @param value supplied checksum
     * @return the validated lowercase checksum
     * @throws ApiException when the value is not 64 lowercase hex characters
     */
    public static String checksum(String value) {
        return UploadValidation.checksum(value);
    }

    /**
     * Validate a payload or rendered object size against configured bounds.
     *
     * @param size supplied size in bytes
     * @return the validated size
     * @throws ApiException when the size is outside 1..10 MiB
     */
    public static long size(long size) {
        return UploadValidation.size(size);
    }

    /**
     * Parse a terminal scanner verdict command.
     *
     * @param value supplied verdict key
     * @return {@code CLEAN} or {@code FAILED}
     * @throws ApiException when the verdict is absent or not terminal
     */
    public static ScanVerdict verdict(String value) {
        String wire = UploadValidation.terminalVerdict(value, "verdict");
        return "clean".equals(wire) ? ScanVerdict.CLEAN : ScanVerdict.FAILED;
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
            throw UploadValidation.invalid("language", "must be en or ar");
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
            throw UploadValidation.invalid(field, "must be positive");
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
        return UploadValidation.detail(value, max);
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
            throw UploadValidation.invalid(field, "must be a bounded private object key");
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
        return UploadValidation.objectKey(organizationId, id, objectVersion);
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
        return UploadValidation.newId();
    }
}
