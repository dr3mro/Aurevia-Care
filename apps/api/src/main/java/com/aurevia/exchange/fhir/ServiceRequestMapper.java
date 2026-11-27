package com.aurevia.exchange.fhir;

import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.ServiceRequestData;
import com.aurevia.exchange.order.OrderView;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a released order to and from a FHIR R4 ServiceRequest.
 *
 * <p>The patient subject and the order identifier are mandatory on import: the mapper
 * rejects a resource that lacks either instead of inventing a local association. The
 * partner profile name is carried in {@code meta.profile} so a partner-specific profile
 * can be layered on without changing the exchange aggregate.</p>
 */
public final class ServiceRequestMapper {

    private static final String RESOURCE_TYPE = "ServiceRequest";
    private static final String ERROR_CODE = "INVALID_SERVICE_REQUEST";

    private ServiceRequestMapper() {
    }

    /**
     * Build the FHIR-boundary projection of a released order.
     *
     * @param order   released order aggregate
     * @param profile partner profile name
     * @return the projection to map to FHIR JSON
     */
    public static ServiceRequestData fromOrder(OrderView order, String profile) {
        return new ServiceRequestData(profile, order.patientId(), order.id(), order.encounterRef(),
                order.orderCode(), order.priority(), order.clinicalQuestion(), order.responsibleClinician(),
                order.authorizationId());
    }

    /**
     * Render the projection as a FHIR R4 ServiceRequest JSON resource.
     *
     * @param data projection to render
     * @return the FHIR resource
     */
    public static JsonNode toFhir(ServiceRequestData data) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("resourceType", RESOURCE_TYPE);
        node.put("id", Objects.toString(data.orderId(), ""));
        node.set("meta", FhirSupport.meta(data.profile()));
        node.put("status", "active");
        node.put("intent", "order");
        node.put("priority", Objects.toString(data.priority(), ""));
        node.set("subject", FhirSupport.reference("Patient", data.patientId()));

        ArrayNode encounters = FhirSupport.JSON.arrayNode();
        encounters.add(FhirSupport.reference("Encounter", data.encounterRef()));
        node.set("encounter", encounters);

        node.set("code", FhirSupport.concept(data.orderCode()));

        ObjectNode question = FhirSupport.JSON.objectNode();
        question.put("text", Objects.toString(data.clinicalQuestion(), ""));
        ArrayNode reasons = FhirSupport.JSON.arrayNode();
        reasons.add(question);
        node.set("reasonCode", reasons);

        node.set("requester", FhirSupport.reference("Practitioner", data.requester()));

        ArrayNode identifiers = FhirSupport.JSON.arrayNode();
        identifiers.add(FhirSupport.identifier(FhirSupport.ORDER_IDENTIFIER_SYSTEM, data.orderId()));
        identifiers.add(FhirSupport.identifier(FhirSupport.AUTHORIZATION_IDENTIFIER_SYSTEM,
                data.authorizationId()));
        node.set("identifier", identifiers);
        return node;
    }

    /**
     * Read a FHIR R4 ServiceRequest, rejecting one without a patient or order identifier.
     *
     * @param resource FHIR resource
     * @return the FHIR-boundary projection
     * @throws ApiException when the resource type, patient or order identifier is invalid
     */
    public static ServiceRequestData fromFhir(JsonNode resource) {
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new ApiException(422, ERROR_CODE, "resource is not a ServiceRequest");
        }
        String patientId = FhirSupport.referenceId(resource, "subject", "Patient", ERROR_CODE);
        String orderId = FhirSupport.requiredText(resource.path("identifier").path(0), "value", ERROR_CODE);
        String encounterRef = FhirSupport.optionalReferenceId(resource.path("encounter").path(0),
                "Encounter");
        String requester = FhirSupport.optionalReferenceId(resource.path("requester"), "Practitioner");
        return new ServiceRequestData(FhirSupport.profile(resource), patientId, orderId, encounterRef,
                resource.path("code").path("coding").path(0).path("code").asString(),
                resource.path("priority").asString(),
                resource.path("reasonCode").path(0).path("text").asString(),
                requester,
                resource.path("identifier").path(1).path("value").asString());
    }
}
