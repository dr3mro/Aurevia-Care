package com.aurevia.scheduling;

import java.util.List;

/**
 * One cursor-paginated page of appointments in the active organization.
 *
 * <p>The {@code nextCursor} is the opaque {@code <startsAt>:<id>} of the last
 * returned row, or {@code null} when this is the final page. Rows are ordered by
 * scheduled start ascending with the immutable appointment id as tie-break.</p>
 *
 * @return this record
 * @param items value supplied by the caller
 * @param nextCursor value supplied by the caller
 */
public record AppointmentPage(List<AppointmentView> items, String nextCursor) {

    /**
     * Freeze the page list so callers cannot mutate a projection in memory.
     *
     * @throws NullPointerException when items is null
     */
    public AppointmentPage {
        items = List.copyOf(items);
    }

    /**
     * Return the immutable appointment views in scheduled-start order.
     *
     * @return appointments on this page
     */
    @Override
    public List<AppointmentView> items() {
        return List.copyOf(items);
    }
}
