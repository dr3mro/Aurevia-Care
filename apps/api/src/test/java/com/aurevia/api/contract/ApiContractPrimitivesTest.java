package com.aurevia.api.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** ARC-03/ARC-04 wire primitives: version, idempotency key, digest, cursor and paging. */
class ApiContractPrimitivesTest {

    private static final String ORG = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_ORG = "99999999-8888-7777-6666-555555555555";

    @Test
    void versionDefaultsToV1AndExposesItsRoute() {
        assertSame(ApiVersion.V1, ApiVersion.from(null));
        assertSame(ApiVersion.V1, ApiVersion.from(""));
        assertSame(ApiVersion.V1, ApiVersion.from("   "));
        assertEquals("/api/v1", ApiVersion.V1.route());
    }

    @Test
    void versionAcceptsCaseInsensitiveV1() {
        assertSame(ApiVersion.V1, ApiVersion.from("v1"));
        assertSame(ApiVersion.V1, ApiVersion.from("V1"));
        assertSame(ApiVersion.V1, ApiVersion.from(" v1 "));
    }

    @Test
    void versionRejectsUnsupportedValue() {
        ApiException failure = assertThrows(ApiException.class, () -> ApiVersion.from("v2"));
        assertEquals(422, failure.status());
        assertEquals("UNSUPPORTED_API_VERSION", failure.code());
    }

    @Test
    void errorCodesCarryStatusMessageAndException() {
        assertEquals(428, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED.status());
        assertEquals(409, ApiErrorCode.IDEMPOTENCY_CONFLICT.status());
        assertEquals(412, ApiErrorCode.STALE_VERSION.status());
        assertEquals(422, ApiErrorCode.INVALID_CURSOR.status());
        assertEquals(404, ApiErrorCode.NOT_FOUND.status());
        assertEquals("idempotency scope is incomplete", ApiErrorCode.INVALID_SCOPE.message());
        ApiException failure = ApiErrorCode.INVALID_LIMIT.exception();
        assertEquals(422, failure.status());
        assertEquals("INVALID_LIMIT", failure.code());
        assertEquals("limit must be between 1 and 200", failure.getMessage());
    }

