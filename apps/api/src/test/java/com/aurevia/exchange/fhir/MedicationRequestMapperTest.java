package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.MedicationRequestData;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class MedicationRequestMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MedicationRequestData source() {
        return MedicationRequestMapper.fromDraft(FhirFixtures.draft(), FhirFixtures.PROFILE);
    }

    private JsonNode resource() {
        return MedicationRequestMapper.toFhir(source());
    }

    @Test
    void draftRoundTripsThroughMedicationRequest() {
        MedicationRequestData source = source();

        JsonNode result = MedicationRequestMapper.toFhir(source);
        JsonNode dosage = result.path("dosageInstruction").path(0);
        MedicationRequestData restored = MedicationRequestMapper.fromFhir(result);

        assertEquals("MedicationRequest", result.path("resourceType").asString());
        assertEquals("active", result.path("status").asString());
        assertEquals("order", result.path("intent").asString());
        assertEquals("DRUG-CODE-001",
                result.path("medicationCodeableConcept").path("coding").path(0).path("code").asString());
        assertEquals("ORAL", dosage.path("route").path("coding").path(0).path("code").asString());
        assertEquals("BID", dosage.path("timing").path("code").path("coding").path(0).path("code").asString());
        assertEquals("500", dosage.path("doseAndRate").path(0).path("doseQuantity")
                .path("value").decimalValue().toPlainString());
        assertTrue(dosage.path("doseAndRate").path(0).path("doseQuantity").path("value").isNumber());
        assertEquals("mg", dosage.path("doseAndRate").path(0).path("doseQuantity").path("unit").asString());
        assertEquals("20", result.path("dispenseRequest").path("quantity")
                .path("value").decimalValue().toPlainString());
        assertTrue(result.path("dispenseRequest").path("quantity").path("value").isNumber());
        assertEquals("Practitioner/prescriber-synthetic",
                result.path("requester").path("reference").asString());
        assertEquals("pharmacy-synthetic", result.path("extension").path(0).path("valueString").asString());
        assertEquals(source, restored);
    }

    @Test
    void nonNumericDoseIsRejected() {
        MedicationRequestData invalid = new MedicationRequestData(FhirFixtures.PROFILE,
                FhirFixtures.PATIENT, "draft-synthetic-001", "DRUG-CODE-001",
                "not-a-number", "mg", "ORAL", "BID", "20", "capsule",
                "prescriber-synthetic", "pharmacy-synthetic");

        ApiException failure = assertThrows(ApiException.class, () -> MedicationRequestMapper.toFhir(invalid));
        assertEquals(422, failure.status());
        assertEquals("INVALID_MEDICATION_REQUEST", failure.code());
    }

    @Test
    void nonNumericQuantityIsRejected() {
        MedicationRequestData invalid = new MedicationRequestData(FhirFixtures.PROFILE,
                FhirFixtures.PATIENT, "draft-synthetic-001", "DRUG-CODE-001",
                "500", "mg", "ORAL", "BID", "not-a-number", "capsule",
                "prescriber-synthetic", "pharmacy-synthetic");

        ApiException failure = assertThrows(ApiException.class, () -> MedicationRequestMapper.toFhir(invalid));
        assertEquals(422, failure.status());
    }

    @Test
    void unknownExtensionIsIgnored() {
        JsonNode result = resource();
        ((ObjectNode) result.path("extension").path(0)).put("url", "urn:unknown:extension");

        MedicationRequestData restored = MedicationRequestMapper.fromFhir(result);

        assertEquals("", restored.destination());
    }

    @Test
    void resourceWithoutPatientIsRejected() {
        JsonNode result = resource();
        ((ObjectNode) result).remove("subject");

        ApiException failure = assertThrows(ApiException.class, () -> MedicationRequestMapper.fromFhir(result));
        assertEquals(422, failure.status());
        assertEquals("INVALID_MEDICATION_REQUEST", failure.code());
    }

    @Test
    void resourceWithoutRequestIdentifierIsRejected() {
        JsonNode result = resource();
        ((ObjectNode) result).put("id", "");

        assertThrows(ApiException.class, () -> MedicationRequestMapper.fromFhir(result));
    }

    @Test
    void wrongResourceTypeIsRejected() throws Exception {
        JsonNode result = JSON.readTree("{\"resourceType\":\"Patient\"}");
        assertThrows(ApiException.class, () -> MedicationRequestMapper.fromFhir(result));
    }
}
