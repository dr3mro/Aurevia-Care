package com.aurevia.messaging.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.springframework.stereotype.Component;

/**
 * Deterministic lowercase-hex SHA-256 hashing of synthetic evidence strings.
 *
 * <p>The digest factory is injected so a missing algorithm fails closed with a
 * clear error instead of silently storing an unverifiable record. Identical
 * input always yields an identical digest; byte order is significant.</p>
 */
@Component
public final class EvidenceHash {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** Creates a message digest on demand. */
    @FunctionalInterface
    public interface DigestFactory {
        /**
         * Create a fresh digest instance.
         *
         * @return a message digest
         * @throws NoSuchAlgorithmException when SHA-256 is unavailable
         */
        MessageDigest create() throws NoSuchAlgorithmException;
    }

    private final DigestFactory factory;

    /**
     * Create a hasher over the platform SHA-256 implementation.
     */
    public EvidenceHash() {
        this(() -> MessageDigest.getInstance("SHA-256"));
    }

    /**
     * Create a hasher over an explicit digest factory.
     *
     * @param factory digest factory used for every hash call
     */
    public EvidenceHash(DigestFactory factory) {
        this.factory = factory;
    }

    /**
     * Hash a string with SHA-256 and render it as lowercase hexadecimal.
     *
     * @param input value to hash
     * @return 64 lowercase hexadecimal characters
     * @throws IllegalStateException when the digest algorithm is unavailable
     */
    public String hash(String input) {
        try {
            byte[] digest = factory.create().digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(HEX[(value >> 4) & 0xf]).append(HEX[value & 0xf]);
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
