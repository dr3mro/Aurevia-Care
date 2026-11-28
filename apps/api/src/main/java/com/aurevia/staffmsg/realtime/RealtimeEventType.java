package com.aurevia.staffmsg.realtime;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Kind of change carried by an identifier-only realtime event.
 *
 * <p>The wire value is part of the SSE {@code event:} line and of the polling
 * projection, so it is a stable lowercase token rather than the Java constant
 * name. No clinical content is carried by either kind.</p>
 */
public enum RealtimeEventType {
    /** A new message was published in a conversation. */
    MESSAGE("message"),
    /** A participant advanced their read cursor. */
    READ("read");

    private final String wire;

    RealtimeEventType(String wire) {
        this.wire = wire;
    }

    /**
     * Return the stable wire value.
     *
     * @return lowercase event type token
     */
    @JsonValue
    public String wire() {
        return wire;
    }
}
