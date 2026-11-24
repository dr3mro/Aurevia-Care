package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.ImagingStudyData;
import com.aurevia.exchange.fhir.FhirModel.StudyLinkResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ImagingStudyMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ImagingStudyData study() {
        return ImagingStudyMapper.fromOrder(FhirFixtures.imagingOrder(), FhirFixtures.PROFILE);
    }

    private JsonNode studyResource() {
        return ImagingStudyMapper.toFhir(study());
    }

    @Test
    void orderRoundTripsThroughImagingStudy() {
        ImagingStudyData source = study();

        JsonNode resource = ImagingStudyMapper.toFhir(source);
        ImagingStudyData restored = ImagingStudyMapper.fromFhir(resource);

        assertEquals("ImagingStudy", resource.path("resourceType").asString());
        assertEquals("available", resource.path("status").asString());
        assertEquals(FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM,
                resource.path("identifier").path(0).path("system").asString());
        assertEquals(FhirFixtures.STUDY_UID, resource.path("identifier").path(0).path("value").asString());
        assertEquals(FhirSupport.ACCESSION_IDENTIFIER_SYSTEM,
                resource.path("identifier").path(1).path("system").asString());
        assertEquals(FhirFixtures.IMAGING_ACCESSION,
                resource.path("identifier").path(1).path("value").asString());
        assertEquals(FhirSupport.DICOM_MODALITY_SYSTEM,
                resource.path("modality").path(0).path("coding").path(0).path("system").asString());
        assertEquals("ct", resource.path("modality").path(0).path("coding").path(0).path("code").asString());
        assertEquals("ServiceRequest/" + FhirFixtures.ORDER,
                resource.path("basedOn").path(0).path("reference").asString());
        assertEquals(source, restored);
    }

    @Test
    void identifiersAreMatchedBySystemNotPosition() {
        ImagingStudyData source = study();
        JsonNode resource = studyResource();
        ArrayNode identifiers = (ArrayNode) resource.path("identifier");
        JsonNode studyIdentifier = identifiers.get(0).deepCopy();
        identifiers.set(0, identifiers.get(1).deepCopy());
        identifiers.set(1, studyIdentifier);

        ImagingStudyData restored = ImagingStudyMapper.fromFhir(resource);

        assertEquals(source, restored);
    }

    @Test
    void linkResultRejectsAnInconsistentOutcome() {
        ApiException missingStudy = assertThrows(ApiException.class,
                () -> new StudyLinkResult("linked", null, ""));
        assertEquals(422, missingStudy.status());
        assertThrows(ApiException.class, () -> new StudyLinkResult(
                "patient_mismatch", study(), "patient does not match the order"));
    }

    @Test
    void matchingPatientAndAccessionLinkTheStudy() {
        ImagingStudyData source = study();

        StudyLinkResult result = ImagingStudyMapper.link(source, FhirFixtures.imagingOrder());

        assertTrue(result.linked());
        assertEquals("linked", result.outcome());
        assertEquals(source, result.study());
    }

    @Test
    void wrongPatientStudyIsRefusedWithoutLinking() {
        ImagingStudyData study = ImagingStudyMapper.fromOrder(
                FhirFixtures.imagingOrder("pt-synthetic-wrong", FhirFixtures.IMAGING_ACCESSION),
                FhirFixtures.PROFILE);

        StudyLinkResult result = ImagingStudyMapper.link(study, FhirFixtures.imagingOrder());

        assertFalse(result.linked());
        assertEquals("patient_mismatch", result.outcome());
        assertNull(result.study());
        assertTrue(result.reason().contains("patient"));
    }

    @Test
    void wrongAccessionStudyIsRefusedWithoutLinking() {
        ImagingStudyData study = ImagingStudyMapper.fromOrder(
                FhirFixtures.imagingOrder(FhirFixtures.PATIENT, "IMG-2026-9999"), FhirFixtures.PROFILE);

        StudyLinkResult result = ImagingStudyMapper.link(study, FhirFixtures.imagingOrder());

        assertFalse(result.linked());
        assertEquals("accession_mismatch", result.outcome());
        assertNull(result.study());
        assertTrue(result.reason().contains("accession"));
    }

    @Test
    void resourceWithoutPatientIsRejected() {
        JsonNode resource = studyResource();
        ((ObjectNode) resource).remove("subject");

        ApiException failure = assertThrows(ApiException.class, () -> ImagingStudyMapper.fromFhir(resource));
        assertEquals(422, failure.status());
        assertEquals("INVALID_IMAGING_STUDY", failure.code());
    }

    @Test
    void resourceWithoutStudyUidIsRejected() {
        JsonNode resource = studyResource();
        ((ArrayNode) resource.path("identifier")).set(0,
                FhirSupport.JSON.objectNode().put("value", ""));

        assertThrows(ApiException.class, () -> ImagingStudyMapper.fromFhir(resource));
    }

    @Test
    void resourceWithoutAccessionIsRejected() {
        JsonNode resource = studyResource();
        ((ArrayNode) resource.path("identifier")).set(1,
                FhirSupport.JSON.objectNode().put("value", ""));

        assertThrows(ApiException.class, () -> ImagingStudyMapper.fromFhir(resource));
    }

    @Test
    void resourceWithoutOrderIsRejected() {
        JsonNode resource = studyResource();
        ((ArrayNode) resource.path("basedOn")).set(0,
                FhirSupport.JSON.objectNode().put("reference", "ServiceRequest/"));

        assertThrows(ApiException.class, () -> ImagingStudyMapper.fromFhir(resource));
    }

    @Test
    void wrongResourceTypeIsRejected() throws Exception {
        JsonNode resource = JSON.readTree("{\"resourceType\":\"Patient\"}");
        assertThrows(ApiException.class, () -> ImagingStudyMapper.fromFhir(resource));
    }
}
