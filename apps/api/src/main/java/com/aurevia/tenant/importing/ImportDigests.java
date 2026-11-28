package com.aurevia.tenant.importing;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared lowercase-hex SHA-256 digest and blank check for tenant import.
 *
 * <p>Both import services compute canonical digests that validate import signatures
 * and manifest content. Keeping the byte-to-hex computation and the blank check here
 * makes that rule single-sourced: the two callers cannot drift into different
 * attestation or checksum behaviour. The digest factory is an extension point used by
 * failure tests so an unavailable algorithm fails closed with a clear error.</p>
 */
public final class ImportDigests {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Creates a message digest on demand. */
    @FunctionalInterface
    interface DigestFactory {
        /**
         * Create a fresh digest instance.
         *
         * @return a message digest
         * @throws NoSuchAlgorithmException when SHA-256 is unavailable
     */
        MessageDigest create() throws NoSuchAlgorithmException;
    }

    private final DigestFactory digestFactory;

    /** Create a digester over the platform SHA-256 implementation. */
    ImportDigests() {
        this(() -> MessageDigest.getInstance("SHA-256"));
    }

    /**
     * Create a digester over an explicit digest factory.
     *
     * @param digestFactory digest factory used for every digest computation
     */
    ImportDigests(DigestFactory digestFactory) {
        this.digestFactory = digestFactory;
    }

    /**
     * Compute the lowercase 64-digit hexadecimal SHA-256 of the supplied bytes.
     *
     * @param bytes canonical bytes to digest
     * @return lowercase 64-hex-digit digest
     * @throws IllegalStateException when SHA-256 is unavailable
     */
    String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = digestFactory.create().digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(HEX[(value >> 4) & 0xf]).append(HEX[value & 0xf]);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * Return {@code true} when the value has content beyond surrounding whitespace.
     *
     * @param value candidate value, possibly null
     * @return true when non-null and not blank
     */
    static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
