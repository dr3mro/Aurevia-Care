package com.aurevia.exchange.fhir;

import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.SpecimenData;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps an accessioned specimen to and from a FHIR R4 Specimen (LAB-01, XCH-04).
 *
 * <p>Patient, accession and source-order identifiers are all required on import.
 * The mapper never invents a specimen from a partial resource: a missing identifier
 * is rejected with a stable {@code 422} code.</p>
 */
public final class SpecimenMapper {

    private static final String RESOURCE_TYPE = "Specimen";
    private static final String ERROR_CODE = "INVALID_SPECIMEN";

    private SpecimenMapper() {
    }

    /**
     * Build the FHIR-boundary projection of an accession.
     *
     * @param accession accessioned specimen aggregate
     * @param profile   partner profile name
     * @return the projection to map to FHIR JSON
     */
    public static SpecimenData fromAccession(LabAccession accession, String profile) {
        AccessionState state = accession.state();
        String status = state == AccessionState.REJECTED ? "unsuitable"
                : state == AccessionState.RECOLLECTED ? "unavailable" : "available";
        return new SpecimenData(profile, accession.patientId(), accession.id(), accession.accessionNo(),
                accession.accessionIssuer(), accession.sourceOrderRef(), accession.specimenType(),
                accession.siteCode(), accession.collector(), accession.collectedAt(), accession.receivedAt(),
                accession.quality(), status);
    }

    /**
     * Render the projection as a FHIR R4 Specimen JSON resource.
     *
     * @param data projection to render
     * @return the FHIR resource
     */
    public static JsonNode toFhir(SpecimenData data) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("resourceType", RESOURCE_TYPE);
        node.put("id", Objects.toString(data.accessionId(), ""));
        node.set("meta", FhirSupport.meta(data.profile()));
        node.put("status", Objects.toString(data.status(), ""));

        ObjectNode identifier = FhirSupport.JSON.objectNode();
        identifier.put("value", Objects.toString(data.accessionNo(), ""));
        ObjectNode assigner = FhirSupport.JSON.objectNode();
        assigner.put("display", Objects.toString(data.accessionIssuer(), ""));
        identifier.set("assigner", assigner);
        ArrayNode identifiers = FhirSupport.JSON.arrayNode();
        identifiers.add(identifier);
        node.set("identifier", identifiers);

        node.set("subject", FhirSupport.reference("Patient", data.patientId()));

        ArrayNode requests = FhirSupport.JSON.arrayNode();
        requests.add(FhirSupport.reference("ServiceRequest", data.orderId()));
        node.set("request", requests);

        node.set("type", FhirSupport.concept(data.specimenType()));

        ObjectNode collection = FhirSupport.JSON.objectNode();
        collection.put("collectedDateTime", Objects.toString(data.collectedAt(), ""));
        ObjectNode bodySite = FhirSupport.JSON.objectNode();
        bodySite.set("coding", FhirSupport.JSON.arrayNode().add(FhirSupport.coding(data.siteCode())));
        collection.set("bodySite", bodySite);
        ObjectNode collector = FhirSupport.JSON.objectNode();
        collector.put("display", Objects.toString(data.collector(), ""));
        collection.set("collector", collector);
        node.set("collection", collection);

        node.put("receivedTime", Objects.toString(data.receivedAt(), ""));
        node.set("quality", FhirSupport.JSON.arrayNode().add(FhirSupport.concept(data.quality())));
        return node;
    }

    /**
     * Read a FHIR R4 Specimen, rejecting one without a patient, accession or order identifier.
     *
     * @param resource FHIR resource
     * @return the FHIR-boundary projection
     * @throws ApiException when the resource type or a required identifier is invalid
     */
    public static SpecimenData fromFhir(JsonNode resource) {
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new ApiException(422, ERROR_CODE, "resource is not a Specimen");
        }
        String patientId = FhirSupport.referenceId(resource, "subject", "Patient", ERROR_CODE);
        String accessionNo = FhirSupport.requiredText(resource.path("identifier").path(0),
                "value", ERROR_CODE);
        String orderId = FhirSupport.arrayReferenceId(resource.path("request"), 0,
                "ServiceRequest", ERROR_CODE);
        return new SpecimenData(FhirSupport.profile(resource), patientId,
                resource.path("id").asString(), accessionNo,
                resource.path("identifier").path(0).path("assigner").path("display").asString(),
                orderId,
                resource.path("type").path("coding").path(0).path("code").asString(),
                resource.path("collection").path("bodySite").path("coding").path(0).path("code").asString(),
                resource.path("collection").path("collector").path("display").asString(),
                resource.path("collection").path("collectedDateTime").asString(),
                resource.path("receivedTime").asString(),
                resource.path("quality").path(0).path("coding").path(0).path("code").asString(),
                resource.path("status").asString());
    }
}
