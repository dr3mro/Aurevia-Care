package com.aurevia.smartclinic.g1.s_g1_03;

/**
 * The four longitudinal chart families captured by this CLN-03 slice.
 *
 * <p>Every family shares the same provenance contract (source, clinical
 * assertion, verification, effective dates and separated authors). Only
 * {@link com.aurevia.smartclinic.g1.s_g1_03.EntryType#VITAL} requires a value, unit and measurement time.</p>
 */
public enum EntryType {
    /** A problem or diagnosis, optionally mapped to a versioned market catalog. */
    PROBLEM,
    /** An allergy, including an explicit {@link com.aurevia.smartclinic.g1.s_g1_03.PresenceState#ABSENT} assertion. */
    ALLERGY,
    /** A current or historical medication. */
    MEDICATION,
    /** A measured observation with a value, unit and measurement time. */
    VITAL
}
