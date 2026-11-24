package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Accessors over a parsed message. */
class Hl7MessageTest {

    private final Hl7Segment msh = new Hl7Segment("MSH", List.of(
            List.of("MSH"), List.of("^~\\&"), List.of("APP"), List.of("SENDFAC"),
            List.of("APP"), List.of("RECVFAC"), List.of("20261121120000"), List.of(""),
            List.of("ORU^R01"), List.of("MSG-1")));
    private final Hl7Segment obx = new Hl7Segment("OBX", List.of(List.of("OBX")));

    private Hl7Message message() {
        return new Hl7Message(Hl7Delimiters.standard(), new ArrayList<>(List.of(msh, obx)), "raw");
    }

    @Test
    void freezesTheSegmentList() {
        List<Hl7Segment> mutable = new ArrayList<>(List.of(msh));
        Hl7Message frozen = new Hl7Message(Hl7Delimiters.standard(), mutable, "raw");
        mutable.add(obx);

        assertEquals(1, frozen.segments().size());
        assertThrows(UnsupportedOperationException.class, () -> frozen.segments().add(obx));
    }

    @Test
    void readsMessageTypeAndControlIdFromMsh() {
        assertEquals("ORU^R01", message().messageType());
        assertEquals("MSG-1", message().controlId());
    }

    @Test
    void returnsEmptyHeaderValuesWhenNoMshExists() {
        Hl7Message noMsh = new Hl7Message(Hl7Delimiters.standard(), List.of(obx), "raw");
        assertEquals("", noMsh.messageType());
        assertEquals("", noMsh.controlId());
    }

    @Test
    void findsFirstAndAllSegmentsByType() {
        assertEquals(obx, message().first("OBX").orElseThrow());
        assertTrue(message().first("PID").isEmpty());
        assertEquals(List.of(obx), message().all("OBX"));
        assertEquals(List.of(), message().all("PID"));
    }

    @Test
    void reportsSegmentPresence() {
        assertTrue(message().has("MSH"));
        assertFalse(message().has("ZZZ"));
    }
}
