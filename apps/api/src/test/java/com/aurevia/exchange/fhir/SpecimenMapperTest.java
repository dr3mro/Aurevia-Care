package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.SpecimenData;
import com.aurevia.lab.accession.AccessionState;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class SpecimenMapperTest {

    static Stream<Arguments> states() {
        return Stream.of(
                Arguments.of(AccessionState.ACCESSIONED, "available"),
                Arguments.of(AccessionState.REJECTED, "unsuitable"),
                Arguments.of(AccessionState.RECOLLECTED, "unavailable"));
    }

    @ParameterizedTest
    @MethodSource("states")
    void accessionRoundTripsThroughSpecimen(AccessionState state, String expectedStatus) {
        SpecimenData source = SpecimenMapper.fromAccession(FhirFixtures.accession(state), FhirFixtures.PROFILE);

        JsonNode resource = SpecimenMapper.toFhir(source);
        SpecimenData restored = SpecimenMapper.fromFhir(resource);

        assertEquals("Specimen", resource.path("resourceType").asString());
        assertEquals(expectedStatus, resource.path("status").asString());
        assertEquals("ACCEPTABLE", resource.path("quality").path(0).path("coding").path(0)
                .path("code").asString());
        assertEquals(source, restored);
    }

    @Test
    void resourceWithoutPatientIsRejected() {
        JsonNode resource = specimenResource();
        ((ObjectNode) resource).remove("subject");
        assertThrows(ApiException.class, () -> SpecimenMapper.fromFhir(resource));
    }

    @Test
    void resourceWithoutAccessionIdentifierIsRejected() {
        JsonNode resource = specimenResource();
        ((ArrayNode) resource.path("identifier")).set(0,
                FhirSupport.JSON.objectNode().put("value", ""));
        ApiException failure = assertThrows(ApiException.class, () -> SpecimenMapper.fromFhir(resource));
        assertEquals("INVALID_SPECIMEN", failure.code());
    }

    @Test
    void resourceWithoutOrderIdentifierIsRejected() {
        JsonNode resource = specimenResource();
        ((ArrayNode) resource.path("request")).set(0,
                FhirSupport.JSON.objectNode().put("reference", "ServiceRequest/"));
        assertThrows(ApiException.class, () -> SpecimenMapper.fromFhir(resource));
    }

    @Test
    void wrongResourceTypeIsRejected() throws Exception {
        JsonNode resource = new tools.jackson.databind.ObjectMapper()
                .readTree("{\"resourceType\":\"Observation\"}");
        assertThrows(ApiException.class, () -> SpecimenMapper.fromFhir(resource));
    }

    private JsonNode specimenResource() {
        return SpecimenMapper.toFhir(
                SpecimenMapper.fromAccession(FhirFixtures.accession(AccessionState.RECEIVED), FhirFixtures.PROFILE));
    }
}
