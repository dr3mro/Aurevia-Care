package com.aurevia.labflow.results;

import java.time.Instant;
import java.util.List;

/**
 * Non-sensitive projection of one report revision at an HTTP boundary (LAB-02).
 *
 * <p>Only identifiers, bounded observation values and interpretation flags are
 * exposed. The owning organization and actor provenance are retained so a
 * projection cannot be moved between tenants without detection.</p>
 *
 * @param reportId          tenant-local report identifier
 * @param version           revision version
 * @param supersedesVersion prior revision, or {@code null} for first capture
 * @param state             wire state: preliminary, final, corrected or entered_in_error
 * @param patientId         owning patient
 * @param specimenId        matched specimen
 * @param orderId           matched source order
 * @param actor             authenticated actor that produced this revision
 * @param verifier          authenticated verifier, or {@code null} when preliminary
 * @param effectiveAt       clinical effective instant
 * @param reason            linked correction or withdrawal reason
 * @param observations      immutable observations in capture order
 * @return this record
 */
public record ReportView(String reportId, int version, Integer supersedesVersion, String state,
                         String patientId, String specimenId, String orderId, String actor,
                         String verifier, Instant effectiveAt, String reason,
                         List<ObservationView> observations) {

    /**
     * Freeze the observation list.
     *
     * @return this record with an immutable observation list
     */
    public ReportView {
        observations = List.copyOf(observations);
    }

    /**
     * Return the immutable observations.
     *
     * @return observations in capture order
     */
    @Override
    public List<ObservationView> observations() {
        return List.copyOf(observations);
    }

    /**
     * Non-sensitive projection of one observation and its derived flag (LAB-02).
     *
     * @param code          test code
     * @param kind          wire observation kind
     * @param presence      wire presence state
     * @param value         measured decimal value, or {@code null}
     * @param qualifier     display symbol such as {@code <}, empty when exact
     * @param unit          source unit
     * @param textValue     text result
     * @param codedValue    coded result
     * @param method        recorded method
     * @param instrument    recorded instrument or source
     * @param abnormalFlag  wire flag: none, low, high or critical
     * @return this observation view
     */
    public record ObservationView(String code, String kind, String presence, String value,
                                  String qualifier, String unit, String textValue, String codedValue,
                                  String method, String instrument, String abnormalFlag) {

        /**
         * Render the observation for a reader without losing a qualifier or unit.
         *
         * @return non-sensitive display form
         */
        public String display() {
            return switch (kind) {
                case "numeric" -> value + " " + unit + " (" + abnormalFlag + ")";
                case "qualified_numeric" -> qualifier + value + " " + unit;
                case "text" -> textValue;
                default -> codedValue;
            };
        }
    }
}
