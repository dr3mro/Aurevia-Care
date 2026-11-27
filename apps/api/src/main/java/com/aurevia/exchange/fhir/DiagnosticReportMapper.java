package com.aurevia.exchange.fhir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.DiagnosticReportData;
import com.aurevia.exchange.fhir.FhirModel.ObservationData;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.lab.result.ReportRevision;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a laboratory report revision to and from a FHIR R4 DiagnosticReport.
 *
 * <p>A corrected revision carries a {@code supersedes} extension that links it to the
 * version it replaces; the prior revision stays immutable. Patient, specimen and order
 * identifiers are mandatory on import, and the contained observations are mapped in both
 * directions so the report round trip keeps their qualifiers and units.</p>
 */
public final class DiagnosticReportMapper {

    private static final String RESOURCE_TYPE = "DiagnosticReport";
    private static final String ERROR_CODE = "INVALID_DIAGNOSTIC_REPORT";
    private static final String SOURCE_EXTENSION = "urn:aurevia:fhir:report-source";
    private static final String SUPERSEDES_EXTENSION = "urn:aurevia:fhir:supersedes";

    private DiagnosticReportMapper() {
    }

    /**
     * Build the FHIR-boundary projection of a report revision.
     *
     * @param revision     report revision from the laboratory kernel
     * @param observations contained observation projections in capture order
     * @param profile      partner profile name
     * @return the projection to map to FHIR JSON
     */
    public static DiagnosticReportData fromRevision(ReportRevision revision,
                                                    List<ObservationData> observations, String profile) {
        return new DiagnosticReportData(profile, revision.patientId(), revision.reportId(), revision.version(),
                revision.orderId(), revision.specimenId(), fhirStatus(revision.state()), observations,
                revision.supersedesVersion(), revision.effectiveAt().toString());
    }

    /**
     * Render the projection as a FHIR R4 DiagnosticReport JSON resource.
     *
     * @param data projection to render
     * @return the FHIR resource
     */
    public static JsonNode toFhir(DiagnosticReportData data) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("resourceType", RESOURCE_TYPE);
        node.put("id", Objects.toString(data.reportId(), ""));
        ObjectNode meta = FhirSupport.meta(data.profile());
        meta.put("versionId", Integer.toString(data.version()));
        node.set("meta", meta);

        ObjectNode identifier = FhirSupport.JSON.objectNode();
        identifier.put("value", Objects.toString(data.reportId(), ""));
        node.set("identifier", FhirSupport.JSON.arrayNode().add(identifier));

        node.put("status", Objects.toString(data.state(), ""));
        node.set("subject", FhirSupport.reference("Patient", data.patientId()));
        node.set("specimen", FhirSupport.JSON.arrayNode()
                .add(FhirSupport.reference("Specimen", data.specimenId())));
        node.set("basedOn", FhirSupport.JSON.arrayNode()
                .add(FhirSupport.reference("ServiceRequest", data.orderId())));
        node.put("effectiveDateTime", Objects.toString(data.effectiveAt(), ""));

        ArrayNode extensions = FhirSupport.JSON.arrayNode();
        ObjectNode source = FhirSupport.JSON.objectNode();
        source.put("url", SOURCE_EXTENSION);
        source.put("valueString", "synthetic-lab");
        extensions.add(source);

        Integer supersedes = data.supersedesVersion();
        if (supersedes != null) {
            ObjectNode link = FhirSupport.JSON.objectNode();
            link.put("url", SUPERSEDES_EXTENSION);
            ObjectNode reference = FhirSupport.JSON.objectNode();
            reference.put("reference", RESOURCE_TYPE + "/" + data.reportId() + "/_history/" + supersedes);
            link.set("valueReference", reference);
            extensions.add(link);
        }
        node.set("extension", extensions);

        ArrayNode contained = FhirSupport.JSON.arrayNode();
        ArrayNode results = FhirSupport.JSON.arrayNode();
        for (ObservationData observation : data.observations()) {
            JsonNode resource = ObservationMapper.toFhir(observation);
            ((ObjectNode) resource).put("id", observation.id());
            contained.add(resource);
            results.add(resultReference(observation.id()));
        }
        node.set("contained", contained);
        node.set("result", results);
        return node;
    }

    /**
     * Read a FHIR R4 DiagnosticReport, rejecting one without a patient, specimen or order identifier.
     *
     * @param resource FHIR resource
     * @return the FHIR-boundary projection
     * @throws ApiException when the resource type or a required identifier is invalid
     */
    public static DiagnosticReportData fromFhir(JsonNode resource) {
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new ApiException(422, ERROR_CODE, "resource is not a DiagnosticReport");
        }
        String patientId = FhirSupport.referenceId(resource, "subject", "Patient", ERROR_CODE);
        String specimenId = FhirSupport.arrayReferenceId(resource.path("specimen"), 0,
                "Specimen", ERROR_CODE);
        String orderId = FhirSupport.arrayReferenceId(resource.path("basedOn"), 0,
                "ServiceRequest", ERROR_CODE);
        String reportId = FhirSupport.requiredText(resource, "id", ERROR_CODE);

        Integer supersedes = null;
        for (JsonNode extension : resource.path("extension")) {
            if (SUPERSEDES_EXTENSION.equals(extension.path("url").asString())) {
                String reference = extension.path("valueReference").path("reference").asString();
                supersedes = parseSupersedesVersion(reference);
            }
        }

        List<ObservationData> observations = new ArrayList<>();
        for (JsonNode contained : resource.path("contained")) {
            observations.add(ObservationMapper.fromFhir(contained));
        }
        return new DiagnosticReportData(FhirSupport.profile(resource), patientId, reportId,
                resource.path("meta").path("versionId").asInt(), orderId, specimenId,
                resource.path("status").asString(), observations, supersedes,
                resource.path("effectiveDateTime").asString());
    }

    private static Integer parseSupersedesVersion(String reference) {
        String version = reference.substring(reference.lastIndexOf('/') + 1);
        try {
            return Integer.valueOf(version);
        } catch (NumberFormatException exception) {
            throw new ApiException(422, ERROR_CODE, "invalid supersedes reference");
        }
    }

    private static ObjectNode resultReference(String id) {
        ObjectNode reference = FhirSupport.JSON.objectNode();
        reference.put("reference", "#" + id);
        return reference;
    }

    private static String fhirStatus(VerificationState state) {
        if (state == VerificationState.ENTERED_IN_ERROR) {
            return "entered-in-error";
        }
        return state.wire();
    }
}
