package com.aurevia.exchange.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.DiagnosticReportData;
import com.aurevia.exchange.fhir.FhirModel.ObservationData;
import com.aurevia.lab.result.LabTypes.VerificationState;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DiagnosticReportMapperTest {

    private DiagnosticReportData report(int version, Integer supersedes, VerificationState state,
                                        List<ObservationData> observations) {
        return DiagnosticReportMapper.fromRevision(
                FhirFixtures.revision(version, supersedes, state,
                        observations.isEmpty() ? List.of() : List.of(FhirFixtures.numeric())),
                observations, FhirFixtures.PROFILE);
    }

    @Test
    void finalReportRoundTripsWithContainedObservations() {
        List<ObservationData> observations = List.of(
                FhirFixtures.observation("obs-1", FhirFixtures.numeric(), FhirFixtures.range()),
                FhirFixtures.observation("obs-2", FhirFixtures.qualified(), null));
        DiagnosticReportData source = report(1, null, VerificationState.FINAL, observations);

        JsonNode resource = DiagnosticReportMapper.toFhir(source);
        DiagnosticReportData restored = DiagnosticReportMapper.fromFhir(resource);

        assertEquals("DiagnosticReport", resource.path("resourceType").asString());
        assertEquals("final", resource.path("status").asString());
        assertEquals(2, resource.path("contained").size());
        assertEquals("#obs-1", resource.path("result").path(0).path("reference").asString());
        assertEquals(source, restored);
    }

    @Test
    void correctedReportLinksToTheOriginalRevision() {
        List<ObservationData> observations = List.of(
                FhirFixtures.observation("obs-3", FhirFixtures.numeric(), FhirFixtures.range()));
        DiagnosticReportData source = report(2, 1, VerificationState.CORRECTED, observations);

        JsonNode resource = DiagnosticReportMapper.toFhir(source);
        JsonNode link = resource.path("extension").path(1);
        assertEquals("urn:aurevia:fhir:supersedes", link.path("url").asString());
        assertEquals("DiagnosticReport/" + FhirFixtures.REPORT + "/_history/1",
                link.path("valueReference").path("reference").asString());
        assertEquals("corrected", resource.path("status").asString());

        DiagnosticReportData restored = DiagnosticReportMapper.fromFhir(resource);
        assertEquals(Integer.valueOf(1), restored.supersedesVersion());
        assertEquals(source, restored);
    }

    @Test
    void enteredInErrorReportWithNoObservationsRoundTrips() {
        DiagnosticReportData source = report(3, 2, VerificationState.ENTERED_IN_ERROR, List.of());

        JsonNode resource = DiagnosticReportMapper.toFhir(source);
        DiagnosticReportData restored = DiagnosticReportMapper.fromFhir(resource);

        assertEquals("entered-in-error", resource.path("status").asString());
        assertEquals(0, resource.path("contained").size());
        assertEquals(Integer.valueOf(2), restored.supersedesVersion());
        assertEquals(source, restored);
    }

    @Test
    void resourceWithoutPatientIsRejected() {
        JsonNode resource = reportResource();
        ((ObjectNode) resource).remove("subject");
        ApiException failure = assertThrows(ApiException.class, () -> DiagnosticReportMapper.fromFhir(resource));
        assertEquals("INVALID_DIAGNOSTIC_REPORT", failure.code());
    }

    @Test
    void resourceWithoutAccessionIsRejected() {
        JsonNode resource = reportResource();
        ((ArrayNode) resource.path("specimen")).set(0,
                FhirSupport.JSON.objectNode().put("reference", "Specimen/"));
        assertThrows(ApiException.class, () -> DiagnosticReportMapper.fromFhir(resource));
    }

    @Test
    void resourceWithoutOrderIsRejected() {
        JsonNode resource = reportResource();
        ((ArrayNode) resource.path("basedOn")).set(0,
                FhirSupport.JSON.objectNode().put("reference", "ServiceRequest/"));
        assertThrows(ApiException.class, () -> DiagnosticReportMapper.fromFhir(resource));
    }

    @Test
    void resourceWithoutReportIdentifierIsRejected() {
        JsonNode resource = reportResource();
        ((ObjectNode) resource).put("id", "");
        assertThrows(ApiException.class, () -> DiagnosticReportMapper.fromFhir(resource));
    }

    @Test
    void malformedSupersedesReferenceIsRejected() {
        List<ObservationData> observations = List.of(
                FhirFixtures.observation("obs-10", FhirFixtures.numeric(), null));
        JsonNode resource = DiagnosticReportMapper.toFhir(
                report(2, 1, VerificationState.CORRECTED, observations));
        ((ObjectNode) resource.path("extension").path(1).path("valueReference"))
                .put("reference", "DiagnosticReport/rpt-synthetic-001/_history/not-a-number");

        ApiException failure = assertThrows(ApiException.class, () -> DiagnosticReportMapper.fromFhir(resource));
        assertEquals(422, failure.status());
        assertEquals("INVALID_DIAGNOSTIC_REPORT", failure.code());
    }

    @Test
    void wrongResourceTypeIsRejected() throws Exception {
        JsonNode resource = new tools.jackson.databind.ObjectMapper().readTree("{\"resourceType\":\"Patient\"}");
        assertThrows(ApiException.class, () -> DiagnosticReportMapper.fromFhir(resource));
    }

    private JsonNode reportResource() {
        return DiagnosticReportMapper.toFhir(report(1, null, VerificationState.FINAL, List.of()));
    }
}
