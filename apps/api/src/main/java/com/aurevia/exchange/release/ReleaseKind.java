package com.aurevia.exchange.release;

import java.util.Locale;

import com.aurevia.api.ApiException;

/**
 * The three release timing policies.
 *
 * <p>{@code IMMEDIATE} releases are visible as soon as the authorization is
 * effective, {@code TIMED} releases become visible at a configured future
 * instant, and {@code HOLD} releases stay invisible until an authorized reviewer
 * lifts the hold. A hold never opens access by elapsed time alone.</p>
 */
public enum ReleaseKind {
    /** Visible once the authorization is effective. */
    IMMEDIATE,
    /** Visible only from the configured effective instant. */
    TIMED,
    /** Invisible until an authorized reviewer explicitly lifts it. */
    HOLD;

    /**
     * Parse a client-supplied policy kind, case-insensitively.
     *
     * @param raw client-supplied value
     * @return the parsed policy
     * @throws ApiException {@code 422} when the value is blank or unknown
     */
    public static ReleaseKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(422, "INVALID_RELEASE_KIND", "release kind is required");
        }
        try {
            return ReleaseKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(422, "INVALID_RELEASE_KIND", "unknown release kind");
        }
    }
}
