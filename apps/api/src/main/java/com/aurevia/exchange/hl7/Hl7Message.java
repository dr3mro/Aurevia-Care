package com.aurevia.exchange.hl7;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One parsed HL7 v2 message with its declared delimiters and raw payload.
 *
 * <p>The raw payload is retained so a deterministic fingerprint of the exact
 * received bytes can be carried into the internal result command. No clinical
 * payload is copied into logs or acknowledgments.</p>
 *
 * @param delimiters delimiters declared by the MSH segment
 * @param segments   parsed segments in wire order
 * @param rawText    exact message text as received
 */
public record Hl7Message(Hl7Delimiters delimiters, List<Hl7Segment> segments, String rawText) {

    /**
     * Freeze the parsed segment list.
     */
    public Hl7Message {
        segments = List.copyOf(segments);
    }

    /**
     * Return an immutable view of the parsed segments.
     *
     * @return segments in wire order
     */
    @Override
    public List<Hl7Segment> segments() {
        return List.copyOf(segments);
    }

    /**
     * Return the raw message type field (MSH-9), for example {@code ORM^O01}.
     *
     * @return message type, or an empty string when the message has no MSH
     */
    public String messageType() {
        return first("MSH").map(segment -> segment.field(9)).orElse("");
    }

    /**
     * Return the message control identifier (MSH-10) used for duplicate detection.
     *
     * @return control identifier, or an empty string when absent
     */
    public String controlId() {
        return first("MSH").map(segment -> segment.field(10)).orElse("");
    }

    /**
     * Return the first segment of a given type.
     *
     * @param type three-character segment identifier
     * @return the first matching segment, or empty when none exists
     */
    public Optional<Hl7Segment> first(String type) {
        for (Hl7Segment segment : segments) {
            if (segment.type().equals(type)) {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }

    /**
     * Return every segment of a given type in wire order.
     *
     * @param type three-character segment identifier
     * @return immutable list of matching segments
     */
    public List<Hl7Segment> all(String type) {
        List<Hl7Segment> found = new ArrayList<>();
        for (Hl7Segment segment : segments) {
            if (segment.type().equals(type)) {
                found.add(segment);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Whether the message contains a segment of the given type.
     *
     * @param type three-character segment identifier
     * @return true when at least one segment matches
     */
    public boolean has(String type) {
        return first(type).isPresent();
    }
}
