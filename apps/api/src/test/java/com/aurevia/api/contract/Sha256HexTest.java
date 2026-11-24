package com.aurevia.api.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** The single canonical SHA-256 hex text rule shared by request-boundary validators. */
class Sha256HexTest {

    private static final String LOWERCASE = "a".repeat(64);
    private static final String UPPERCASE = LOWERCASE.toUpperCase(Locale.ROOT);

    @Test
    void acceptsValidLowercaseHex() {
        assertEquals(LOWERCASE, Sha256Hex.sha256Hex(LOWERCASE).value());
    }

    @Test
    void normalizesUppercaseHexToLowercase() {
        assertEquals(LOWERCASE, Sha256Hex.sha256Hex(UPPERCASE).value());
        assertEquals("0123456789abcdef".repeat(4),
                Sha256Hex.sha256Hex("0123456789ABCDEF".repeat(4)).value());
    }

    @Test
    void rejectsNullAndEmptyText() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> Sha256Hex.sha256Hex(null));
        assertEquals(Sha256Hex.INVALID_MESSAGE, missing.getMessage());
        assertEquals(Sha256Hex.INVALID_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> Sha256Hex.sha256Hex("")).getMessage());
    }

    @Test
    void rejectsWrongLength() {
        for (String value : List.of("abc", LOWERCASE.substring(1), LOWERCASE + "a")) {
            assertEquals(Sha256Hex.INVALID_MESSAGE,
                    assertThrows(IllegalArgumentException.class, () -> Sha256Hex.sha256Hex(value)).getMessage());
        }
    }

    @Test
    void rejectsNonHexCharacters() {
        for (char character : List.of('g', 'z', '/', ' ')) {
            String value = character + LOWERCASE.substring(1);
            assertEquals(Sha256Hex.INVALID_MESSAGE,
                    assertThrows(IllegalArgumentException.class, () -> Sha256Hex.sha256Hex(value)).getMessage());
        }
    }
}
