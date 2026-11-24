package com.aurevia.api.contract;

import java.util.regex.Pattern;

/**
 * The single bounded catalog-code rule applied at API trust boundaries (CLN-03).
 *
 * <p>Codes are tenant-local identifiers shared by catalog families, clinical
 * dictionaries and diagnosis/procedure entries. Keeping one typed primitive
 * means length, allowed characters and script handling are tightened in one
 * place and cannot drift between callers. Only ASCII letters and digits plus
 * {@code _ . : -} are accepted; non-ASCII scripts such as Arabic are rejected
 * with a deterministic error so a code stays a safe routing and log value.</p>
 *
 * <p>This primitive throws {@code IllegalArgumentException} rather than an
 * {@code ApiException}: the two dictionary records it validates already expose
 * an illegal-argument contract, and the API service translates that exception
 * into its own machine-readable error at the web boundary.</p>
 *
 * @param value the validated code, 2-64 letters, digits or {@code _ . : -}
 */
public record CatalogCode(String value) {

    /** Single deterministic detail message shared with the API service's error translation. */
    public static final String INVALID_MESSAGE = "code must be 2-64 letters, digits or _ . : -";

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{1,63}");

    /**
     * Validate a code and return it as a typed value.
     *
     * <p>This is the only entry point used by the catalog callers; a rejection
     * always carries the same status-free, non-sensitive detail message so
     * each caller can render its own domain error without re-deriving the rule.</p>
     *
     * @param value candidate code, possibly {@code null} or malformed
     * @return the validated code
     * @throws IllegalArgumentException when the value is null, too short, too
     *         long or contains a character outside the shared alphabet
     */
    public static CatalogCode code(String value) {
        return new CatalogCode(value);
    }

    /**
     * Reject a malformed code at construction.
     *
     * @throws IllegalArgumentException when the value does not match the single
     *         shared rule
     */
    public CatalogCode {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }
}
