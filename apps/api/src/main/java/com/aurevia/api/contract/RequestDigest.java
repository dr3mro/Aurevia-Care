package com.aurevia.api.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import com.aurevia.api.ApiException;

/**
 * Server-computed request fingerprint used to bind an idempotency key to one payload (ARC-03).
 *
 * <p>The canonical form is a byte-length-prefixed UTF-8 stream over tenant,
 * authenticated subject, command name and payload. A caller never supplies the
 * digest: a reused key with a different canonical payload is a conflict. A
 * fresh digest instance is created per computation; the mutable
 * {@code MessageDigest} is never shared between requests.</p>
 *
 * @param hex lowercase hexadecimal SHA-256 value
 */
public record RequestDigest(String hex) {

    private static final HexFormat HEX = HexFormat.of();

    /**
     * Reject an absent fingerprint.
     *
     * @param hex lowercase hexadecimal SHA-256 value
     */
    public RequestDigest {
        Objects.requireNonNull(hex, "hex");
    }

    /**
     * Build the canonical byte form of a protected command.
     *
     * @param organizationId active tenant identifier, never inferred from the body
     * @param subject authenticated caller
     * @param command stable command name such as {@code orders.accept}
     * @param payload canonical command payload
     * @return unambiguous length-prefixed UTF-8 bytes
     */
    public static byte[] canonicalBytes(String organizationId, String subject, String command, String payload) {
        return ByteParts.encode(organizationId, subject, command, payload);
    }

    /**
     * Fingerprint a command with the platform SHA-256 provider.
     *
     * @param organizationId active tenant identifier
     * @param subject authenticated caller
     * @param command stable command name
     * @param payload canonical command payload
     * @return the lowercase hexadecimal fingerprint
     */
    public static RequestDigest of(String organizationId, String subject, String command, String payload) {
        return of(organizationId, subject, command, payload, MessageDigest::getInstance);
    }

    /**
     * Fingerprint a command with an explicit digest factory.
     *
     * <p>The factory is a seam for a deterministic failure test; production
     * callers use {@code of(String, String, String, String)}.</p>
     *
     * @param organizationId active tenant identifier
     * @param subject authenticated caller
     * @param command stable command name
     * @param payload canonical command payload
     * @param factory source of a fresh digest instance
     * @return the lowercase hexadecimal fingerprint
     * @throws ApiException {@code 500} with code {@code INTERNAL_ERROR} when the
     *         platform cannot provide SHA-256; no payload detail is exposed
     */
    public static RequestDigest of(String organizationId, String subject, String command, String payload,
                                   MessageDigestFactory factory) {
        Objects.requireNonNull(factory, "factory");
        try {
            byte[] digest = factory.create("SHA-256")
                    .digest(canonicalBytes(organizationId, subject, command, payload));
            return new RequestDigest(HEX.formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(500, "INTERNAL_ERROR", "request digest could not be computed");
        }
    }

    /**
     * Creates a fresh message digest for a named algorithm.
     */
    @FunctionalInterface
    public interface MessageDigestFactory {

        /**
         * Create a fresh digest.
         *
         * @param algorithm JCA algorithm name
         * @return a new mutable digest instance
         * @throws NoSuchAlgorithmException when the algorithm is unavailable
         */
        MessageDigest create(String algorithm) throws NoSuchAlgorithmException;
    }
}
