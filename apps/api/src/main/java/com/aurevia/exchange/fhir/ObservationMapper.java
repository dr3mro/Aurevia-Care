package com.aurevia.exchange.fhir;

import java.math.BigDecimal;
import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.fhir.FhirModel.FhirReferenceRange;
import com.aurevia.exchange.fhir.FhirModel.ObservationData;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabTypes.Qualifier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a laboratory observation to and from a FHIR R4 Observation (LAB-02, XCH-04).
 *
 * <p>The four observation kinds keep their own FHIR value field so a less-than qualifier,
 * its unit, an abnormal flag and an approved reference range survive a round trip. A
 * resource without a patient or specimen identifier is rejected; no association is guessed
 * from the observation code or value.</p>
 */
public final class ObservationMapper {

    private static final String RESOURCE_TYPE = "Observation";
    private static final String ERROR_CODE = "INVALID_OBSERVATION";
    private static final String INSTRUMENT_EXTENSION = "urn:aurevia:fhir:instrument";
    private static final String VERIFIER_EXTENSION = "urn:aurevia:fhir:verifier";

    private ObservationMapper() {
    }

    /**
     * Build the FHIR-boundary projection of a laboratory observation.
     *
     * @param observation observation from the laboratory kernel
     * @param id          observation identifier used by report result references
     * @param patientId   verified patient identifier
     * @param specimenId  specimen carrying the accession
     * @param status      FHIR observation status
     * @param profile     partner profile name
     * @param range       approved reference range, when supplied
     * @return the projection to map to FHIR JSON
     */
    public static ObservationData fromObservation(LabObservation observation, String id, String patientId,
                                                  String specimenId, String status, String profile,
                                                  FhirReferenceRange range) {
        String comparator = observation.qualifier() == Qualifier.NONE ? "" : observation.qualifier().symbol();
        return new ObservationData(id, profile, patientId, specimenId, observation.code(), status,
                observation.kind().wire(), observation.presence().wire(), observation.value(), comparator,
                Objects.toString(observation.unit(), ""), Objects.toString(observation.textValue(), ""),
                Objects.toString(observation.codedValue(), ""), Objects.toString(observation.method(), ""),
                observation.abnormalFlag().wire(), Objects.toString(observation.instrument(), ""),
                Objects.toString(observation.verifier(), ""), range);
    }

    /**
     * Render the projection as a FHIR R4 Observation JSON resource.
     *
     * @param data projection to render
     * @return the FHIR resource
     */
    public static JsonNode toFhir(ObservationData data) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("resourceType", RESOURCE_TYPE);
        node.put("id", Objects.toString(data.id(), ""));
        node.set("meta", FhirSupport.meta(data.profile()));
        node.put("status", Objects.toString(data.status(), ""));
        node.set("code", FhirSupport.concept(data.code()));
        node.set("subject", FhirSupport.reference("Patient", data.patientId()));
        node.set("specimen", FhirSupport.reference("Specimen", data.specimenId()));
        node.set("extension", extensions(data));

        if ("present".equals(data.presence())) {
            writePresentValue(node, data);
        } else {
            String code = "absent".equals(data.presence()) ? "not-performed" : "unknown";
            ObjectNode reason = FhirSupport.JSON.objectNode();
            reason.set("coding", FhirSupport.JSON.arrayNode().add(FhirSupport.coding(code)));
            node.set("dataAbsentReason", reason);
        }

        FhirReferenceRange range = data.referenceRange();
        if (range != null) {
            ObjectNode low = FhirSupport.JSON.objectNode();
            low.put("value", range.low());
            low.put("unit", Objects.toString(range.unit(), ""));
            low.put("system", FhirSupport.UCUM_SYSTEM);
            low.put("code", Objects.toString(range.unit(), ""));
            ObjectNode high = FhirSupport.JSON.objectNode();
            high.put("value", range.high());
            high.put("unit", Objects.toString(range.unit(), ""));
            high.put("system", FhirSupport.UCUM_SYSTEM);
            high.put("code", Objects.toString(range.unit(), ""));
            ObjectNode referenceRange = FhirSupport.JSON.objectNode();
            referenceRange.set("low", low);
            referenceRange.set("high", high);
            node.set("referenceRange", FhirSupport.JSON.arrayNode().add(referenceRange));
        }

