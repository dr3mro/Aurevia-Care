package com.aurevia.exchange.fhir;

import java.math.BigDecimal;
import java.util.List;

import com.aurevia.api.ApiException;

/**
 * Immutable FHIR-boundary projections of the exchange, laboratory, imaging, document and prescription models.
 *
 * <p>These records name exactly the fields that cross the FHIR R4 boundary. They are not
 * replacements for the domain records in {@code exchange.order}, {@code lab.accession},
 * {@code lab.result}, {@code radiology}, {@code documents} and {@code prescription}: each mapper
 * builds one from a domain record and reads one back from a FHIR resource. Keeping the boundary
 * shape explicit lets a partner-specific profile extend the mapping without changing the domain
 * aggregates. {@code profile} carries the partner profile name on every projection so the name
 * is never guessed from the resource content.</p>
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
         */
    public record SpecimenData(String profile, String patientId, String accessionId, String accessionNo,
                               String accessionIssuer, String orderId, String specimenType, String siteCode,
                               String collector, String collectedAt, String receivedAt, String quality,
                               String status) {
    }

    /**
     * One approved reference range attached to an observation.
     *
     * @param low  inclusive lower bound
     * @param high exclusive upper bound
     * @param unit measurement unit of both bounds
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

    /**
     * The imaging study fields carried by a FHIR ImagingStudy.
     *
     * <p>The Study Instance UID and accession are carried as typed identifiers and the
     * modality as a DICOM coding. A study is only linkable after its patient and accession
     * labels have been checked against the owning order by
     * {@link com.aurevia.exchange.fhir.ImagingStudyMapper#link(com.aurevia.exchange.fhir.FhirModel.ImagingStudyData, com.aurevia.radiology.ImagingOrder)}.</p>
     *
     * @param profile          partner profile name preserved in the mapping
     * @param patientId        verified local patient identifier (required)
     * @param orderId          imaging order identifier carried as the ServiceRequest reference (required)
     * @param studyInstanceUid external DICOM Study Instance UID (required)
     * @param accession        issuer-scoped imaging accession (required)
     * @param modality         DICOM modality wire code
         */
    public record ImagingStudyData(String profile, String patientId, String orderId,
                                   String studyInstanceUid, String accession, String modality) {
    }

    /**
     * Outcome of checking an incoming imaging study against the order it claims to belong to.
     *
     * <p>Only a {@code linked} result carries a study projection, so a mismatch cannot be
     * mistaken for a successful association. The mismatch outcome and reason name exactly
     * which label failed, which lets a caller open the existing study-link review queue
     * without guessing.</p>
     *
     * @param outcome "linked", "patient_mismatch" or "accession_mismatch"
     * @param study   the linkable study projection, or {@code null} on a mismatch
     * @param reason  bounded non-sensitive mismatch detail, empty when linked
         */
    public record StudyLinkResult(String outcome, ImagingStudyData study, String reason) {

        /**
         * Keep the outcome and the presence of a study in agreement.
         *
         * @throws ApiException when a linked result carries no study or a mismatch carries one
         */
        public StudyLinkResult {
            boolean shouldLink = "linked".equals(outcome);
            if (shouldLink != (study != null)) {
                throw new ApiException(422, "INVALID_STUDY_LINK_RESULT", "link outcome and study disagree");
            }
        }

        /**
         * Create the successful link result.
         *
         * @param study linkable study projection
         * @return a linked result carrying the study
         */
        public static StudyLinkResult linked(ImagingStudyData study) {
            return new StudyLinkResult("linked", study, "");
        }

        /**
         * Create a refusal that carries no linkable study.
         *
         * @param outcome stable mismatch outcome key
         * @param reason  bounded non-sensitive mismatch detail
         * @return a mismatch result with no study
         */
        public static StudyLinkResult mismatch(String outcome, String reason) {
            return new StudyLinkResult(outcome, null, reason);
        }

        /**
         * Report whether the study may be associated with the order.
         *
         * @return {@code true} only when a linkable study is present
         */
        public boolean linked() {
            return study != null;
        }
    }

    /**
     * The retained-document fields carried by a FHIR DocumentReference.
     *
     * <p>The checksum is the retained SHA-256 digest, the revision is the immutable version
     * number of the document, and the status carries the void state so a voided association
     * never appears as a current clinical document.</p>
     *
     * @param profile    partner profile name preserved in the mapping
     * @param patientId  verified local patient identifier (required)
     * @param documentId retained document identifier (required)
     * @param checksum   lowercase hexadecimal SHA-256 digest of the retained bytes
     * @param revision   immutable document revision
     * @param status     domain status, {@code active} or {@code voided}
     * @param voidReason recorded reason when voided, otherwise empty
     * @param voidedAt   instant the association was voided, otherwise empty
     * @param mediaType  retained document media type
         */
    public record DocumentReferenceData(String profile, String patientId, String documentId,
                                        String checksum, int revision, String status, String voidReason,
                                        String voidedAt, String mediaType) {
    }

    /**
     * The prescription fields carried by a FHIR MedicationRequest.
     *
     * <p>The selected catalog entry id is the drug code; dose, route, frequency, quantity,
     * prescriber and destination describe the clinician's order. No allergy or suggestion
     * data crosses this boundary: only the medication the clinician actually selected.</p>
     *
     * @param profile        partner profile name preserved in the mapping
     * @param patientId      verified local patient identifier (required)
     * @param requestId      prescription draft identifier (required)
     * @param drugCode       selected catalog entry identifier
     * @param doseValue      dose amount
     * @param doseUnit       dose unit
     * @param route          administration route code
     * @param frequency      administration frequency code
     * @param quantity       dispense quantity
     * @param quantityUnit   dispense quantity unit
     * @param prescriber     prescribing clinician reference
     * @param destination    intended pharmacy destination
         */
    public record MedicationRequestData(String profile, String patientId, String requestId,
                                        String drugCode, String doseValue, String doseUnit, String route,
                                        String frequency, String quantity, String quantityUnit,
                                        String prescriber, String destination) {
    }
}