    @Test
    void idempotencyKeyIsRequired() {
        assertEquals(428, assertThrows(ApiException.class, () -> IdempotencyKey.parse(null)).status());
        assertEquals(428, assertThrows(ApiException.class, () -> IdempotencyKey.parse("  ")).status());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED",
                assertThrows(ApiException.class, () -> IdempotencyKey.parse(null)).code());
    }

    @Test
    void idempotencyKeyRejectsMalformedValues() {
        assertEquals(422, assertThrows(ApiException.class, () -> IdempotencyKey.parse("bad key")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> IdempotencyKey.parse("a".repeat(256))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new IdempotencyKey(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new IdempotencyKey("")).status());
        assertEquals("INVALID_IDEMPOTENCY_KEY",
                assertThrows(ApiException.class, () -> IdempotencyKey.parse("bad key")).code());
    }

    @Test
    void idempotencyKeyAcceptsOpaqueTrimmedValue() {
        assertEquals("abc-123", IdempotencyKey.parse(" abc-123 ").value());
        assertEquals("u:1.v_2", new IdempotencyKey("u:1.v_2").value());
        assertEquals("a".repeat(255), IdempotencyKey.parse("a".repeat(255)).value());
    }

    @Test
    void bytePartsRoundTripUtf8WithExplicitLengths() {
        byte[] encoded = ByteParts.encode(ORG, "2026-11-19T10:00:00Z", "科幻-١");
        assertEquals(List.of(ORG, "2026-11-19T10:00:00Z", "科幻-١"), ByteParts.decode(encoded, 3));
    }

    @Test
    void bytePartsRejectMalformedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> ByteParts.decode(new byte[11], 3));
        byte[] negative = ByteBuffer.allocate(4).putInt(-1).array();
        assertThrows(IllegalArgumentException.class, () -> ByteParts.decode(negative, 1));
        byte[] tooLong = ByteBuffer.allocate(4).putInt(257).array();
        assertThrows(IllegalArgumentException.class, () -> ByteParts.decode(tooLong, 1));
        byte[] truncated = ByteBuffer.allocate(6).putInt(5).put(new byte[] {1, 2}).array();
        assertThrows(IllegalArgumentException.class, () -> ByteParts.decode(truncated, 1));
        byte[] trailing = ByteParts.encode("a");
        assertThrows(IllegalArgumentException.class,
                () -> ByteParts.decode(ByteBuffer.allocate(trailing.length + 1).put(trailing).put((byte) 7).array(), 1));
    }

    @Test
    void digestCanonicalBytesAreLengthPrefixedAndOrderSensitive() {
        byte[] first = RequestDigest.canonicalBytes(ORG, "subject", "accept", "payload");
        assertArrayEquals(first, RequestDigest.canonicalBytes(ORG, "subject", "accept", "payload"));
        assertNotEquals(RequestDigest.canonicalBytes("subject", ORG, "accept", "payload"), first);
        assertNotEquals(RequestDigest.canonicalBytes(ORG, "subject", "accept", "payload٢"), first);
    }

    @Test
    void digestComputesLowercaseHexSha256() throws NoSuchAlgorithmException {
        byte[] canonical = RequestDigest.canonicalBytes(ORG, "subject", "accept", "payload");
        String expected = java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        RequestDigest digest = RequestDigest.of(ORG, "subject", "accept", "payload");
        assertEquals(expected, digest.hex());
        assertEquals(digest, RequestDigest.of(ORG, "subject", "accept", "payload"));
        assertNotEquals(digest, RequestDigest.of(ORG, "subject", "accept", "different"));
    }

    @Test
    void digestFailsClosedWhenSha256IsUnavailable() throws NoSuchAlgorithmException {
        RequestDigest.MessageDigestFactory unavailable = algorithm -> {
            throw new NoSuchAlgorithmException(algorithm);
        };
        ApiException failure = assertThrows(ApiException.class,
                () -> RequestDigest.of(ORG, "subject", "accept", "payload", unavailable));
        assertEquals(500, failure.status());
        assertEquals("INTERNAL_ERROR", failure.code());
        assertEquals("request digest could not be computed", failure.getMessage());
    }

    @Test
    void cursorRoundTripsTenantScopedOrderingKey() {
        Cursor cursor = new Cursor(ORG, "2026-11-19T10:00:00Z", "order-1");
        String token = Cursor.encode(cursor);
        assertEquals(cursor, Cursor.decode(token, ORG));
    }

    @Test
    void cursorRejectsInvalidTokens() {
        String token = Cursor.encode(new Cursor(ORG, "sort", "id"));
        assertEquals(422, assertThrows(ApiException.class, () -> Cursor.decode(null, ORG)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> Cursor.decode("  ", ORG)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> Cursor.decode("x".repeat(1201), ORG)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> Cursor.decode("!!!", ORG)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> Cursor.decode(token + "=", ORG)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> Cursor.decode(java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(new byte[4]), ORG)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> Cursor.decode(token, OTHER_ORG)).status());
        assertEquals("INVALID_CURSOR",
                assertThrows(ApiException.class, () -> Cursor.decode(token, OTHER_ORG)).code());
    }

    @Test
    void cursorRejectsNonCanonicalPaddedToken() {
        Cursor cursor = new Cursor("a", "b", "cd");
        String canonical = Cursor.encode(cursor);
        String padded = java.util.Base64.getUrlEncoder().encodeToString(ByteParts.encode("a", "b", "cd"));
        assertNotEquals(canonical, padded);
        assertEquals(422, assertThrows(ApiException.class, () -> Cursor.decode(padded, "a")).status());
        assertEquals("INVALID_CURSOR",
                assertThrows(ApiException.class, () -> Cursor.decode(padded, "a")).code());
    }

    @Test
    void cursorRejectsInvalidParts() {
        assertEquals(422, assertThrows(ApiException.class, () -> new Cursor(null, "sort", "id")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Cursor(ORG, "", "id")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new Cursor(ORG, "sort", "a".repeat(257))).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Cursor(ORG, "bad|sort", "id")).status());
        String bytes = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ByteParts.encode(ORG, "sort", "bad|id"));
        assertEquals(422, assertThrows(ApiException.class, () -> Cursor.decode(bytes, ORG)).status());
    }

    @Test
    void pageDefaultsToFiftyAndFirstPage() {
        PageRequest first = PageRequest.parse(null, null, ORG);
        assertNull(first.cursor());
        assertEquals(50, first.limit());
        assertEquals(50, PageRequest.parse("", "  ", ORG).limit());
        assertNull(PageRequest.parse("", null, ORG).cursor());
    }

    @Test
    void pageAcceptsCursorAndBoundedLimit() {
        String token = Cursor.encode(new Cursor(ORG, "sort", "id"));
        PageRequest request = PageRequest.parse(token, "200", ORG);
        assertEquals(new Cursor(ORG, "sort", "id"), request.cursor());
        assertEquals(200, request.limit());
        assertEquals(1, PageRequest.parse(null, "1", ORG).limit());
    }

    @Test
    void pageRejectsInvalidLimitsAndForeignCursor() {
        for (String limit : List.of("0", "201", "-1", "abc")) {
            assertEquals(422, assertThrows(ApiException.class,
                    () -> PageRequest.parse(null, limit, ORG)).status());
        }
        assertEquals("INVALID_LIMIT", assertThrows(ApiException.class,
                () -> PageRequest.parse(null, "abc", ORG)).code());
        String token = Cursor.encode(new Cursor(ORG, "sort", "id"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> PageRequest.parse(token, null, OTHER_ORG)).status());
    }

    @Test
    void canonicalUsesUtf8ByteLengthsNotCharCounts() {
        assertEquals(StandardCharsets.UTF_8.name(), "UTF-8");
        byte[] arabic = RequestDigest.canonicalBytes(ORG, "subject", "accept", "رسالة");
        byte[] latin = RequestDigest.canonicalBytes(ORG, "subject", "accept", "abcdef");
        assertNotEquals(arabic.length, latin.length);
    }
}