        node.set("interpretation", FhirSupport.JSON.arrayNode()
                .add(FhirSupport.coding(data.abnormalFlag())));
        ObjectNode method = FhirSupport.JSON.objectNode();
        method.put("text", Objects.toString(data.method(), ""));
        node.set("method", method);
        return node;
    }

    /**
     * Read a FHIR R4 Observation, rejecting one without a patient or specimen identifier.
     *
     * @param resource FHIR resource
     * @return the FHIR-boundary projection
     * @throws ApiException when the resource type or a required identifier is invalid
     */
    public static ObservationData fromFhir(JsonNode resource) {
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new ApiException(422, ERROR_CODE, "resource is not an Observation");
        }
        String patientId = FhirSupport.referenceId(resource, "subject", "Patient", ERROR_CODE);
        String specimenId = FhirSupport.referenceId(resource, "specimen", "Specimen", ERROR_CODE);
        String instrument = "";
        String verifier = "";
        for (JsonNode extension : resource.path("extension")) {
            String url = extension.path("url").asString();
            if (INSTRUMENT_EXTENSION.equals(url)) {
                instrument = extension.path("valueString").asString();
            } else if (VERIFIER_EXTENSION.equals(url)) {
                verifier = extension.path("valueString").asString();
            }
        }

        String presence = "present";
        String kind = "numeric";
        BigDecimal value = null;
        String comparator = "";
        String unit = "";
        String textValue = "";
        String codedValue = "";
        if (resource.has("dataAbsentReason")) {
            String code = resource.path("dataAbsentReason").path("coding").path(0).path("code").asString();
            presence = "not-performed".equals(code) ? "absent" : "unknown";
        } else if (resource.has("valueQuantity")) {
            JsonNode quantity = resource.path("valueQuantity");
            comparator = quantity.path("comparator").asString();
            kind = comparator.isEmpty() ? "numeric" : "qualified_numeric";
            unit = quantity.path("unit").asString();
            value = quantity.path("value").decimalValue();
        } else if (resource.has("valueString")) {
            kind = "text";
            textValue = resource.path("valueString").asString();
        } else {
            kind = "coded";
            codedValue = resource.path("valueCodeableConcept").path("coding").path(0).path("code").asString();
        }

        FhirReferenceRange range = null;
        if (resource.has("referenceRange")) {
            JsonNode node = resource.path("referenceRange").path(0);
            range = new FhirReferenceRange(node.path("low").path("value").decimalValue(),
                    node.path("high").path("value").decimalValue(),
                    node.path("low").path("unit").asString());
        }

        return new ObservationData(resource.path("id").asString(), FhirSupport.profile(resource),
                patientId, specimenId,
                resource.path("code").path("coding").path(0).path("code").asString(),
                resource.path("status").asString(), kind, presence, value, comparator, unit,
                textValue, codedValue,
                resource.path("method").path("text").asString(),
                resource.path("interpretation").path(0).path("code").asString(),
                instrument, verifier, range);
    }

    private static void writePresentValue(ObjectNode node, ObservationData data) {
        String kind = Objects.toString(data.kind(), "");
        if ("numeric".equals(kind) || "qualified_numeric".equals(kind)) {
            ObjectNode quantity = FhirSupport.JSON.objectNode();
            quantity.put("value", data.value());
            String comparator = Objects.toString(data.comparator(), "");
            if (!comparator.isEmpty()) {
                quantity.put("comparator", comparator);
            }
            quantity.put("unit", Objects.toString(data.unit(), ""));
            quantity.put("system", FhirSupport.UCUM_SYSTEM);
            quantity.put("code", Objects.toString(data.unit(), ""));
            node.set("valueQuantity", quantity);
        } else if ("text".equals(kind)) {
            node.put("valueString", Objects.toString(data.textValue(), ""));
        } else {
            ObjectNode concept = FhirSupport.JSON.objectNode();
            concept.set("coding", FhirSupport.JSON.arrayNode().add(FhirSupport.coding(data.codedValue())));
            node.set("valueCodeableConcept", concept);
        }
    }

    private static ArrayNode extensions(ObservationData data) {
        ArrayNode extensions = FhirSupport.JSON.arrayNode();
        ObjectNode instrument = FhirSupport.JSON.objectNode();
        instrument.put("url", INSTRUMENT_EXTENSION);
        instrument.put("valueString", Objects.toString(data.instrument(), ""));
        extensions.add(instrument);
        ObjectNode verifier = FhirSupport.JSON.objectNode();
        verifier.put("url", VERIFIER_EXTENSION);
        verifier.put("valueString", Objects.toString(data.verifier(), ""));
        extensions.add(verifier);
        return extensions;
    }
}
