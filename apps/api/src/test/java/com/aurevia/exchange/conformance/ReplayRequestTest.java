package com.aurevia.exchange.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for the authorized replay request that ARC-05 requires for every replay.
 * All identifiers are synthetic.
 */
class ReplayRequestTest {

    private static final PartnerEvent EVENT = PartnerEvent.of("rec-1", EventKind.RESULT,
            "order-recovered", 1L, "ACC-1001", "7.1 mg/dL");

    @Test
    void rejectsNullFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest(null, "partner-lab", EventKind.RESULT,
                        "outage cleared", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", null, EventKind.RESULT,
                        "outage cleared", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", "partner-lab", null,
                        "outage cleared", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", "partner-lab", EventKind.RESULT,
                        null, "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", "partner-lab", EventKind.RESULT,
                        "outage cleared", null));
    }

    @Test
    void rejectsBlankFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest(" ", "partner-lab", EventKind.RESULT,
                        "outage cleared", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", " ", EventKind.RESULT,
                        "outage cleared", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", "partner-lab", EventKind.RESULT,
                        " ", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", "partner-lab", EventKind.RESULT,
                        "outage cleared", " "));
    }

    @Test
    void keepsReasonAndAuthorization() {
        ReplayRequest request = new ReplayRequest("rec-1", "partner-lab", EventKind.RESULT,
                "partner outage cleared", "operator-synthetic");
        assertEquals("rec-1", request.eventId());
        assertEquals("partner-lab", request.sender());
        assertEquals(EventKind.RESULT, request.kind());
        assertEquals("partner outage cleared", request.reason());
        assertEquals("operator-synthetic", request.authorizedBy());
    }

    @Test
    void authorizesOnlyTheMatchingSenderTypeAndIdentifier() {
        ReplayRequest request = new ReplayRequest("rec-1", "partner-lab", EventKind.RESULT,
                "partner outage cleared", "operator-synthetic");
        assertTrue(request.matches(EVENT));
        assertFalse(new ReplayRequest("other", "partner-lab", EventKind.RESULT,
                "r", "operator-synthetic").matches(EVENT));
        assertFalse(new ReplayRequest("rec-1", "other-partner", EventKind.RESULT,
                "r", "operator-synthetic").matches(EVENT));
        assertFalse(new ReplayRequest("rec-1", "partner-lab", EventKind.CORRECTION,
                "r", "operator-synthetic").matches(EVENT));
    }
}
