package com.aurevia.tenant.export;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

/**
 * Canonical SHA-256 digest for tenant export artifacts.
 *
 * <p>Export checksums are derived from the bytes actually written to the object
 * store, never copied from a caller-supplied label. The digest factory is an
 * extension point used by a failure test so an unavailable algorithm fails
 * closed instead of producing an unverifiable manifest.</p>
 */
@Component
public final class ExportDigests {

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
    public ExportDigests() {
        this(() -> MessageDigest.getInstance("SHA-256"));
    }

    /**
     * Create a digester over an explicit digest factory.
     *
     * @param digestFactory digest factory used for every checksum computation
     */
    ExportDigests(DigestFactory digestFactory) {
        this.digestFactory = digestFactory;
    }

    /**
     * Compute the lowercase 64-digit hexadecimal SHA-256 of the supplied bytes.
     *
     * @param bytes bytes to digest
     * @return canonical lowercase hex digest
     * @throws IllegalStateException when SHA-256 is unavailable
     */
    public String sha256Hex(byte[] bytes) {
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
}
