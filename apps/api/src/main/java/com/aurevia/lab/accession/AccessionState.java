package com.aurevia.lab.accession;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Lifecycle of one accessioned specimen (LAB-01).
 *
 * <p>The same vocabulary is stored in the database and checked by a guard
 * trigger, so an application transition can never walk the machine backwards or
 * skip a verification stage. A rejected specimen can only spawn one linked
 * recollection; the recollected row is terminal.</p>
 */
public enum AccessionState {

    /** Requisition accessioned; awaiting collection. */
    ACCESSIONED,
    /** Specimen collected and patient/accession matched. */
    COLLECTED,
    /** Specimen received and quality checked. */
    RECEIVED,
    /** Specimen rejected with a retained reason and notification. */
    REJECTED,
    /** Specimen superseded by a linked recollection. */
    RECOLLECTED;

    /**
     * Decide whether this state may move to the target state.
     *
     * @param target requested destination state
     * @return {@code true} only for an approved LAB-01 edge
     */
    public boolean canTransitionTo(AccessionState target) {
        return switch (this) {
            case ACCESSIONED -> target == COLLECTED;
            case COLLECTED -> target == RECEIVED || target == REJECTED;
            case RECEIVED -> target == REJECTED;
            case REJECTED -> target == RECOLLECTED;
            case RECOLLECTED -> false;
        };
    }

    /**
     * Return the stable lowercase value stored in the database and API.
     *
     * @return the wire value of this state
     */
    @JsonValue
    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
