package com.aurevia.orderentry.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Wire forms and value semantics of the delivery kernel value types (XCH-02). */
class DeliveryTypesTest {

    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void delivery_event_kinds_keep_distinct_identities_and_wire_forms() {
        assertEquals(10, DeliveryEventKind.values().length);
        for (DeliveryEventKind kind : DeliveryEventKind.values()) {
            assertEquals(kind.name().toLowerCase(Locale.ROOT), kind.wire());
            assertEquals(kind, DeliveryEventKind.from(kind.wire()));
            assertEquals(kind, DeliveryEventKind.valueOf(kind.name()));
        }
        assertThrows(IllegalArgumentException.class, () -> DeliveryEventKind.from("not-a-kind"));
    }

    @Test
    void delivery_status_exposes_every_state() {
        assertEquals(10, DeliveryStatus.values().length);
        for (DeliveryStatus status : DeliveryStatus.values()) {
            assertEquals(status.name().toLowerCase(Locale.ROOT), status.wire());
            assertEquals(status, DeliveryStatus.valueOf(status.name()));
        }
    }

    @Test
    void delivery_route_exposes_every_channel() {
        assertEquals(3, DeliveryRoute.values().length);
        for (DeliveryRoute route : DeliveryRoute.values()) {
            assertEquals(route.name().toLowerCase(Locale.ROOT), route.wire());
            assertEquals(route, DeliveryRoute.valueOf(route.name()));
        }
    }

    @Test
    void send_outcome_is_three_valued() {
        assertEquals(3, SendOutcome.values().length);
        for (SendOutcome outcome : SendOutcome.values()) {
            assertEquals(outcome.name().toLowerCase(Locale.ROOT), outcome.wire());
            assertEquals(outcome, SendOutcome.valueOf(outcome.name()));
        }
    }

    @Test
    void delivery_event_exposes_every_part_and_value_semantics() {
        DeliveryEvent event = new DeliveryEvent("event-1", "org", "order", 1,
                DeliveryEventKind.SUBMITTED, 2, "actor", "detail", "verifier", AT, AT);
        DeliveryEvent copy = new DeliveryEvent("event-1", "org", "order", 1,
                DeliveryEventKind.SUBMITTED, 2, "actor", "detail", "verifier", AT, AT);

        assertEquals("event-1", event.id());
        assertEquals("org", event.organizationId());
        assertEquals("order", event.orderId());
        assertEquals(1, event.orderVersion());
        assertEquals(DeliveryEventKind.SUBMITTED, event.kind());
        assertEquals(2, event.attempt());
        assertEquals("actor", event.actor());
        assertEquals("detail", event.detail());
        assertEquals("verifier", event.verifiedBy());
        assertEquals(AT, event.verifiedAt());
        assertEquals(AT, event.occurredAt());
        assertEquals(event, copy);
        assertEquals(event.hashCode(), copy.hashCode());
        assertEquals(event.toString(), copy.toString());
    }

    @Test
    void order_delivery_exposes_every_part_and_value_semantics() {
        OrderDelivery delivery = new OrderDelivery("org", "order", DeliveryRoute.ELECTRONIC,
                DeliveryStatus.DELIVERED, "submission-1", 2, 1, AT);
        OrderDelivery copy = new OrderDelivery("org", "order", DeliveryRoute.ELECTRONIC,
                DeliveryStatus.DELIVERED, "submission-1", 2, 1, AT);

        assertEquals("org", delivery.organizationId());
        assertEquals("order", delivery.orderId());
        assertEquals(DeliveryRoute.ELECTRONIC, delivery.route());
        assertEquals(DeliveryStatus.DELIVERED, delivery.status());
        assertEquals("submission-1", delivery.submissionId());
        assertEquals(2, delivery.attempts());
        assertEquals(1, delivery.orderVersion());
        assertEquals(AT, delivery.updatedAt());
        assertEquals(delivery, copy);
        assertEquals(delivery.hashCode(), copy.hashCode());
        assertEquals(delivery.toString(), copy.toString());
    }

    @Test
    void send_request_carries_only_opaque_delivery_metadata() {
        SendRequest request = new SendRequest("org", "order", "submission-1", "partner-lab", "digest-abc");
        SendRequest copy = new SendRequest("org", "order", "submission-1", "partner-lab", "digest-abc");

        assertEquals("org", request.organizationId());
        assertEquals("order", request.orderId());
        assertEquals("submission-1", request.submissionId());
        assertEquals("partner-lab", request.partnerCode());
        assertEquals("digest-abc", request.payloadDigest());
        assertEquals(request, copy);
        assertEquals(request.hashCode(), copy.hashCode());
        assertEquals(request.toString(), copy.toString());
    }

    @Test
    void manual_delivery_carries_delivery_and_verification_evidence() {
        ManualDelivery evidence = new ManualDelivery("nurse-7", AT, "supervisor-9", AT, "receipt-42", "CARE");
        ManualDelivery copy = new ManualDelivery("nurse-7", AT, "supervisor-9", AT, "receipt-42", "CARE");

        assertEquals("nurse-7", evidence.deliveredBy());
        assertEquals(AT, evidence.deliveredAt());
        assertEquals("supervisor-9", evidence.verifiedBy());
        assertEquals(AT, evidence.verifiedAt());
        assertEquals("receipt-42", evidence.verificationArtifact());
        assertEquals("CARE", evidence.purpose());
        assertEquals(evidence, copy);
        assertEquals(evidence.hashCode(), copy.hashCode());
        assertEquals(evidence.toString(), copy.toString());
    }

    @Test
    void partner_channel_is_a_single_method_contract() {
        PartnerChannel channel = request -> SendOutcome.DELIVERED;
        assertTrue(channel.send(new SendRequest("org", "order", "sub-1", "partner", "digest"))
                == SendOutcome.DELIVERED);
        assertEquals(1, Set.of(channel).size());
    }
}
