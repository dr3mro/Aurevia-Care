package com.aurevia.exchange.conformance;

/**
 * Authorization and reason attached to a replay of a synthetic partner event.
 *
 * <p>A replay preserves the immutable event identity. Authorization is scoped to
 * the full dedup namespace of sender, event kind and event identifier, so an
 * authorization for one partner's event cannot replay a colliding identifier
 * from another partner or type. The request also carries the reason the replay
 * is needed and the actor that authorized it.</p>
 *
 * @param eventId      immutable event identifier the replay is authorized for
 * @param sender       sender that produced the event
 * @param kind         event kind in whose namespace the replay is authorized
 * @param reason       non-blank reason the event is replayed
 * @param authorizedBy non-blank synthetic actor that authorized the replay
 */
public record ReplayRequest(String eventId, String sender, EventKind kind, String reason,
                            String authorizedBy) {

    /**
     * Reject a missing or blank authorization field.
     */
    public ReplayRequest {
        requireText(eventId, "eventId");
        requireText(sender, "sender");
        if (kind == null) {
            throw new IllegalArgumentException("kind is required for a replay");
        }
        requireText(reason, "reason");
        requireText(authorizedBy, "authorizedBy");
    }

    /**
     * Whether this request authorizes the given event in the dedup namespace.
     *
     * @param event event being replayed
     * @return {@code true} when sender, kind and identifier all match
     */
    public boolean matches(PartnerEvent event) {
        return eventId.equals(event.eventId())
                && sender.equals(event.sender())
                && kind == event.kind();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required for a replay");
        }
    }
}
