package com.aurevia.clinical.templates;

import java.util.List;

/**
 * An approved site field dictionary used by CODE template fields (CLN-04).
 *
 * @return this record
 */
public record FieldDictionary(String key, List<String> entries, boolean approved, String approvedBy) {

    /**
     * Freeze the entry set so an approved dictionary cannot change.
     *
     * @throws NullPointerException when entries is null
     */
    public FieldDictionary {
        entries = List.copyOf(entries);
    }

    /**
     * Return the immutable permitted codes.
     *
     * @return dictionary entries
     */
    @Override
    public List<String> entries() {
        return List.copyOf(entries);
    }
}
