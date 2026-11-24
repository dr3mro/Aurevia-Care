package com.aurevia.configuration;

import java.util.List;

/**
 * Immutable version history of one configuration key in the active organization.
 *
 * @return this record
 */
public record ConfigHistory(String key, List<ConfigEntry> entries) {

    /**
     * Freeze the history list so callers cannot mutate an append-only trail in memory.
     *
     * @throws NullPointerException when entries is null
     */
    public ConfigHistory {
        entries = List.copyOf(entries);
    }

    /**
     * Return the immutable version list.
     *
     * @return versions in ascending version order
     */
    @Override
    public List<ConfigEntry> entries() {
        return List.copyOf(entries);
    }
}
