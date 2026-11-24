package com.aurevia.api.contract;

import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An opaque, tenant-bound, stable-ordering cursor (ARC-02, ARC-03).
 *
 * <p>The token encodes the active organization, a stable sort key and a unique
 * identifier as canonical base64url without padding. A cursor from another
 * tenant is rejected with the same generic {@code INVALID_CURSOR} error as a
 * malformed token, so it cannot be used as a tenant-existence oracle. Only
 * safe-charset parts are accepted; clients never construct the sort order.</p>
 *
 * @param organizationId tenant the cursor was issued for
 * @param sortKey stable tenant-scoped ordering value
 * @param id unique identifier completing the ordering key
 */
public record Cursor(String organizationId, String sortKey, String id) {

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9._:-]{1,256}");
    private static final int MAX_TOKEN_CHARS = 1200;

    /**
     * Validate the three bounded cursor parts.
     *
     * @param organizationId tenant identifier
     * @param sortKey stable ordering value
     * @param id unique identifier
     * @throws com.aurevia.api.ApiException {@code 422} with code
     *         {@code INVALID_CURSOR} for any null, empty, oversized or unsafe part
     */
    public Cursor {
        requirePart(organizationId);
        requirePart(sortKey);
        requirePart(id);
    }

    private static void requirePart(String part) {
        if (part == null || !PART.matcher(part).matches()) {
            throw ApiErrorCode.INVALID_CURSOR.exception();
        }
    }

    /**
     * Encode a cursor as a canonical base64url token.
     *
     * @param cursor cursor to encode
     * @return opaque token without padding
     */
    public static String encode(Cursor cursor) {
        Objects.requireNonNull(cursor, "cursor");
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteParts.encode(cursor.organizationId(), cursor.sortKey(), cursor.id()));
    }

    /**
     * Decode a cursor and verify it belongs to the active tenant.
     *
     * @param token opaque token, possibly {@code null} or blank for a first page
     * @param expectedOrganizationId active tenant from authenticated context
     * @return the decoded cursor
     * @throws com.aurevia.api.ApiException {@code 422} with code
     *         {@code INVALID_CURSOR} for malformed, non-canonical or foreign tokens
     */
    public static Cursor decode(String token, String expectedOrganizationId) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_CHARS) {
            throw ApiErrorCode.INVALID_CURSOR.exception();
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException exception) {
            throw ApiErrorCode.INVALID_CURSOR.exception();
        }
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(payload).equals(token)) {
            throw ApiErrorCode.INVALID_CURSOR.exception();
        }
        List<String> parts;
        try {
            parts = ByteParts.decode(payload, 3);
        } catch (IllegalArgumentException exception) {
            throw ApiErrorCode.INVALID_CURSOR.exception();
        }
        Cursor cursor = new Cursor(parts.get(0), parts.get(1), parts.get(2));
        if (!cursor.organizationId().equals(expectedOrganizationId)) {
            throw ApiErrorCode.INVALID_CURSOR.exception();
        }
        return cursor;
    }
}
