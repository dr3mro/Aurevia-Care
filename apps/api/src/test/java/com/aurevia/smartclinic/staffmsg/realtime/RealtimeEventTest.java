package com.aurevia.smartclinic.staffmsg.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * Wire-shape acceptance for realtime events: identifier-only payloads and a
 * defensive page projection. The text sentinel is synthetic.
 */
class RealtimeEventTest {

    private static final String CONVERSATION = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String SENTINEL_TEXT = "SYNTHETIC_SENTINEL_NO_PATIENT_TEXT_9f3a";

    @Test
    void eventSerializesIdentifiersOnlyAndNeverMessageText() throws Exception {
        RealtimeEvent event = new RealtimeEvent("message-1", 7L, RealtimeEventType.MESSAGE,
                CONVERSATION, "nurse-b");
        String json = new ObjectMapper().writeValueAsString(event);

        assertTrue(json.contains("\"id\":\"message-1\""));
        assertTrue(json.contains("\"sequence\":7"));
        assertTrue(json.contains("\"type\":\"message\""));
        assertTrue(json.contains("\"conversationId\":\"" + CONVERSATION + "\""));
        assertTrue(json.contains("\"subject\":\"nurse-b\""));
        assertFalse(json.contains("body"), "the event must have no body field");
        assertFalse(json.contains("senderSubject"));
        assertFalse(json.contains("text"));
        assertFalse(json.contains(SENTINEL_TEXT), "the event must never carry message content");
    }

    @Test
    void eventTypesCarryStableWireValuesAndRoundTrip() {
        assertEquals("message", RealtimeEventType.MESSAGE.wire());
        assertEquals("read", RealtimeEventType.READ.wire());
        assertArrayEqualsAll(RealtimeEventType.values());
        assertEquals(RealtimeEventType.READ, RealtimeEventType.valueOf("READ"));
    }

    private static void assertArrayEqualsAll(RealtimeEventType[] values) {
        assertEquals(2, values.length);
        assertTrue(List.of(values).contains(RealtimeEventType.MESSAGE));
        assertTrue(List.of(values).contains(RealtimeEventType.READ));
    }

    @Test
    void eventPageFreezesTheEventListAgainstCallerMutation() {
        RealtimeEvent event = new RealtimeEvent("message-1", 1L, RealtimeEventType.MESSAGE, CONVERSATION, "nurse-b");
        List<RealtimeEvent> source = new ArrayList<>();
        source.add(event);
        EventPage page = new EventPage(source, 11L);

        source.clear();
        assertEquals(1, page.events().size(), "the page must keep its own copy");
        assertThrows(UnsupportedOperationException.class, () -> page.events().add(event));
        assertEquals(11L, page.nextCursor());
    }
}
