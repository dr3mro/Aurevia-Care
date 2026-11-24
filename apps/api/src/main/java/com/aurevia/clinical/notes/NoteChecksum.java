package com.aurevia.clinical.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.aurevia.api.ApiException;

/**
 * Server-side content fingerprint for a clinical note revision (CLN-05, A9).
 *
 * <p>The checksum is SHA-256 over the UTF-8 bytes of the stored content. It is
 * computed by the server, never accepted from a caller, and stored with each
 * immutable revision. The V15 insert trigger computes the same value from the
 * database row so a submitted value can never be trusted.</p>
 */
public final class NoteChecksum {

    private static final HexFormat HEX = HexFormat.of();

    private NoteChecksum() {
    }

    /**
     * Compute the lowercase hex SHA-256 of the supplied content.
     *
     * @param content canonical note content, never {@code null}
     * @return 64 lowercase hex characters
     * @throws ApiException when the platform does not provide SHA-256
     */
    public static String sha256(String content) {
        return sha256(content, MessageDigest::getInstance);
    }

    /**
     * Compute a fingerprint with an explicit digest factory.
     *
     * <p>The factory is a seam for a deterministic failure test; production
     * callers use {@code sha256(String)}.</p>
     *
     * @param content canonical note content
     * @param factory digest factory keyed by algorithm name
     * @return 64 lowercase hex characters
     * @throws ApiException when the factory cannot provide SHA-256
     */
    public static String sha256(String content, MessageDigestFactory factory) {
        try {
            byte[] digest = factory.create("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(500, "CHECKSUM_UNAVAILABLE", "SHA-256 is required for signed notes");
        }
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
}
