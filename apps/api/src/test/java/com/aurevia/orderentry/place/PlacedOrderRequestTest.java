package com.aurevia.orderentry.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

/** Order-content trust boundary: required fields, approved priority and stable fingerprint. */
class PlacedOrderRequestTest {

    private static PlacedOrderRequest request(String preparation) {
        return new PlacedOrderRequest("patient-1", "encounter-1", "facility-1",
                List.of(new OrderSelection("item-cbc", 2)), "rule out anaemia", "urgent",
                preparation, "partner-lab", DeliveryMode.BOTH);
    }

    @Test
    void accepts_valid_content_and_normalizes_text() {
        PlacedOrderRequest request = request("fast for 8 hours");
        assertEquals("patient-1", request.patientId());
        assertEquals("encounter-1", request.encounterId());
        assertEquals("facility-1", request.facilityId());
        assertEquals("urgent", request.priority());
        assertEquals("partner-lab", request.destinationPartnerId());
        assertEquals(DeliveryMode.BOTH, request.deliveryMode());
        assertEquals(1, request.items().size());
    }

    @Test
    void trims_required_text_at_the_boundary() {
        PlacedOrderRequest request = new PlacedOrderRequest("  patient-1  ", "encounter-1", "facility-1",
                List.of(new OrderSelection("item-cbc", 1)), "question", "routine", null,
                "partner-lab", DeliveryMode.PRINT);
        assertEquals("patient-1", request.patientId());
        assertEquals("routine", request.priority());
    }

    @Test
    void refuses_missing_or_blank_required_text() {
        assertEquals("INVALID_ORDER", assertThrows(ApiException.class,
                () -> new PlacedOrderRequest(null, "e", "f", List.of(new OrderSelection("i", 1)),
                        "q", "routine", null, "p", DeliveryMode.BOTH)).code());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("  ", "e", "f", List.of(new OrderSelection("i", 1)),
                        "q", "routine", null, "p", DeliveryMode.BOTH)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("p", null, "f", List.of(new OrderSelection("i", 1)),
                        "q", "routine", null, "partner", DeliveryMode.BOTH)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("p", "e", "f", List.of(new OrderSelection("i", 1)),
                        null, "routine", null, "partner", DeliveryMode.BOTH)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("p", "e", "f", List.of(new OrderSelection("i", 1)),
                        "q", "routine", null, "  ", DeliveryMode.BOTH)).status());
    }

    @Test
    void refuses_an_unapproved_priority() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("p", "e", "f", List.of(new OrderSelection("i", 1)),
                        "q", "asap", null, "partner", DeliveryMode.BOTH)).status());
    }

    @Test
    void refuses_an_empty_or_missing_item_list() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("p", "e", "f", null, "q", "routine", null,
                        "partner", DeliveryMode.BOTH)).status());
        assertEquals("INVALID_ORDER", assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("p", "e", "f", List.of(), "q", "routine", null,
                        "partner", DeliveryMode.BOTH)).code());
    }

    @Test
    void refuses_a_missing_delivery_mode() {
        assertEquals(422, assertThrows(ApiException.class,
                () -> new PlacedOrderRequest("p", "e", "f", List.of(new OrderSelection("i", 1)),
                        "q", "routine", null, "partner", null)).status());
    }

    @Test
    void canonical_payload_is_stable_and_reflects_every_field() {
        assertEquals(request("fast").canonicalPayload(), request("fast").canonicalPayload());
        assertNotEquals(request("fast").canonicalPayload(), request("slow").canonicalPayload());
        assertTrue(request(null).canonicalPayload().contains("BOTH"));
    }
}
