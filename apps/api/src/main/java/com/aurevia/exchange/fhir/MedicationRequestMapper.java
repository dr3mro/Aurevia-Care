package com.aurevia.exchange.fhir;

import java.math.BigDecimal;
import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.MedicationRequestData;
import com.aurevia.prescription.PrescriptionContract.Draft;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a prescription draft to and from a FHIR R4 MedicationRequest.
 *
 * <p>Only the medication the clinician selected crosses this boundary: the catalog entry
 * id is the drug code and the dose, route, frequency, quantity, prescriber and destination
 * describe that selection. Suggestion and allergy data stay inside the prescription domain
 * and are never exported as part of the order.</p>
 *
 * <p>The patient subject and draft id are mandatory on import; a resource without either
 * is rejected rather than guessed from the medication or requester fields.</p>
 */
public final class MedicationRequestMapper {

    private static final String RESOURCE_TYPE = "MedicationRequest";
    private static final String ERROR_CODE = "INVALID_MEDICATION_REQUEST";

    private MedicationRequestMapper() {
    }

    /**
     * Build the FHIR-boundary projection of a prescription draft.
     *
     * @param draft   prescription draft carrying the clinician's selection
     * @param profile partner profile name
     * @return the projection to map to FHIR JSON
     */
    public static MedicationRequestData fromDraft(Draft draft, String profile) {
        return new MedicationRequestData(profile, Objects.toString(draft.patientId(), ""),
                Objects.toString(draft.id(), ""), Objects.toString(draft.selectedEntryId(), ""),
                Objects.toString(draft.doseValue(), ""), Objects.toString(draft.doseUnit(), ""),
                Objects.toString(draft.route(), ""), Objects.toString(draft.frequency(), ""),
                Objects.toString(draft.quantity(), ""), Objects.toString(draft.quantityUnit(), ""),
                Objects.toString(draft.prescriberSubject(), ""), Objects.toString(draft.destination(), ""));
    }

    /**
     * Render the projection as a FHIR R4 MedicationRequest JSON resource.
     *
     * @param data projection to render
     * @return the FHIR resource
     */
    public static JsonNode toFhir(MedicationRequestData data) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("resourceType", RESOURCE_TYPE);
        node.put("id", Objects.toString(data.requestId(), ""));
        node.set("meta", FhirSupport.meta(data.profile()));
        node.put("status", "active");
        node.put("intent", "order");
        node.set("subject", FhirSupport.reference("Patient", data.patientId()));
        node.set("medicationCodeableConcept", FhirSupport.concept(data.drugCode()));

        ObjectNode requester = FhirSupport.JSON.objectNode();
        requester.put("reference", "Practitioner/" + Objects.toString(data.prescriber(), ""));
        node.set("requester", requester);

        ObjectNode doseQuantity = FhirSupport.JSON.objectNode();
        doseQuantity.put("value", decimal(data.doseValue(), "dose"));
        doseQuantity.put("unit", Objects.toString(data.doseUnit(), ""));
        doseQuantity.put("system", FhirSupport.UCUM_SYSTEM);
        doseQuantity.put("code", Objects.toString(data.doseUnit(), ""));
        ObjectNode doseAndRate = FhirSupport.JSON.objectNode();
        doseAndRate.set("doseQuantity", doseQuantity);

        ObjectNode route = FhirSupport.JSON.objectNode();
        route.set("coding", FhirSupport.JSON.arrayNode().add(FhirSupport.coding(data.route())));
        ObjectNode timingCode = FhirSupport.JSON.objectNode();
        timingCode.set("coding", FhirSupport.JSON.arrayNode().add(FhirSupport.coding(data.frequency())));
        ObjectNode timing = FhirSupport.JSON.objectNode();
        timing.set("code", timingCode);

        ObjectNode dosage = FhirSupport.JSON.objectNode();
        dosage.set("timing", timing);
        dosage.set("route", route);
        dosage.set("doseAndRate", FhirSupport.JSON.arrayNode().add(doseAndRate));
        node.set("dosageInstruction", FhirSupport.JSON.arrayNode().add(dosage));

        ObjectNode quantity = FhirSupport.JSON.objectNode();
        quantity.put("value", decimal(data.quantity(), "quantity"));
        quantity.put("unit", Objects.toString(data.quantityUnit(), ""));
        ObjectNode dispenseRequest = FhirSupport.JSON.objectNode();
        dispenseRequest.set("quantity", quantity);
        node.set("dispenseRequest", dispenseRequest);

        ObjectNode destination = FhirSupport.JSON.objectNode();
        destination.put("url", FhirSupport.DESTINATION_EXTENSION);
        destination.put("valueString", Objects.toString(data.destination(), ""));
        node.set("extension", FhirSupport.JSON.arrayNode().add(destination));
        return node;
    }

    /**
     * Read a FHIR R4 MedicationRequest, rejecting one without a patient or draft identifier.
     *
     * @param resource FHIR resource
     * @return the FHIR-boundary projection
     * @throws ApiException when the resource type or a required identifier is invalid
     */
    public static MedicationRequestData fromFhir(JsonNode resource) {
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new ApiException(422, ERROR_CODE, "resource is not a MedicationRequest");
        }
        String patientId = FhirSupport.referenceId(resource, "subject", "Patient", ERROR_CODE);
        String requestId = FhirSupport.requiredText(resource, "id", ERROR_CODE);
        String prescriber = FhirSupport.optionalReferenceId(resource.path("requester"), "Practitioner");

        JsonNode dosage = resource.path("dosageInstruction").path(0);
        JsonNode doseQuantity = dosage.path("doseAndRate").path(0).path("doseQuantity");
        String doseValue = doseQuantity.path("value").decimalValue().toPlainString();
        String doseUnit = doseQuantity.path("unit").asString();
        String route = dosage.path("route").path("coding").path(0).path("code").asString();
        String frequency = dosage.path("timing").path("code").path("coding").path(0).path("code").asString();
        JsonNode dispenseQuantity = resource.path("dispenseRequest").path("quantity");
        String quantity = dispenseQuantity.path("value").decimalValue().toPlainString();
        String quantityUnit = dispenseQuantity.path("unit").asString();

        String destination = "";
        for (JsonNode extension : resource.path("extension")) {
            if (FhirSupport.DESTINATION_EXTENSION.equals(extension.path("url").asString())) {
                destination = extension.path("valueString").asString();
            }
        }

        String drugCode = resource.path("medicationCodeableConcept").path("coding").path(0).path("code").asString();
        return new MedicationRequestData(FhirSupport.profile(resource), patientId, requestId, drugCode,
                doseValue, doseUnit, route, frequency, quantity, quantityUnit, prescriber, destination);
    }

    /**
     * Parse a decimal quantity as a FHIR number, rejecting non-numeric domain values.
     *
     * @param value domain quantity text
     * @param field field name used in the structured error
     * @return the numeric value to write as a FHIR {@code Quantity.value}
     * @throws ApiException when the value is not a valid decimal
     */
    private static BigDecimal decimal(String value, String field) {
        try {
            return new BigDecimal(Objects.toString(value, ""));
        } catch (NumberFormatException exception) {
            throw new ApiException(422, ERROR_CODE, "invalid " + field + " quantity");
        }
    }
}
