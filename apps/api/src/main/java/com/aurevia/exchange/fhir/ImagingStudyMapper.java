package com.aurevia.exchange.fhir;

import java.util.Locale;
import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.ImagingStudyData;
import com.aurevia.exchange.fhir.FhirModel.StudyLinkResult;
import com.aurevia.radiology.ImagingOrder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps an associated external study to and from a FHIR R4 ImagingStudy (RAD-01).
 *
 * <p>The Study Instance UID and accession are typed identifiers, the modality is a DICOM
 * coding and the imaging order is a {@code ServiceRequest} reference. Import rejects a
 * resource without a patient, study, accession or order identifier; no label is invented
 * from the modality or resource id.</p>
 *
 * <p>Linking is deliberately a separate, explicit step. A study is only linkable after
 * both patient and accession labels match the owning order; a mismatch yields a
 * {@link com.aurevia.exchange.fhir.FhirModel.StudyLinkResult} that carries no
 * study, so a caller cannot associate the study by ignoring the result.</p>
 */
public final class ImagingStudyMapper {

    private static final String RESOURCE_TYPE = "ImagingStudy";
    private static final String ERROR_CODE = "INVALID_IMAGING_STUDY";
    private static final String PATIENT_MISMATCH = "patient_mismatch";
    private static final String ACCESSION_MISMATCH = "accession_mismatch";

    private ImagingStudyMapper() {
    }

    /**
     * Build the FHIR-boundary projection of an associated imaging order.
     *
     * @param order   associated imaging order carrying the external study
     * @param profile partner profile name
     * @return the projection to map to FHIR JSON
     */
    public static ImagingStudyData fromOrder(ImagingOrder order, String profile) {
        String modality = Objects.toString(order.modality(), "").toLowerCase(Locale.ROOT);
        return new ImagingStudyData(profile, Objects.toString(order.patientId(), ""),
                Objects.toString(order.orderId(), ""), Objects.toString(order.studyInstanceUid(), ""),
                Objects.toString(order.accession(), ""), modality);
    }

    /**
     * Render the projection as a FHIR R4 ImagingStudy JSON resource.
     *
     * @param data projection to render
     * @return the FHIR resource
     */
    public static JsonNode toFhir(ImagingStudyData data) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("resourceType", RESOURCE_TYPE);
        node.put("id", Objects.toString(data.orderId(), ""));
        node.set("meta", FhirSupport.meta(data.profile()));
        node.put("status", "available");

        ArrayNode identifiers = FhirSupport.JSON.arrayNode();
        identifiers.add(FhirSupport.identifier(FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM,
                data.studyInstanceUid()));
        identifiers.add(FhirSupport.identifier(FhirSupport.ACCESSION_IDENTIFIER_SYSTEM, data.accession()));
        node.set("identifier", identifiers);

        node.set("subject", FhirSupport.reference("Patient", data.patientId()));
        node.set("basedOn", FhirSupport.JSON.arrayNode()
                .add(FhirSupport.reference("ServiceRequest", data.orderId())));

        ObjectNode modalityCoding = FhirSupport.JSON.objectNode();
        modalityCoding.put("system", FhirSupport.DICOM_MODALITY_SYSTEM);
        modalityCoding.put("code", Objects.toString(data.modality(), ""));
        ObjectNode modality = FhirSupport.JSON.objectNode();
        modality.set("coding", FhirSupport.JSON.arrayNode().add(modalityCoding));
        node.set("modality", FhirSupport.JSON.arrayNode().add(modality));
        return node;
    }

    /**
     * Read a FHIR R4 ImagingStudy, rejecting one without a patient, study, accession or order identifier.
     *
     * @param resource FHIR resource
     * @return the FHIR-boundary projection
     * @throws ApiException when the resource type or a required identifier is invalid
     */
    public static ImagingStudyData fromFhir(JsonNode resource) {
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new ApiException(422, ERROR_CODE, "resource is not an ImagingStudy");
        }
        String patientId = FhirSupport.referenceId(resource, "subject", "Patient", ERROR_CODE);
        String studyUid = FhirSupport.identifierValue(resource, FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM,
                ERROR_CODE);
        String accession = FhirSupport.identifierValue(resource, FhirSupport.ACCESSION_IDENTIFIER_SYSTEM,
                ERROR_CODE);
        String orderId = FhirSupport.arrayReferenceId(resource.path("basedOn"), 0,
                "ServiceRequest", ERROR_CODE);
        String modality = resource.path("modality").path(0).path("coding").path(0).path("code").asString();
        return new ImagingStudyData(FhirSupport.profile(resource), patientId, orderId, studyUid,
                accession, modality);
    }

    /**
     * Verify an incoming study against the order it claims to belong to before any link is made.
     *
     * <p>Both labels must match. A patient mismatch is reported first so the caller can
     * distinguish a wrong-patient association from a wrong-accession one. No revision is
     * produced and no study is returned on a mismatch.</p>
     *
     * @param study an incoming study projection
     * @param order the order the study claims to belong to
     * @return a linked result carrying the study, or a mismatch result carrying none
     */
    public static StudyLinkResult link(ImagingStudyData study, ImagingOrder order) {
        if (!order.patientId().equals(study.patientId())) {
            return StudyLinkResult.mismatch(PATIENT_MISMATCH, "patient does not match the order");
        }
        if (!Objects.equals(order.accession(), study.accession())) {
            return StudyLinkResult.mismatch(ACCESSION_MISMATCH, "accession does not match the order");
        }
        return StudyLinkResult.linked(study);
    }
}
