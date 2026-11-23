package com.aurevia.smartclinic.staffmsg.realtime;

/**
 * Identifier-only notification about a change in a staff conversation (ARC-07).
 *
 * <p>An event is deliberately limited to identifiers and its stream sequence:
 * it never carries message text, a sender display name or any other clinical
 * payload. The {@code sequence} is assigned by {@link com.aurevia.smartclinic.staffmsg.realtime.MessageEventHub}
 * and is the value a client echoes as {@code Last-Event-ID} after a drop; the
 * {@code id} is the stable identifier of the underlying message or read
 * cursor. The subject identifies the acting staff member, never their name.</p>
 *
 * @param id             stable identifier of the changed message or read cursor
 * @param sequence       monotonic stream sequence assigned by the hub
 * @param type           what changed
 * @param conversationId owning conversation identifier
 * @param subject        acting staff subject
 * @return the immutable event
 */
public record RealtimeEvent(String id, long sequence, RealtimeEventType type,
                            String conversationId, String subject) {
}
