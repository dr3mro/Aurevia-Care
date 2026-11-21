package com.aurevia.smartclinic.exchange.conformance;

/**
 * Authorization and reason attached to a replay of a synthetic partner event (ARC-05).
 *
 * <p>A replay preserves the immutable event identity. The request carries the
 * event it is allowed to replay, the reason the replay is needed and the actor
 * that authorized it, so a replay can never be anonymous or silent.</p>
 *
 * @param eventId      immutable event identifier the replay is authorized for
 * @param reason       non-blank reason the event is replayed
 * @param authorizedBy non-blank synthetic actor that authorized the replay
 * @return this record
 */
public record ReplayRequest(String eventId, String reason, String authorizedBy) {

    /**
     * Reject a missing or blank authorization field.
     */
    public ReplayRequest {
        requireText(eventId, "eventId");
        requireText(reason, "reason");
        requireText(authorizedBy, "authorizedBy");
    }

    /**
     * Whether this request is scoped to the given event's immutable identifier.
     *
     * @param event event being replayed
     * @return {@code true} when the identifiers are equal
     */
    public boolean matches(PartnerEvent event) {
        return eventId.equals(event.eventId());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for a replay");
        }
    }
}
