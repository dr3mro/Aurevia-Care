package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.DocumentReferenceData;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DocumentReferenceMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHECKSUM = FhirFixtures.CHECKSUM;

    private JsonNode activeResource() {
        return DocumentReferenceMapper.toFhir(
                DocumentReferenceMapper.fromAssociation(FhirFixtures.activeDocument(), FhirFixtures.PROFILE));
    }

    @Test
    void activeDocumentRoundTripsWithChecksumAndRevision() {
        DocumentReferenceData source = DocumentReferenceMapper.fromAssociation(
                FhirFixtures.activeDocument(), FhirFixtures.PROFILE);

        JsonNode resource = DocumentReferenceMapper.toFhir(source);
        DocumentReferenceData restored = DocumentReferenceMapper.fromFhir(resource);

        assertEquals("DocumentReference", resource.path("resourceType").asString());
        assertEquals("current", resource.path("status").asString());
        assertEquals("3", resource.path("meta").path("versionId").asString());
        assertEquals("application/pdf",
                resource.path("content").path(0).path("attachment").path("contentType").asString());
        assertEquals(source, restored);
    }

    @Test
    void voidedDocumentMapsToEnteredInErrorStatus() {
        DocumentReferenceData source = DocumentReferenceMapper.fromAssociation(
                FhirFixtures.voidedDocument(), FhirFixtures.PROFILE);

        JsonNode resource = DocumentReferenceMapper.toFhir(source);
        DocumentReferenceData restored = DocumentReferenceMapper.fromFhir(resource);

        assertEquals("entered-in-error", resource.path("status").asString());
        assertEquals("4", resource.path("meta").path("versionId").asString());
        assertEquals("duplicate copy", resource.path("extension").path(0).path("valueString").asString());
        assertEquals("2026-11-21T09:00:00Z",
                resource.path("extension").path(1).path("valueString").asString());
        assertEquals("voided", restored.status());
        assertEquals("duplicate copy", restored.voidReason());
        assertEquals(source, restored);
    }

    @Test
    void checksumSurvivesBase64RoundTrip() {
        DocumentReferenceData source = DocumentReferenceMapper.fromAssociation(
                FhirFixtures.activeDocument(), FhirFixtures.PROFILE);

        JsonNode resource = DocumentReferenceMapper.toFhir(source);
        String wireChecksum = resource.path("content").path(0).path("attachment").path("checksum").asString();
        assertEquals(CHECKSUM.length() / 2, java.util.Base64.getDecoder().decode(wireChecksum).length);
        assertEquals(CHECKSUM, DocumentReferenceMapper.fromFhir(resource).checksum());
    }

    @Test
    void unknownExtensionIsIgnored() {
        JsonNode resource = activeResource();
        ((ObjectNode) resource.path("extension").path(0)).put("url", "urn:unknown:extension");

        DocumentReferenceData restored = DocumentReferenceMapper.fromFhir(resource);

        assertEquals("", restored.voidReason());
    }

    @Test
    void resourceWithoutPatientIsRejected() {
        JsonNode resource = activeResource();
        ((ObjectNode) resource).remove("subject");

        ApiException failure = assertThrows(ApiException.class, () -> DocumentReferenceMapper.fromFhir(resource));
        assertEquals(422, failure.status());
        assertEquals("INVALID_DOCUMENT_REFERENCE", failure.code());
    }

    @Test
    void resourceWithoutDocumentIdentifierIsRejected() {
        JsonNode resource = activeResource();
        ((ArrayNode) resource.path("identifier")).set(0,
                FhirSupport.JSON.objectNode().put("value", ""));

        assertThrows(ApiException.class, () -> DocumentReferenceMapper.fromFhir(resource));
    }

    @Test
    void wrongResourceTypeIsRejected() throws Exception {
        JsonNode resource = JSON.readTree("{\"resourceType\":\"Patient\"}");
        assertThrows(ApiException.class, () -> DocumentReferenceMapper.fromFhir(resource));
    }
}
