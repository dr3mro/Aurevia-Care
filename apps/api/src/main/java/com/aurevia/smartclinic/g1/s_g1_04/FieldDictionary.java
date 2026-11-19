package com.aurevia.smartclinic.g1.s_g1_04;

import java.util.List;

/**
 * An approved site field dictionary used by CODE template fields (CLN-04).
 *
 * @param key       tenant-local dictionary key
 * @param entries   permitted codes
 * @param approved  whether clinical governance has approved this dictionary
 * @param approvedBy governance approver subject, or {@code null} for a draft
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
