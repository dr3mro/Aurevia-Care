package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Escape rules for the bounded HL7 v2 subset. */
class Hl7EscapeTest {

    private final Hl7Delimiters delimiters = Hl7Delimiters.standard();

    @Test
    void encodesEveryReservedCharacterAndRoundTrips() {
        String raw = "A|^~&\\" + "\r\n";
        String encoded = Hl7Escape.encode(raw, delimiters);

        assertTrue(encoded.contains("\\F\\"), "field separator escaped");
        assertTrue(encoded.contains("\\S\\"), "component separator escaped");
        assertTrue(encoded.contains("\\R\\"), "repetition separator escaped");
        assertTrue(encoded.contains("\\T\\"), "subcomponent separator escaped");
        assertTrue(encoded.contains("\\E\\"), "escape character escaped");
        assertTrue(encoded.contains("\\X0d\\"), "carriage return escaped");
        assertTrue(encoded.contains("\\X0a\\"), "line feed escaped");
        assertEquals(raw, Hl7Escape.decode(encoded, delimiters));
    }

    @Test
    void encodesNullAsEmpty() {
        assertEquals("", Hl7Escape.encode(null, delimiters));
    }

    @Test
    void decodesNullAndEmptyAsEmpty() {
        assertEquals("", Hl7Escape.decode(null, delimiters));
        assertEquals("", Hl7Escape.decode("", delimiters));
    }

    @Test
    void leavesPlainTextUntouched() {
        assertEquals("plain text 123", Hl7Escape.decode("plain text 123", delimiters));
    }

    @Test
    void preservesTrailingOrIncompleteEscape() {
        assertEquals("\\", Hl7Escape.decode("\\", delimiters));
        assertEquals("\\F", Hl7Escape.decode("\\F", delimiters));
        assertEquals("\\FZ", Hl7Escape.decode("\\FZ", delimiters));
    }

    @Test
    void preservesUnknownEscapeCode() {
        assertEquals("\\Q\\", Hl7Escape.decode("\\Q\\", delimiters));
    }

    @Test
    void decodesSingleCharacterEscapeCodes() {
        assertEquals("|", Hl7Escape.decode("\\F\\", delimiters));
        assertEquals("^", Hl7Escape.decode("\\S\\", delimiters));
        assertEquals("~", Hl7Escape.decode("\\R\\", delimiters));
        assertEquals("&", Hl7Escape.decode("\\T\\", delimiters));
        assertEquals("\\", Hl7Escape.decode("\\E\\", delimiters));
        assertEquals("x", Hl7Escape.decode("\\H\\x\\N\\", delimiters));
    }

    @Test
    void decodesHexEscape() {
        assertEquals("\r", Hl7Escape.decode("\\X0d\\", delimiters));
        assertEquals("AB", Hl7Escape.decode("\\X4142\\", delimiters));
    }

    @Test
    void preservesMalformedHexEscape() {
        assertEquals("\\X0d", Hl7Escape.decode("\\X0d", delimiters));
        assertEquals("\\XGG\\", Hl7Escape.decode("\\XGG\\", delimiters));
        assertEquals("\\XG0\\", Hl7Escape.decode("\\XG0\\", delimiters));
        assertEquals("\\X0G\\", Hl7Escape.decode("\\X0G\\", delimiters));
        assertEquals("\\X0\\", Hl7Escape.decode("\\X0\\", delimiters));
        assertEquals("\\X\\", Hl7Escape.decode("\\X\\", delimiters));
        assertEquals("\\X414243\\", Hl7Escape.decode("\\X414243\\", delimiters));
    }
}
