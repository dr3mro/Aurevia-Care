package com.aurevia.exchange.hl7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Field, repetition and component access on a parsed segment. */
class Hl7SegmentTest {

    private final Hl7Segment segment = new Hl7Segment("OBX", List.of(
            List.of("OBX"), List.of(""), List.of("GLUC^Glucose"), List.of("7.1", "7.3"),
            List.of()));

    @Test
    void freezesTheNestedRepetitionLists() {
        List<List<String>> mutable = new java.util.ArrayList<>();
        mutable.add(new java.util.ArrayList<>(List.of("OBX")));
        Hl7Segment copy = new Hl7Segment("OBX", mutable);
        mutable.get(0).add("injected");

        assertEquals(List.of(List.of("OBX")), copy.repetitions());
        assertThrows(UnsupportedOperationException.class, () -> copy.repetitions().add(List.of("x")));
    }

    @Test
    void readsTheFirstRepetitionAndReturnsEmptyOutOfRange() {
        assertEquals("OBX", segment.field(1));
        assertEquals("", segment.field(2));
        assertEquals("GLUC^Glucose", segment.field(3));
        assertEquals("7.1", segment.field(4));
        assertEquals("", segment.field(5));
        assertEquals("", segment.field(0));
        assertEquals("", segment.field(99));
    }

    @Test
    void readsAllRepetitionsAndReturnsEmptyOutOfRange() {
        assertEquals(List.of("7.1", "7.3"), segment.repetitionValues(4));
        assertEquals(List.of(), segment.repetitionValues(0));
        assertEquals(List.of(), segment.repetitionValues(99));
    }

    @Test
    void readsComponentsAndReturnsEmptyWhenAbsent() {
        assertEquals("GLUC", segment.component(3, 1, '^'));
        assertEquals("Glucose", segment.component(3, 2, '^'));
        assertEquals("", segment.component(3, 3, '^'));
        assertEquals("", segment.component(3, 0, '^'));
        assertEquals("", segment.component(2, 1, '^'));
    }
}
