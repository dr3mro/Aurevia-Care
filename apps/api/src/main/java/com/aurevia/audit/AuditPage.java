package com.aurevia.audit;

import java.util.List;

/**
 * One cursor-paginated page of audit events.
 *
 * @return this record
 */
public record AuditPage(List<AuditEvent> events, String nextCursor) {

    /**
     * Freeze the page list so callers cannot mutate an audit projection in memory.
     *
     * @throws NullPointerException when events is null
     */
    public AuditPage {
        events = List.copyOf(events);
    }

    /**
     * Return the immutable event list.
     *
     * @return events in descending sequence order
     */
    @Override
    public List<AuditEvent> events() {
        return List.copyOf(events);
    }
}
