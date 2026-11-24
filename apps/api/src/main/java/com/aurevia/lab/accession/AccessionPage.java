package com.aurevia.lab.accession;

import java.util.List;

/**
 * One cursor-paginated page of accessions in the active organization (ARC-03).
 *
 * <p>The {@code nextCursor} is the opaque {@code <createdAt>:<id>} of the last
 * returned row, or {@code null} when this is the final page. List rows carry no
 * history: the single-record read keeps ownership of the immutable event stream,
 * so a page stays bounded. The list is frozen so a caller cannot mutate a
 * projection produced inside a protected transaction.</p>
 *
 * @return this record
 */
public record AccessionPage(List<AccessionView> items, String nextCursor) {

    /**
     * Freeze the page items so callers cannot mutate the returned projection.
     *
     * @throws NullPointerException when items is null
     */
    public AccessionPage {
        items = List.copyOf(items);
    }

    /**
     * Return the immutable accession list in creation-time descending order.
     *
     * @return accessions on this page
     */
    @Override
    public List<AccessionView> items() {
        return List.copyOf(items);
    }
}
