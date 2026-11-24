package com.aurevia.exchange.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Dependency-free parser for the bounded HL7 v2 subset used by order and result
 * exchange (XCH-01, XCH-04).
 *
 * <p>The first non-blank segment declares the delimiters; every segment is then
 * split on the field separator, each field on the repetition separator, and each
 * repetition is decoded. No library and no I/O is involved, so every failure is
 * a deterministic {@link com.aurevia.exchange.hl7.Hl7ParseException} that maps to a negative
 * acknowledgment.</p>
 */
public final class Hl7Parser {

    private Hl7Parser() {
    }

    /**
     * Parse one message, accepting CR, LF or CRLF segment terminators.
     *
     * @param text raw message text
     * @return parsed message
     * @throws Hl7ParseException when the message is empty or its MSH header is invalid
     */
    public static Hl7Message parse(String text) {
        if (text == null || text.isBlank()) {
            throw new Hl7ParseException("EMPTY_MESSAGE", "message text is empty");
        }
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        // The isBlank guard above guarantees at least one non-blank line.
        int firstSegment = 0;
        while (lines[firstSegment].isBlank()) {
            firstSegment++;
        }
        Hl7Delimiters delimiters = Hl7Delimiters.fromMsh(lines[firstSegment]);
        List<Hl7Segment> segments = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            segments.add(parseSegment(line, delimiters));
        }
        return new Hl7Message(delimiters, segments, text);
    }

    /**
     * Parse one non-blank segment line.
     *
     * @param line       segment line
     * @param delimiters delimiters declared by the message
     * @return parsed segment
     * @throws Hl7ParseException when the segment identifier is truncated
     */
    private static Hl7Segment parseSegment(String line, Hl7Delimiters delimiters) {
        if (line.length() < 3) {
            throw new Hl7ParseException("TRUNCATED_SEGMENT", "segment identifier is truncated");
        }
        String[] rawFields = line.split(Pattern.quote(String.valueOf(delimiters.field())), -1);
        List<List<String>> repetitions = new ArrayList<>(rawFields.length);
        for (String rawField : rawFields) {
            String[] rawRepetitions = rawField.split(
                    Pattern.quote(String.valueOf(delimiters.repetition())), -1);
            List<String> values = new ArrayList<>(rawRepetitions.length);
            for (String rawRepetition : rawRepetitions) {
                values.add(Hl7Escape.decode(rawRepetition, delimiters));
            }
            repetitions.add(values);
        }
        return new Hl7Segment(rawFields[0], repetitions);
    }
}
