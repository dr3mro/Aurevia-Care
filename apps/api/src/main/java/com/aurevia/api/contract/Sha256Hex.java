package com.aurevia.api.contract;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The canonical SHA-256 hex text rule shared by the migrated event and import validators.
 *
 * <p>The ARC-05 delivery and OPS-08 import boundaries previously declared the
 * {@code [0-9a-f]{64}} checksum rule independently. The delivery validator accepted
 * uppercase hex and normalized it, while the import validator rejected it, so
 * identical client input was treated differently and a future change (uppercase,
 * prefix or length support) would have to be made in both copies and could drift
 * into an inconsistent trust boundary. One typed primitive now owns the accepted
 * shape: a value is accepted when it is exactly 64 hexadecimal digits in either
 * case, and the validated value is offered in canonical lowercase form.</p>
 *
 * <p>Callers decide how to apply the canonical form to fit their domain. The
 * delivery validator persists the normalized value as a derived digest, while the
 * import validator uses this primitive only as a verdict and preserves the caller's
 * original checksum text as provenance evidence (OPS-08). Other checksum boundaries
 * keep their own rule until a separate change migrates them.</p>
 *
 * <p>This primitive throws {@code IllegalArgumentException} rather than an
 * {@code ApiException}: callers own their field-specific error code and message
 * and translate this rejection at their own boundary, exactly as they do for
 * {@link com.aurevia.api.contract.CanonicalUuid}.</p>
 *
 * @param value the validated canonical lowercase hexadecimal digest
 */
public record Sha256Hex(String value) {

    /** Single deterministic detail message shared with each caller's error translation. */
    public static final String INVALID_MESSAGE = "digest must be 64 hexadecimal characters";

    private static final Pattern HEX_256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * Validate a SHA-256 hex value and return it as a typed canonical value.
     *
     * <p>This is the single entry point used by request validators; a rejection
     * always carries the same status-free, non-sensitive detail so each caller
     * can render its own machine-readable error without re-deriving the rule.</p>
     *
     * @param value candidate hexadecimal digest text, possibly {@code null},
     *              mixed case or malformed
     * @return the validated digest with canonical lowercase hex
     * @throws IllegalArgumentException when the value is null or is not exactly
     *         64 hexadecimal digits
     */
    public static Sha256Hex sha256Hex(String value) {
        return new Sha256Hex(value);
    }

    /**
     * Reject a malformed value and normalize accepted uppercase hex to lowercase.
     *
     * @throws IllegalArgumentException when the value does not match the single
     *         shared 64-hex-digit rule
     */
    public Sha256Hex {
        if (value == null) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!HEX_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        value = normalized;
    }
}
