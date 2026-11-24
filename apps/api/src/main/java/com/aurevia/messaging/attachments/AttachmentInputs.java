package com.aurevia.messaging.attachments;

import com.aurevia.api.ApiException;
import com.aurevia.documents.UploadValidation;
import com.aurevia.messaging.attachments.AttachmentContract.FileType;
import com.aurevia.messaging.attachments.AttachmentContract.ScanVerdict;

/**
 * Pure, total validation of every S-G3-06 attachment command field (MSG-03).
 *
 * <p>Every method either returns a normalized value or raises a {@code 422}
 * with a stable code before any durable state changes, so a malformed type,
 * size or checksum can never reach the quarantine store. Shared upload rules
 * (size, file name, checksum, key) live in the single {@code UploadValidation}
 * primitive; only the attachment-specific media-type parser remains here.</p>
 */
public final class AttachmentInputs {

    /** Largest accepted payload, ten mebibytes. */
    public static final long MAX_SIZE_BYTES = UploadValidation.MAX_SIZE_BYTES;

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
        return UploadValidation.uuid(value, field);
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
        return UploadValidation.key(value, field);
    }

    /**
     * Validate a bounded, path-free original file name.
     *
     * @param value supplied file name
     * @return the trimmed file name
     * @throws ApiException when the name is blank, too long or contains a path separator
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
     * Validate a payload size against the shared bound.
     *
     * @param size supplied size in bytes
     * @return the validated size
     * @throws ApiException when the size is outside 1..10 MiB
     */
    public static long size(long size) {
        return UploadValidation.size(size);
    }

    /**
     * Parse a scanner verdict command.
     *
     * @param value supplied verdict key
     * @return {@code ScanVerdict.CLEAN} or {@code ScanVerdict.FAILED}
     * @throws ApiException when the verdict is absent or is not a terminal verdict
     */
    public static ScanVerdict verdict(String value) {
        String wire = UploadValidation.terminalVerdict(value, "verdict");
        return "clean".equals(wire) ? ScanVerdict.CLEAN : ScanVerdict.FAILED;
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
        return UploadValidation.detail(value, max);
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
        return UploadValidation.objectKey(organizationId, id, objectVersion);
    }

    /**
     * Create an opaque attachment identifier.
     *
     * @return random UUID text
     */
    public static String newId() {
        return UploadValidation.newId();
    }
}
