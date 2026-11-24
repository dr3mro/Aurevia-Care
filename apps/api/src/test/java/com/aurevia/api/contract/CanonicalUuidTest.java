package com.aurevia.api.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** The single canonical UUID text rule shared by request-boundary validators (ARC-03). */
class CanonicalUuidTest {

    private static final String CANONICAL = "11111111-2222-3333-4444-555555555555";
    private static final String UPPERCASE = CANONICAL.toUpperCase(Locale.ROOT);

    @Test
    void acceptsCanonicalText() {
        assertEquals(CANONICAL, CanonicalUuid.uuid(CANONICAL).value());
    }

    @Test
    void normalizesUppercaseHexToCanonicalLowercase() {
        assertEquals(CANONICAL, CanonicalUuid.uuid(UPPERCASE).value());
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                CanonicalUuid.uuid("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE").value());
    }

    @Test
    void rejectsNullAndEmptyText() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> CanonicalUuid.uuid(null));
        assertEquals(CanonicalUuid.INVALID_MESSAGE, missing.getMessage());
        assertEquals(CanonicalUuid.INVALID_MESSAGE,
                assertThrows(IllegalArgumentException.class, () -> CanonicalUuid.uuid("")).getMessage());
    }

    @Test
    void rejectsShortFormAndMalformedText() {
        for (String value : List.of("1-1-1-1-1", "nope", CANONICAL + "0", CANONICAL.replace('-', 'x'))) {
            assertEquals(CanonicalUuid.INVALID_MESSAGE,
                    assertThrows(IllegalArgumentException.class, () -> CanonicalUuid.uuid(value)).getMessage());
        }
    }
}
