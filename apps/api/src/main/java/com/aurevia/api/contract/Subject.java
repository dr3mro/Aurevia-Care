package com.aurevia.api.contract;

import java.util.regex.Pattern;

/**
 * The single canonical lowercase subject key rule at API trust boundaries
 * (IAM-01, IAM-02, ARC-03, ARC-04, OPS-02).
 *
 * <p>A subject is an opaque, tenant-scoped key used to identify an actor, an
 * invitee or a patient for authorization and audit. Request validators used to
 * disagree about its shape: some admitted dots and underscores while others
 * only admitted hyphens, so the same client input was accepted on one endpoint
 * and rejected on another. One typed primitive now owns the rule, its length
 * bounds and its script handling. The alphabet is ASCII lowercase letters and
 * digits after an alphanumeric start, with {@code . _ -} allowed inside the
 * key. A colon and any non-ASCII script (for example Arabic) are rejected so a
 * subject stays a safe routing, log and cursor value.</p>
 *
 * <p>This primitive throws {@code IllegalArgumentException} rather than an
 * {@code ApiException}: callers own their field-specific error code and message
 * and translate this rejection at their own boundary, exactly as they do for
 * {@link com.aurevia.api.contract.CanonicalUuid} and
 * {@link com.aurevia.api.contract.CatalogCode}.</p>
 *
 * @param value the validated bounded lowercase subject key
 */
public record Subject(String value) {

    /** Single deterministic detail message shared with each caller's error translation. */
    public static final String INVALID_MESSAGE = "subject must be 2-64 lowercase letters, digits, . _ or -";

    /**
     * The shared regex fragment for composite boundary keys that embed a subject.
     *
     * <p>Callers that parse a larger token (such as a staff cursor) must build it
     * from this fragment rather than restating the alphabet, so the subject part
     * can never drift from {@link com.aurevia.api.contract.Subject#subject(String)}.</p>
     */
    public static final String PATTERN = "[a-z0-9][a-z0-9._-]{1,63}";

    private static final Pattern ALLOWED = Pattern.compile(PATTERN);

    /**
     * Validate a subject and return it as a typed canonical value.
     *
     * <p>This is the single entry point used by request validators; a rejection
     * always carries the same status-free, non-sensitive detail so each caller
     * can render its own machine-readable error without re-deriving the rule.</p>
     *
     * @param value candidate subject, possibly {@code null} or malformed
     * @return the validated subject, unchanged
     * @throws IllegalArgumentException when the value is null, empty, outside
     *         2-64 characters or contains a character outside the shared alphabet
     */
    public static Subject subject(String value) {
        return new Subject(value);
    }

    /**
     * Reject a value that is not a bounded lowercase subject key.
     *
     * @throws IllegalArgumentException when the value does not match the single
     *         shared rule
     */
    public Subject {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw new IllegalArgumentException(INVALID_MESSAGE);
        }
    }
}
