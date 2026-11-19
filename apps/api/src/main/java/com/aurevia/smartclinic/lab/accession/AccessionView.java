package com.aurevia.smartclinic.lab.accession;

import java.util.List;

/**
 * Current accession together with its immutable history (LAB-01, ARC-03).
 *
 * @param accession current projection
 * @param events    append-only history in occurrence order
 */
public record AccessionView(LabAccession accession, List<AccessionEvent> events) {

    /**
     * Copy the supplied history so the view cannot be mutated through its input.
     *
     * @param accession current projection
     * @param events    append-only history
     */
    public AccessionView {
        events = List.copyOf(events);
    }

    /**
     * Return an unmodifiable snapshot of the history.
     *
     * @return the immutable event list
     */
    @Override
    public List<AccessionEvent> events() {
        return List.copyOf(events);
    }
}
