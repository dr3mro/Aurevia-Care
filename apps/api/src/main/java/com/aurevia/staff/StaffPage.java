package com.aurevia.staff;

import java.util.List;

/**
 * One cursor-paginated page of staff members in the active organization.
 *
 * <p>The {@code nextCursor} is the opaque {@code <subject>:<id>} of the last
 * returned row, or {@code null} when this is the final page.</p>
 *
 * @return this record
 */
public record StaffPage(List<StaffMember> items, String nextCursor) {

    /**
     * Freeze the page list so callers cannot mutate a staff projection in memory.
     *
     * @throws NullPointerException when items is null
     */
    public StaffPage {
        items = List.copyOf(items);
    }

    /**
     * Return the immutable member list in subject-ascending order.
     *
     * @return members on this page
     */
    @Override
    public List<StaffMember> items() {
        return List.copyOf(items);
    }
}
