package com.aurevia.api.contract;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single canonical UUID text rule applied at API trust boundaries (ARC-03).
 *
 * <p>Request validators previously disagreed on what a UUID is: some accepted
 * any shape {@code java.util.UUID.fromString} tolerates (including non-canonical
 * forms such as {@code 1-1-1-1-1}) while others required the strict
 * {@code 8-4-4-4-12} hex shape but returned the caller's original casing. One
 * typed primitive now enforces the strict shape and returns canonical lowercase
 * text, so identical client input is accepted or rejected the same way on every
 * endpoint and never silently rewritten into a different identifier.</p>
 *
 * <p>This primitive throws {@code IllegalArgumentException} rather than an
 * {@code ApiException}: callers own their field-specific error code and message
 * and translate this rejection at their own boundary, exactly as they do for
 * {@link com.aurevia.api.contract.CatalogCode}.</p>
 *
 * @param value the validated canonical lowercase UUID text
 */
public record CanonicalUuid(String value) {

    /** Single deterministic detail message shared with each caller's error translation. */
    public static final String INVALID_MESSAGE = "identifier must be a canonical UUID";

    private static final Pattern CANONICAL = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Validate a UUID and return it as a typed canonical value.
     *
     * <p>This is the single entry point used by request validators; a rejection
     * always carries the same status-free, non-sensitive detail so each caller
     * can render its own machine-readable error without re-deriving the rule.</p>
     *
     * @param value candidate UUID text, possibly {@code null} or non-canonical
     * @return the validated UUID with canonical lowercase hex
     * @throws IllegalArgumentException when the value is null or is not a strict
     *         {@code 8-4-4-4-12} hex UUID
     */
    public static CanonicalUuid uuid(String value) {
        return new CanonicalUuid(value);
    }

    /**
     * Reject a non-canonical value and normalize accepted uppercase hex to lowercase.
     *
     * @throws IllegalArgumentException when the value does not match the single
     *         shared canonical rule
     */
    public CanonicalUuid {
        if (value == null || !CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
        value = value.toLowerCase(Locale.ROOT);
    }
}
