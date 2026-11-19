package com.aurevia.smartclinic.audit;

import java.util.List;

/**
 * One cursor-paginated page of audit events.
 *
 * @param events     events ordered by descending per-tenant sequence
 * @param nextCursor opaque cursor for the next page, or {@code null} at the end
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
