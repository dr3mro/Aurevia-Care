package com.aurevia.catalog.dictionary;

import java.util.List;

/**
 * Serializable snapshot of one stored dictionary or catalogue version.
 *
 * <p>This is the durable value written into the tenant configuration entry: the
 * author is kept with the entries so an approver can be checked against the author
 * even after a restart. The entry list is defensively copied so a caller cannot
 * mutate a stored snapshot in memory.</p>
 *
 * @param author  defining actor recorded with the version
 * @param entries version entries
 * @param <T>     entry value type
 * @return this record
 */
public record StoredVersion<T>(String author, List<T> entries) {

    /**
     * Validate the author and freeze the entries.
     *
     * @throws IllegalArgumentException when the author is missing
     */
    public StoredVersion {
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("author is required");
        }
        entries = List.copyOf(entries);
    }

    /**
     * Return an immutable copy of the entries.
     *
     * @return the stored entries
     */
    @Override
    public List<T> entries() {
        return List.copyOf(entries);
    }
}
