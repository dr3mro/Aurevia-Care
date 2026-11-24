package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.ObservationData;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ObservationMapperTest {

    @Test
    void numericObservationRoundTripsWithQualifiersUnitAndRange() {
        ObservationData source = FhirFixtures.observation("obs-1", FhirFixtures.numeric(), FhirFixtures.range());

        JsonNode resource = ObservationMapper.toFhir(source);
        ObservationData restored = ObservationMapper.fromFhir(resource);

        assertEquals("Observation", resource.path("resourceType").asString());
        assertEquals(5.4, resource.path("valueQuantity").path("value").asDouble(), 0.0001);
        assertEquals("mmol/L", resource.path("valueQuantity").path("unit").asString());
        assertEquals(FhirSupport.UCUM_SYSTEM, resource.path("valueQuantity").path("system").asString());
        assertEquals("mmol/L", resource.path("valueQuantity").path("code").asString());
        assertFalse(resource.path("valueQuantity").has("comparator"));
        assertEquals("3.9", resource.path("referenceRange").path(0).path("low").path("value").asString());
        assertEquals("7.1", resource.path("referenceRange").path(0).path("high").path("value").asString());
        assertEquals(FhirSupport.UCUM_SYSTEM,
                resource.path("referenceRange").path(0).path("high").path("system").asString());
        assertEquals("mmol/L",
                resource.path("referenceRange").path(0).path("high").path("unit").asString());
        assertEquals(source, restored);
    }

    @Test
    void qualifiedNumericKeepsLessThanComparator() {
        ObservationData source = FhirFixtures.observation("obs-2", FhirFixtures.qualified(), null);

        JsonNode resource = ObservationMapper.toFhir(source);
        ObservationData restored = ObservationMapper.fromFhir(resource);

        assertEquals("<", resource.path("valueQuantity").path("comparator").asString());
        assertEquals("qualified_numeric", restored.kind());
        assertNull(restored.referenceRange());
        assertEquals(source, restored);
    }

    @Test
    void textObservationRoundTrips() {
        ObservationData source = FhirFixtures.observation("obs-3", FhirFixtures.text(), null);

        JsonNode resource = ObservationMapper.toFhir(source);
        ObservationData restored = ObservationMapper.fromFhir(resource);

        assertEquals("clear", resource.path("valueString").asString());
        assertEquals("text", restored.kind());
        assertEquals(source, restored);
    }

    @Test
    void codedObservationRoundTrips() {
        ObservationData source = FhirFixtures.observation("obs-4", FhirFixtures.coded(), null);

        JsonNode resource = ObservationMapper.toFhir(source);
        ObservationData restored = ObservationMapper.fromFhir(resource);

        assertEquals("NEGATIVE", resource.path("valueCodeableConcept").path("coding").path(0)
                .path("code").asString());
        assertEquals("coded", restored.kind());
        assertEquals(source, restored);
    }

    @Test
    void unknownObservationRoundTripsWithoutAValue() {
        ObservationData source = FhirFixtures.observation("obs-5", FhirFixtures.unknown(), null);

        JsonNode resource = ObservationMapper.toFhir(source);
        ObservationData restored = ObservationMapper.fromFhir(resource);

        assertEquals("unknown", resource.path("dataAbsentReason").path("coding").path(0)
                .path("code").asString());
        assertFalse(resource.has("valueQuantity"));
        assertEquals("unknown", restored.presence());
        assertNull(restored.value());
        assertEquals(source, restored);
    }

    @Test
    void absentObservationRoundTripsAsNotPerformed() {
        ObservationData source = FhirFixtures.observation("obs-6", FhirFixtures.absent(), null);

        JsonNode resource = ObservationMapper.toFhir(source);
        ObservationData restored = ObservationMapper.fromFhir(resource);

        assertEquals("not-performed", resource.path("dataAbsentReason").path("coding").path(0)
                .path("code").asString());
        assertEquals("absent", restored.presence());
        assertEquals(source, restored);
    }

    @Test
    void unrelatedExtensionIsIgnored() {
        ObservationData source = FhirFixtures.observation("obs-7", FhirFixtures.numeric(), null);
        JsonNode resource = ObservationMapper.toFhir(source);
        ((ArrayNode) resource.path("extension")).add(
                FhirSupport.JSON.objectNode().put("url", "urn:unrelated").put("valueString", "ignored"));

        ObservationData restored = ObservationMapper.fromFhir(resource);

        assertEquals(source.verifier(), restored.verifier());
        assertEquals(source.instrument(), restored.instrument());
        assertEquals(source, restored);
    }

    @Test
    void resourceWithoutPatientIsRejected() {
        JsonNode resource = ObservationMapper.toFhir(
                FhirFixtures.observation("obs-8", FhirFixtures.numeric(), null));
        ((ObjectNode) resource).remove("subject");
        ApiException failure = assertThrows(ApiException.class, () -> ObservationMapper.fromFhir(resource));
        assertEquals("INVALID_OBSERVATION", failure.code());
    }

    @Test
    void resourceWithoutSpecimenIsRejected() {
        JsonNode resource = ObservationMapper.toFhir(
                FhirFixtures.observation("obs-9", FhirFixtures.numeric(), null));
        ((ObjectNode) resource).remove("specimen");
        assertThrows(ApiException.class, () -> ObservationMapper.fromFhir(resource));
    }

    @Test
    void wrongResourceTypeIsRejected() throws Exception {
        JsonNode resource = new tools.jackson.databind.ObjectMapper().readTree("{\"resourceType\":\"Patient\"}");
        assertThrows(ApiException.class, () -> ObservationMapper.fromFhir(resource));
    }
}
