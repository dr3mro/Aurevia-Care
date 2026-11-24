package com.aurevia.api.contract;

import java.util.regex.Pattern;

/**
 * The single bounded facility-code rule applied at API trust boundaries (ARC-02, ARC-03).
 *
 * <p>Scheduling, check-in, tenancy and clinical-note validators previously each
 * declared the same {@code [A-Z0-9_-]{2,32}} expression, so tightening the length
 * or allowed characters meant editing five places and could drift into an
 * inconsistent identifier rule. One typed primitive now owns the expression: a
 * caller either accepts the same upper-case ASCII code or translates one shared
 * rejection into its own field-specific {@code 422}. Non-ASCII scripts such as
 * Arabic are rejected so a code stays a safe routing and log value.</p>
 *
 * <p>The primitive throws {@code IllegalArgumentException} rather than an
 * {@code ApiException}: callers own their field-specific error code and message
 * and translate this rejection at their own boundary, exactly as they do for
 * {@link com.aurevia.api.contract.CatalogCode}.</p>
 *
 * @param value the validated code, 2-32 uppercase letters, digits, {@code -} or {@code _}
 */
public record FacilityCode(String value) {

    /** Single deterministic detail message shared with each caller's error translation. */
    public static final String INVALID_MESSAGE = "code must be 2-32 uppercase letters, digits, - or _";

    /**
     * The one regex fragment for a code, shared with callers that parse a compound
     * token such as a {@code <code>:<id>} cursor so the rule cannot be re-declared.
     */
    public static final String PATTERN = "[A-Z0-9_-]{2,32}";

    private static final Pattern ALLOWED = Pattern.compile(PATTERN);

    /**
     * Validate a facility code and return it as a typed value.
     *
     * <p>This is the single entry point used by the trust-boundary validators; a
     * rejection always carries the same status-free, non-sensitive detail message
     * so each caller can render its own machine-readable error without
     * re-deriving the rule.</p>
     *
     * @param value candidate code, possibly {@code null} or malformed
     * @return the validated code
     * @throws IllegalArgumentException when the value is null, shorter than two
     *         or longer than 32 characters, or contains a character outside the
     *         shared alphabet
     */
    public static FacilityCode facilityCode(String value) {
        return new FacilityCode(value);
    }

    /**
     * Reject a malformed code at construction.
     *
     * @throws IllegalArgumentException when the value does not match the single
     *         shared rule
     */
    public FacilityCode {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }
}
