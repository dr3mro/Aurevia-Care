package com.aurevia.api.contract;

/**
 * A bounded page request with a tenant-bound, stable-ordering cursor (ARC-03).
 *
 * <p>Absent cursor and limit select the first page with the default size. The
 * limit is parsed here so a malformed or out-of-range value is always rendered
 * as the machine-readable {@code INVALID_LIMIT} error rather than a generic
 * input failure.</p>
 *
 * @param cursor decoded cursor for a subsequent page, or {@code null} for the first
 * @param limit requested page size, between 1 and {@value #MAX_LIMIT}
 */
public record PageRequest(Cursor cursor, int limit) {

    /** Page size used when the limit is absent or blank. */
    public static final int DEFAULT_LIMIT = 50;
    /** Largest accepted page size. */
    public static final int MAX_LIMIT = 200;

    /**
     * Parse the paging query parameters for the active tenant.
     *
     * @param cursorToken opaque cursor token, possibly {@code null} or blank
     * @param limitValue raw limit text, possibly {@code null} or blank
     * @param organizationId active tenant from authenticated context
     * @return the validated page request
     * @throws com.aurevia.api.ApiException {@code 422} with code
     *         {@code INVALID_LIMIT} or {@code INVALID_CURSOR} for invalid input
     */
    public static PageRequest parse(String cursorToken, String limitValue, String organizationId) {
        int limit = DEFAULT_LIMIT;
        if (limitValue != null && !limitValue.isBlank()) {
            try {
                limit = Integer.parseInt(limitValue.trim());
            } catch (NumberFormatException exception) {
                throw ApiErrorCode.INVALID_LIMIT.exception();
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw ApiErrorCode.INVALID_LIMIT.exception();
            }
        }
        Cursor cursor = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            cursor = Cursor.decode(cursorToken, organizationId);
        }
        return new PageRequest(cursor, limit);
    }
}
