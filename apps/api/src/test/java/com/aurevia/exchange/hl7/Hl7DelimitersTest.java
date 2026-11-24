package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Delimiter declaration parsing for the bounded HL7 v2 subset. */
class Hl7DelimitersTest {

    @Test
    void exposesTheStandardDelimiterSet() {
        assertEquals('|', Hl7Delimiters.standard().field());
        assertEquals('^', Hl7Delimiters.standard().component());
        assertEquals('~', Hl7Delimiters.standard().repetition());
        assertEquals('\\', Hl7Delimiters.standard().escape());
        assertEquals('&', Hl7Delimiters.standard().subcomponent());
    }

    @Test
    void readsDeclaredDelimiters() {
        Hl7Delimiters delimiters = Hl7Delimiters.fromMsh("MSH|^~\\&|SENDER");
        assertEquals(new Hl7Delimiters('|', '^', '~', '\\', '&'), delimiters);

        Hl7Delimiters custom = Hl7Delimiters.fromMsh("MSH*^~\\&*SENDER");
        assertEquals(new Hl7Delimiters('*', '^', '~', '\\', '&'), custom);
    }

    @Test
    void rejectsNullShortOrNonMshLines() {
        assertEquals("INVALID_MSH",
                assertThrows(Hl7ParseException.class, () -> Hl7Delimiters.fromMsh(null)).reason());
        assertEquals("INVALID_MSH",
                assertThrows(Hl7ParseException.class, () -> Hl7Delimiters.fromMsh("MSH|^~")).reason());
        assertEquals("INVALID_MSH",
                assertThrows(Hl7ParseException.class, () -> Hl7Delimiters.fromMsh("PID|^~\\&|"))
                        .reason());
    }
}
