package com.aurevia.chart.record;

/**
 * Where a chart entry's information originated (CLN-03 "track source").
 */
public enum EntrySource {
    /** Reported by the patient and captured by staff. */
    PATIENT_REPORTED,
    /** Observed or entered directly by a clinician. */
    CLINICIAN_OBSERVED,
    /** Imported from an external system or document. */
    EXTERNAL_RECORD,
    /** Captured from a measurement device. */
    DEVICE
}
