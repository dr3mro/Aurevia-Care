package com.aurevia.exchange.hl7;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Conversion between HL7 v2 {@code yyyyMMddHHmmss} timestamps and ISO-8601
 * instants used by the internal commands.
 */
public final class Hl7Timestamp {

    /** Pattern of an HL7 v2 timestamp without a UTC offset. */
    private static final DateTimeFormatter PATTERN = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Hl7Timestamp() {
    }

    /**
     * Format an instant as an HL7 v2 UTC timestamp.
     *
     * @param instant instant to format
     * @return timestamp in {@code yyyyMMddHHmmss} form
     */
    public static String format(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(PATTERN);
    }

    /**
     * Parse an HL7 v2 timestamp as a UTC instant.
     *
     * @param value timestamp in {@code yyyyMMddHHmmss} form
     * @return matching instant
     * @throws Hl7ParseException when the value is missing or malformed
     */
    public static Instant parse(String value) {
        if (value == null || value.isBlank()) {
            throw new Hl7ParseException("MISSING_TIMESTAMP", "message timestamp is missing");
        }
        try {
            return LocalDateTime.parse(value, PATTERN).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw new Hl7ParseException("INVALID_TIMESTAMP", "message timestamp is malformed");
        }
    }
}
