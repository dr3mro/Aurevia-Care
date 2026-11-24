package com.aurevia.orderentry.place;

import java.util.List;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.order.OrderInputs;

/**
 * The complete, validated content of a draft or placed clinical order (LAB-01, RAD-01, XCH-01).
 *
 * <p>This is the trust boundary for creating or editing an order: every field is
 * validated before any state changes, and the normalized value is stored. The
 * priority vocabulary is reused from the order-exchange domain instead of being
 * redefined. Patient, encounter, facility and partner identifiers are opaque
 * tenant-local references; the active tenant and acting clinician come from the
 * request context and are never accepted from this body.</p>
 *
 * @param patientId             opaque local patient identifier
 * @param encounterId           opaque local encounter identifier
 * @param facilityId            facility whose catalog and ordering privilege apply
 * @param items                 catalog items captured with their versions
 * @param clinicalQuestion      clinical question for the performer
 * @param priority              approved priority: routine, urgent or stat
 * @param preparationInstructions patient preparation text, for example fasting; may be null
 * @param destinationPartnerId  opaque identifier of the performing partner
 * @param deliveryMode          electronic, print or both
 */
public record PlacedOrderRequest(String patientId, String encounterId, String facilityId,
                                 List<OrderSelection> items, String clinicalQuestion, String priority,
                                 String preparationInstructions, String destinationPartnerId,
                                 DeliveryMode deliveryMode) {

    /**
     * Validate and normalize the order content at the trust boundary.
     *
     * @throws ApiException {@code 422} when a required field is missing, the item list is empty,
     *                      the priority is not approved or the delivery mode is absent
     */
    public PlacedOrderRequest {
        patientId = requireText(patientId, "patientId");
        encounterId = requireText(encounterId, "encounterId");
        facilityId = requireText(facilityId, "facilityId");
        clinicalQuestion = requireText(clinicalQuestion, "clinicalQuestion");
        destinationPartnerId = requireText(destinationPartnerId, "destinationPartnerId");
        priority = OrderInputs.priority(priority);
        if (items == null || items.isEmpty()) {
            throw new ApiException(422, "INVALID_ORDER", "at least one catalog item is required");
        }
        items = List.copyOf(items);
        if (deliveryMode == null) {
            throw new ApiException(422, "INVALID_DELIVERY_MODE", "a delivery mode is required");
        }
    }

    /**
     * Return a defensive copy of the captured catalog items.
     *
     * @return an immutable copy of the item list
     */
    public List<OrderSelection> items() {
        return List.copyOf(items);
    }

    /**
     * Produce a stable, unambiguous representation of the order content.
     *
     * <p>Used as the canonical payload when an idempotency key is bound to a
     * placement, so a reused key with different content is detected as a
     * conflict rather than replaying the first order.</p>
     *
     * @return length-prefixed field representation of this order content
     */
    public String canonicalPayload() {
        StringBuilder payload = new StringBuilder();
        append(payload, patientId);
        append(payload, encounterId);
        append(payload, facilityId);
        append(payload, clinicalQuestion);
        append(payload, priority);
        append(payload, preparationInstructions == null ? "" : preparationInstructions);
        append(payload, destinationPartnerId);
        append(payload, deliveryMode.name());
        for (OrderSelection item : items) {
            append(payload, item.itemId());
            append(payload, String.valueOf(item.version()));
        }
        return payload.toString();
    }

    private static void append(StringBuilder payload, String value) {
        payload.append(value.length()).append(':').append(value).append('\u001f');
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_ORDER", field + " is required");
        }
        return value.trim();
    }
}
