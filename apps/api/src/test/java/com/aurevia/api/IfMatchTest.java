package com.aurevia.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** ARC-03 version precondition parsing: 428 when absent, 422 when malformed. */
class IfMatchTest {

    @Test
    void requiresAPrecondition() {
        assertEquals(428, assertThrows(ApiException.class, () -> IfMatch.parse(null)).status());
        assertEquals(428, assertThrows(ApiException.class, () -> IfMatch.parse("  ")).status());
        assertEquals("PRECONDITION_REQUIRED",
                assertThrows(ApiException.class, () -> IfMatch.parse(null)).code());
    }

    @Test
    void acceptsStrongWeakAndQuotedValidators() {
        assertEquals(3, IfMatch.parse("3"));
        assertEquals(3, IfMatch.parse(" \"3\" "));
        assertEquals(3, IfMatch.parse("W/\"3\""));
        assertEquals(3, IfMatch.parse("W/3"));
        assertEquals(11, IfMatch.parse("\"11\""));
    }

    @Test
    void rejectsMalformedAndNonPositiveVersions() {
        assertEquals(422, assertThrows(ApiException.class, () -> IfMatch.parse("abc")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> IfMatch.parse("W/abc")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> IfMatch.parse("0")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> IfMatch.parse("-2")).status());
        assertEquals("INVALID_IF_MATCH",
                assertThrows(ApiException.class, () -> IfMatch.parse("x")).code());
        // A lone quote and an unbalanced quote reach the parser unchanged.
        assertEquals(422, assertThrows(ApiException.class, () -> IfMatch.parse("\"")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> IfMatch.parse("\"3")).status());
    }
}
