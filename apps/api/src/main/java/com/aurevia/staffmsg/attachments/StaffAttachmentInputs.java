package com.aurevia.staffmsg.attachments;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.documents.UploadValidation;

/**
 * Pure validation of every staff-attachment command field.
 *
 * <p>Shared upload rules (file name, checksum, key, detail) delegate to the
 * single {@code UploadValidation} primitive so a tightened bound applies at
 * every upload boundary. The three allowed media types are reused from the
 * document domain: a declared type, the leading payload bytes and the file
 * extension must all agree before a file enters quarantine.</p>
 */
public final class StaffAttachmentInputs {

    /** Largest accepted payload, ten mebibytes, shared with document uploads. */
    public static final long MAX_SIZE_BYTES = UploadValidation.MAX_SIZE_BYTES;
    /** Number of leading payload bytes inspected for the media signature. */
    public static final int MAGIC_BYTES = 8;

    private StaffAttachmentInputs() {
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
     * @return the validated file name
     * @throws ApiException when the name is blank, too long or contains a path
     */
    public static String fileName(String value) {
        return UploadValidation.fileName(value);
    }

    /**
     * Resolve a declared media type, allowing only PDF, JPEG and PNG.
     *
     * @param value declared MIME type
     * @return the supported type
     * @throws ApiException when the type is absent or unsupported
     */
    public static MediaType contentType(String value) {
        MediaType type = MediaType.fromContentType(value);
        if (type == null) {
            throw new ApiException(422, "UNSUPPORTED_CONTENT_TYPE",
                    "only PDF, JPEG and PNG attachments are supported");
        }
        return type;
    }

    /**
     * Compute the lowercase hexadecimal leading bytes of a payload.
     *
     * @param content original payload bytes
     * @return the payload signature prefix
     */
    public static String magicHex(byte[] content) {
        int length = Math.min(MAGIC_BYTES, content.length);
        return HexFormat.of().formatHex(java.util.Arrays.copyOf(content, length))
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Compute the SHA-256 checksum of a payload.
     *
     * @param content original payload bytes
     * @return lowercase hexadecimal checksum
     */
    public static String checksum(byte[] content) {
        return checksum("SHA-256", content);
    }

    /**
     * Compute a digest with an explicit algorithm so an unavailable algorithm is
     * surfaced as a deterministic state failure.
     *
     * @param algorithm digest algorithm name
     * @param content   original payload bytes
     * @return lowercase hexadecimal checksum
     */
    static String checksum(String algorithm, byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("digest algorithm is unavailable", exception);
        }
    }

    /**
     * Compare a client integrity claim with the checksum derived from the bytes.
     *
     * @param declared  client-supplied checksum
     * @param calculated checksum computed from the original bytes
     * @throws ApiException when the claim is absent or does not match
     */
    public static void requireMatchingChecksum(String declared, String calculated) {
        if (declared == null || !declared.equalsIgnoreCase(calculated)) {
            throw new ApiException(422, "CHECKSUM_MISMATCH", "checksum does not match the payload bytes");
        }
    }

    /**
     * Validate a payload size against a configured bound.
     *
     * @param size  supplied size in bytes
     * @param limit configured maximum
     * @return the validated size
     * @throws ApiException when the size is outside 1..limit
     */
    public static long size(long size, long limit) {
        if (size < 1 || size > limit) {
            throw new ApiException(422, "INVALID_SIZE_BYTES", "size must be 1-" + limit + " bytes");
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
        return UploadValidation.detail(value, max);
    }

    /**
     * Build a unique private object key for one immutable version.
     *
     * @param organizationId active organization
     * @param id             attachment or document identifier
     * @param objectVersion  immutable object version
     * @return a private deterministic object key
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

    /**
     * Parse a terminal scanner verdict command.
     *
     * @param value supplied verdict key
     * @return {@code true} for a clean verdict, {@code false} for a failed verdict
     * @throws ApiException when the verdict is absent or not terminal
     */
    public static boolean cleanVerdict(String value) {
        String terminal = UploadValidation.terminalVerdict(value, "verdict");
        return "clean".equals(terminal);
    }
}
