package com.aurevia.smartclinic.exchange.fhir;

import java.math.BigDecimal;
import java.util.List;

/**
 * Immutable FHIR-boundary projections of the exchange and laboratory models (XCH-01, XCH-04, LAB-01, LAB-02).
 *
 * <p>These records name exactly the fields that cross the FHIR R4 boundary. They are not
 * replacements for the domain records in {@code exchange.order}, {@code lab.accession} and
 * {@code lab.result}: each mapper builds one from a domain record and reads one back from a
 * FHIR resource. Keeping the boundary shape explicit lets a partner-specific profile extend
 * the mapping without changing the domain aggregates. {@code profile} carries the partner
 * profile name on every projection so the name is never guessed from the resource content.</p>
 */
public final class FhirModel {

    private FhirModel() {
    }

    /**
     * The order fields carried by a FHIR ServiceRequest.
     *
     * @param profile          partner profile name preserved in the mapping
     * @param patientId        opaque local patient identifier (required)
     * @param orderId          opaque order identifier (required)
     * @param encounterRef     local encounter reference
     * @param orderCode        local order code
     * @param priority         released priority
     * @param clinicalQuestion released clinical question
     * @param requester        responsible clinician
     * @param authorizationId  authorization that permitted the disclosure
     * @return this record
     */
    public record ServiceRequestData(String profile, String patientId, String orderId, String encounterRef,
                                     String orderCode, String priority, String clinicalQuestion,
                                     String requester, String authorizationId) {
    }

    /**
     * The accession fields carried by a FHIR Specimen.
     *
     * @param profile        partner profile name preserved in the mapping
     * @param patientId      verified local patient identifier (required)
     * @param accessionId    opaque specimen identifier
     * @param accessionNo    issuer-scoped accession number (required)
     * @param accessionIssuer issuer of the accession number
     * @param orderId        source order identifier (required)
     * @param specimenType   specimen type code
     * @param siteCode       collection site code
     * @param collector      recorded collector
     * @param collectedAt    collection instant
     * @param receivedAt     receipt instant
     * @param quality        receipt quality code
     * @param status         specimen status code
     * @return this record
     */
    public record SpecimenData(String profile, String patientId, String accessionId, String accessionNo,
                               String accessionIssuer, String orderId, String specimenType, String siteCode,
                               String collector, String collectedAt, String receivedAt, String quality,
                               String status) {
    }

    /**
     * One approved reference range attached to an observation (LAB-02).
     *
     * @param low  inclusive lower bound
     * @param high exclusive upper bound
     * @param unit measurement unit of both bounds
     * @return this record
     */
    public record FhirReferenceRange(BigDecimal low, BigDecimal high, String unit) {
    }

    /**
     * The observation fields carried by a FHIR Observation.
     *
     * <p>{@code kind}, {@code presence}, {@code comparator} and {@code abnormalFlag} are the
     * stable wire forms of the laboratory vocabularies, so a less-than qualifier, its unit
     * and its reference range survive a round trip verbatim.</p>
     *
     * @param id             observation identifier used by a report's result reference
     * @param profile        partner profile name preserved in the mapping
     * @param patientId      verified local patient identifier (required)
     * @param specimenId     specimen identifier carrying the accession (required)
     * @param code           observation code
     * @param status         FHIR observation status
     * @param kind           one of {@code numeric}, {@code qualified_numeric}, {@code text}, {@code coded}
     * @param presence       one of {@code present}, {@code unknown}, {@code absent}
     * @param value          numeric measurement, when present
     * @param comparator     FHIR comparator such as {@code <}, empty when exact
     * @param unit           measurement unit of a numeric value
     * @param textValue      value of a text observation
     * @param codedValue     value of a coded observation
     * @param method         method when supplied
     * @param abnormalFlag   interpretation flag wire form
     * @param instrument     instrument or source
     * @param verifier       verifier of the observation
     * @param referenceRange approved reference range when supplied
     * @return this record
     */
    public record ObservationData(String id, String profile, String patientId, String specimenId, String code,
                                  String status, String kind, String presence, BigDecimal value,
                                  String comparator, String unit, String textValue, String codedValue,
                                  String method, String abnormalFlag, String instrument, String verifier,
                                  FhirReferenceRange referenceRange) {
    }

    /**
     * The report fields carried by a FHIR DiagnosticReport.
     *
     * @param profile           partner profile name preserved in the mapping
     * @param patientId         verified local patient identifier (required)
     * @param reportId          immutable report identifier (required)
     * @param version           revision version
     * @param orderId           source order identifier (required)
     * @param specimenId        specimen identifier carrying the accession (required)
     * @param state             FHIR report status
     * @param observations      contained observations of this revision
     * @param supersedesVersion prior version linked by a corrected report
     * @param effectiveAt       effective instant of the revision
     * @return this record
     */
    public record DiagnosticReportData(String profile, String patientId, String reportId, int version,
                                       String orderId, String specimenId, String state,
                                       List<ObservationData> observations, Integer supersedesVersion,
                                       String effectiveAt) {

        /**
         * Copy the observation list so the projection cannot be mutated through its input.
         */
        public DiagnosticReportData {
            observations = List.copyOf(observations);
        }

        /**
         * Return the immutable contained observations.
         *
         * @return observations in capture order
         */
        @Override
        public List<ObservationData> observations() {
            return List.copyOf(observations);
        }
    }
}
