package com.aurevia.api.contract;

import java.util.regex.Pattern;

/**
 * An opaque idempotency key for a protected command (ARC-03, ARC-04).
 *
 * <p>The key is scoped by the caller to a tenant, an authenticated subject and
 * a command name; the value itself is treated as opaque. It is bounded to 255
 * safe characters so it can be used as a durable cache key without becoming an
 * injection vector or an unbounded log field.</p>
 *
 * @param value the validated, trimmed key value
 */
public record IdempotencyKey(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._:-]{1,255}");

    /**
     * Validate a key value.
     *
     * @param value opaque key value
     * @throws com.aurevia.api.ApiException {@code 422} with code
     *         {@code INVALID_IDEMPOTENCY_KEY} when the value is missing or malformed
     */
    public IdempotencyKey {
        if (value == null || !ALLOWED.matcher(value).matches()) {
            throw ApiErrorCode.INVALID_IDEMPOTENCY_KEY.exception();
        }
    }

    /**
     * Parse the {@code Idempotency-Key} request header.
     *
     * @param header raw header value, possibly {@code null} or blank
     * @return the validated key
     * @throws com.aurevia.api.ApiException {@code 428} when absent,
     *         {@code 422} when malformed
     */
    public static IdempotencyKey parse(String header) {
        if (header == null || header.isBlank()) {
            throw ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED.exception();
        }
        return new IdempotencyKey(header.trim());
    }
}
