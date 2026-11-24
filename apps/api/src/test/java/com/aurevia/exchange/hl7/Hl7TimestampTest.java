package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Conversion between HL7 v2 and ISO-8601 timestamps. */
class Hl7TimestampTest {

    @Test
    void roundTripsAUtcInstant() {
        Instant instant = Instant.parse("2026-11-21T12:34:56Z");
        assertEquals("20261121123456", Hl7Timestamp.format(instant));
        assertEquals(instant, Hl7Timestamp.parse("20261121123456"));
    }

    @Test
    void rejectsMissingTimestamp() {
        assertEquals("MISSING_TIMESTAMP",
                assertThrows(Hl7ParseException.class, () -> Hl7Timestamp.parse(null)).reason());
        assertEquals("MISSING_TIMESTAMP",
                assertThrows(Hl7ParseException.class, () -> Hl7Timestamp.parse("  ")).reason());
    }

    @Test
    void rejectsMalformedTimestamp() {
        assertEquals("INVALID_TIMESTAMP",
                assertThrows(Hl7ParseException.class, () -> Hl7Timestamp.parse("not-a-date"))
                        .reason());
    }
}
