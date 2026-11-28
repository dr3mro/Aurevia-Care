package com.aurevia.staffmsg.realtime;

import java.util.List;

/**
 * Bounded polling result for the realtime event stream.
 *
 * <p>The object shape is intentional: a client never receives a bare array, so
 * it can advance its cursor without re-reading unrelated pages. {@code nextCursor}
 * is the hub's high-water sequence, not the largest returned event, so a page
 * whose events are all invisible to the caller still advances. A cursor above
 * the high-water mark (a client that outlived the process) yields an empty page
 * with a lowered cursor, which tells the client to resynchronise.</p>
 *
 * @param events     matching identifier-only events in sequence order
 * @param nextCursor high-water sequence to send as the next cursor
 * @return the immutable page
 */
public record EventPage(List<RealtimeEvent> events, long nextCursor) {

    /**
     * Freeze the supplied list so a caller cannot mutate the page after it is built.
     */
    public EventPage {
        events = List.copyOf(events);
    }

    /**
     * Return the immutable event list.
     *
     * @return the page events
     */
    @Override
    public List<RealtimeEvent> events() {
        return List.copyOf(events);
    }
}
