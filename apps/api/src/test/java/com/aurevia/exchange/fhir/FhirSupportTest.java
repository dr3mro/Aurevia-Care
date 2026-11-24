package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class FhirSupportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode tree(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void requiredTextReturnsTheValue() throws Exception {
        JsonNode node = tree("{\"id\":\"ord-1\"}");
        assertEquals("ord-1", FhirSupport.requiredText(node, "id", "INVALID_RESOURCE"));
    }

    @Test
    void requiredTextRejectsBlankValue() throws Exception {
        JsonNode node = tree("{\"id\":\"  \"}");
        ApiException failure = assertThrows(ApiException.class,
                () -> FhirSupport.requiredText(node, "id", "INVALID_RESOURCE"));
        assertEquals(422, failure.status());
        assertEquals("INVALID_RESOURCE", failure.code());
    }

    @Test
    void referenceIdReadsTheTypedIdentifier() throws Exception {
        JsonNode node = tree("{\"subject\":{\"reference\":\"Patient/pt-1\"}}");
        assertEquals("pt-1", FhirSupport.referenceId(node, "subject", "Patient", "INVALID_RESOURCE"));
    }

    @Test
    void referenceIdRejectsWrongType() throws Exception {
        JsonNode node = tree("{\"subject\":{\"reference\":\"Practitioner/pt-1\"}}");
        ApiException failure = assertThrows(ApiException.class,
                () -> FhirSupport.referenceId(node, "subject", "Patient", "INVALID_RESOURCE"));
        assertEquals("INVALID_RESOURCE", failure.code());
    }

    @Test
    void referenceIdRejectsEmptyIdentifier() throws Exception {
        JsonNode node = tree("{\"subject\":{\"reference\":\"Patient/\"}}");
        assertThrows(ApiException.class,
                () -> FhirSupport.referenceId(node, "subject", "Patient", "INVALID_RESOURCE"));
    }

    @Test
    void arrayReferenceIdReadsTheTypedIdentifier() throws Exception {
        JsonNode array = tree("[{\"reference\":\"ServiceRequest/ord-1\"}]");
        assertEquals("ord-1",
                FhirSupport.arrayReferenceId(array, 0, "ServiceRequest", "INVALID_RESOURCE"));
    }

    @Test
    void identifierValueReadsTheMatchingSystemRegardlessOfPosition() throws Exception {
        JsonNode node = tree("{\"identifier\":["
                + "{\"system\":\"urn:other\",\"value\":\"wrong\"},"
                + "{\"system\":\"" + FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM
                + "\",\"value\":\"1.2.3\"}]}");
        assertEquals("1.2.3", FhirSupport.identifierValue(node,
                FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM, "INVALID_RESOURCE"));
    }

    @Test
    void identifierValueRejectsUnknownSystem() throws Exception {
        JsonNode node = tree("{\"identifier\":[{\"system\":\"urn:other\",\"value\":\"wrong\"}]}");
        ApiException failure = assertThrows(ApiException.class, () -> FhirSupport.identifierValue(node,
                FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM, "INVALID_RESOURCE"));
        assertEquals(422, failure.status());
        assertEquals("INVALID_RESOURCE", failure.code());
    }

    @Test
    void identifierValueRejectsBlankMatchingValue() throws Exception {
        JsonNode node = tree("{\"identifier\":[{\"system\":\""
                + FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM + "\",\"value\":\"  \"}]}");
        assertThrows(ApiException.class, () -> FhirSupport.identifierValue(node,
                FhirSupport.STUDY_UID_IDENTIFIER_SYSTEM, "INVALID_RESOURCE"));
    }

    @Test
    void referenceAndMetaBuildersCarryProfileAndType() {
        ObjectNode reference = FhirSupport.reference("Patient", "pt-1");
        assertEquals("Patient/pt-1", reference.path("reference").asString());
        assertEquals("GLU", FhirSupport.coding("GLU").path("code").asString());
        assertEquals("partner-r4",
                FhirSupport.meta("partner-r4").path("profile").path(0).asString());
        assertEquals("", FhirSupport.profile(FhirSupport.JSON.objectNode()));
    }
}
