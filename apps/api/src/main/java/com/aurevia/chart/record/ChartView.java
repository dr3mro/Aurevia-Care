package com.aurevia.chart.record;

import java.util.List;

/**
 * An immutable read model of a patient's longitudinal chart (CLN-03).
 *
 * @param entries chart entries in the active tenant, ordered newest clinical time first
 * @return this record
 */
public record ChartView(List<ChartEntry> entries) {

    /**
     * Copy the caller's list so the view cannot be changed through it.
     *
     * @throws NullPointerException when entries is null
     */
    public ChartView {
        entries = List.copyOf(entries);
    }

    /**
     * Return the immutable chart entries.
     *
     * @return chart entries in the active tenant
     */
    @Override
    public List<ChartEntry> entries() {
        return List.copyOf(entries);
    }
}
