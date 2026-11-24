package com.aurevia.api.contract;

import java.util.regex.Pattern;

/**
 * The single bounded issuer/code rule applied at patient and laboratory API trust boundaries
 * (IAM-03, LAB-01, ARC-03).
 *
 * <p>Registration issuers and laboratory issuer, specimen-type, site and quality codes were
 * validated by three copies of the same declaration. One typed primitive now owns the rule so
 * length, allowed characters and script handling cannot drift between callers. Only ASCII
 * letters and digits plus {@code _ . : -} are accepted; non-ASCII scripts such as Arabic are
 * rejected so an identifier stays a safe routing and log value.</p>
 *
 * <p>This primitive throws {@code IllegalArgumentException} rather than an
 * {@code ApiException}: each caller owns its field-specific error code and translates this
 * rejection at its own boundary, exactly as it does for
 * {@link com.aurevia.api.contract.CatalogCode}.</p>
 *
 * @param value the validated issuer or code, 2-32 letters, digits or {@code _ . : -}
 */
public record IssuerCode(String value) {

    /** Single deterministic detail message shared with each caller's error translation. */
    public static final String INVALID_MESSAGE = "issuer/code must be 2-32 letters, digits or _ . : -";

    /** The one regex fragment for an issuer or code, shared with callers and tests. */
    public static final String PATTERN = "[A-Za-z0-9_.:-]{2,32}";

    private static final Pattern ALLOWED = Pattern.compile(PATTERN);

    /**
     * Validate an issuer or code and return it as a typed value.
     *
     * @param value candidate value, possibly {@code null} or malformed
     * @return the validated issuer or code
     * @throws IllegalArgumentException when the value is null, shorter than two, longer
     *         than 32 characters or contains a character outside the shared alphabet
     */
    public static IssuerCode issuerCode(String value) {
        return new IssuerCode(value);
    }

    /**
     * Reject a malformed issuer or code at construction.
     *
     * @throws IllegalArgumentException when the value does not match the single shared rule
     */
    public IssuerCode {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }
}
