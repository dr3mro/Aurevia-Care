package com.aurevia.smartclinic.patient.chart;

/**
 * The clinical assertion recorded by a chart entry, independent of whether it
 * has been verified (CLN-03 "explicit unknown and none states").
 *
 * <p>{@link com.aurevia.smartclinic.patient.chart.PresenceState#ABSENT} means the
 * clinician explicitly asserts there is none (for an allergy, "no known
 * allergies"); {@link com.aurevia.smartclinic.patient.chart.PresenceState#UNKNOWN}
 * means it was considered but could not be determined. Both are distinct from
 * "nothing recorded".</p>
 */
public enum PresenceState {
    /** The finding is asserted to be present. */
    PRESENT,
    /** The finding is explicitly asserted to be absent. */
    ABSENT,
    /** The finding was considered but could not be determined. */
    UNKNOWN
}
