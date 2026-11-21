package com.aurevia.smartclinic.exchange.conformance;

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

    @Test
    void rejectsNullFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest(null, "outage cleared", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", null, "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", "outage cleared", null));
    }

    @Test
    void rejectsBlankFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest(" ", "outage cleared", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", " ", "operator-synthetic"));
        assertThrows(IllegalArgumentException.class,
                () -> new ReplayRequest("e1", "outage cleared", " "));
    }

    @Test
    void keepsReasonAndAuthorizationAndMatchesEventIdentity() {
        ReplayRequest request = new ReplayRequest("rec-1", "partner outage cleared",
                "operator-synthetic");
        assertEquals("rec-1", request.eventId());
        assertEquals("partner outage cleared", request.reason());
        assertEquals("operator-synthetic", request.authorizedBy());
        PartnerEvent matching = PartnerEvent.of("rec-1", EventKind.RESULT, "order-recovered", 1L,
                "ACC-1001", "7.1 mg/dL");
        PartnerEvent other = PartnerEvent.of("other", EventKind.RESULT, "order-recovered", 1L,
                "ACC-1001", "7.1 mg/dL");
        assertTrue(request.matches(matching));
        assertFalse(request.matches(other));
    }
}
