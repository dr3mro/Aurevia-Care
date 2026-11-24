package com.aurevia.tenancy;

import java.util.List;

/**
 * One cursor-paginated page of facilities in the active organization.
 *
 * <p>The {@code nextCursor} is the opaque {@code <code>:<id>} of the last
 * returned row, or {@code null} when this is the final page.</p>
 *
 * @return this record
 */
public record FacilityPage(List<Facility> items, String nextCursor) {

    /**
     * Freeze the page list so callers cannot mutate a facility projection in memory.
     *
     * @throws NullPointerException when items is null
     */
    public FacilityPage {
        items = List.copyOf(items);
    }

    /**
     * Return the immutable facility list in code-ascending order.
     *
     * @return facilities on this page
     */
    @Override
    public List<Facility> items() {
        return List.copyOf(items);
    }
}
