package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.ServiceRequestData;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ServiceRequestMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void orderRoundTripsThroughServiceRequest() {
        ServiceRequestData source = ServiceRequestMapper.fromOrder(FhirFixtures.order(), FhirFixtures.PROFILE);

        JsonNode resource = ServiceRequestMapper.toFhir(source);
        ServiceRequestData restored = ServiceRequestMapper.fromFhir(resource);

        assertEquals("ServiceRequest", resource.path("resourceType").asString());
        assertEquals(FhirFixtures.PROFILE, resource.path("meta").path("profile").path(0).asString());
        assertEquals(FhirSupport.ORDER_IDENTIFIER_SYSTEM,
                resource.path("identifier").path(0).path("system").asString());
        assertEquals(FhirFixtures.ORDER, resource.path("identifier").path(0).path("value").asString());
        assertEquals(FhirSupport.AUTHORIZATION_IDENTIFIER_SYSTEM,
                resource.path("identifier").path(1).path("system").asString());
        assertEquals(source, restored);
    }

    @Test
    void resourceWithoutPatientIsRejected() {
        JsonNode resource = ServiceRequestMapper.toFhir(
                ServiceRequestMapper.fromOrder(FhirFixtures.order(), FhirFixtures.PROFILE));
        ((ObjectNode) resource).remove("subject");

        ApiException failure = assertThrows(ApiException.class, () -> ServiceRequestMapper.fromFhir(resource));
        assertEquals(422, failure.status());
        assertEquals("INVALID_SERVICE_REQUEST", failure.code());
    }

    @Test
    void resourceWithoutOrderIdentifierIsRejected() {
        JsonNode resource = ServiceRequestMapper.toFhir(
                ServiceRequestMapper.fromOrder(FhirFixtures.order(), FhirFixtures.PROFILE));
        ((ArrayNode) resource.path("identifier")).set(0,
                FhirSupport.JSON.objectNode().put("value", ""));

        assertThrows(ApiException.class, () -> ServiceRequestMapper.fromFhir(resource));
    }

    @Test
    void wrongTypedEncounterIsNotImported() {
        JsonNode resource = ServiceRequestMapper.toFhir(
                ServiceRequestMapper.fromOrder(FhirFixtures.order(), FhirFixtures.PROFILE));
        ((ArrayNode) resource.path("encounter")).set(0,
                FhirSupport.JSON.objectNode().put("reference", "Location/synthetic-room"));

        ServiceRequestData restored = ServiceRequestMapper.fromFhir(resource);

        assertEquals("", restored.encounterRef());
    }

    @Test
    void wrongResourceTypeIsRejected() throws Exception {
        JsonNode resource = JSON.readTree("{\"resourceType\":\"Patient\"}");
        assertThrows(ApiException.class, () -> ServiceRequestMapper.fromFhir(resource));
    }
}
