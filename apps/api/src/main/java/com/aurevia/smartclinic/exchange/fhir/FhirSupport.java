package com.aurevia.smartclinic.exchange.fhir;

import java.util.Objects;

import com.aurevia.smartclinic.api.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared FHIR R4 JSON primitives for the exchange and laboratory mappers (XCH-01, XCH-04, LAB-01, LAB-02).
 *
 * <p>All identifier checks live here so a mapper cannot quietly accept a resource without a
 * patient, accession or order identifier. A missing identifier is rejected with a stable
 * {@code 422} code rather than guessed from unrelated resource fields.</p>
 */
public final class FhirSupport {

    /** Factory for JSON nodes; Jackson is already on the classpath through Spring Web. */
    public static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** Synthetic code system used until a partner profile supplies its own. */
    public static final String CODE_SYSTEM = "urn:smartclinic:fhir:code";

    /** Synthetic identifier system for the issuer-scoped order identifier. */
    public static final String ORDER_IDENTIFIER_SYSTEM = "urn:smartclinic:fhir:identifier:order";

    /** Synthetic identifier system for the disclosure authorization. */
    public static final String AUTHORIZATION_IDENTIFIER_SYSTEM = "urn:smartclinic:fhir:identifier:authorization";

    /** UCUM system used by FHIR Quantity values. */
    public static final String UCUM_SYSTEM = "http://unitsofmeasure.org";

    private FhirSupport() {
    }

    /**
     * Build a FHIR Reference to a typed resource.
     *
     * @param type resource type such as {@code Patient}
     * @param id   opaque identifier, never a guessed value
     * @return a reference object node
     */
    public static ObjectNode reference(String type, String id) {
        ObjectNode node = JSON.objectNode();
        node.put("reference", type + "/" + Objects.toString(id, ""));
        return node;
    }

    /**
     * Build a FHIR Coding with a code from the synthetic partner code system.
     *
     * @param code code value
     * @return a coding object node
     */
    public static ObjectNode coding(String code) {
        ObjectNode node = JSON.objectNode();
        node.put("system", CODE_SYSTEM);
        node.put("code", Objects.toString(code, ""));
        return node;
    }

    /**
     * Build a typed FHIR Identifier for an issuer-scoped business identifier.
     *
     * @param system identifier namespace
     * @param value  identifier value
     * @return an identifier object node
     */
    public static ObjectNode identifier(String system, String value) {
        ObjectNode node = JSON.objectNode();
        node.put("system", system);
        node.put("value", Objects.toString(value, ""));
        return node;
    }

    /**
     * Build a FHIR CodeableConcept holding one synthetic code.
     *
     * @param code code value
     * @return a codeable concept object node
     */
    public static ObjectNode concept(String code) {
        ObjectNode node = JSON.objectNode();
        node.set("coding", JSON.arrayNode().add(coding(code)));
        return node;
    }

    /**
     * Build the {@code meta.profile} array that carries the partner profile name.
     *
     * @param profile partner profile name
     * @return a meta object node
     */
    public static ObjectNode meta(String profile) {
        ObjectNode meta = JSON.objectNode();
        ArrayNode profiles = JSON.arrayNode();
        profiles.add(Objects.toString(profile, ""));
        meta.set("profile", profiles);
        return meta;
    }

    /**
     * Read the partner profile name from a resource.
     *
     * @param resource FHIR resource
     * @return the first profile name, or an empty string when none was carried
     */
    public static String profile(JsonNode resource) {
        return resource.path("meta").path("profile").path(0).asString();
    }

    /**
     * Read a required plain text field, rejecting a missing or blank value.
     *
     * @param node      parent resource
     * @param field     field name
     * @param errorCode stable error code for the enclosing resource
     * @return the non-blank field value
     * @throws ApiException when the value is missing or blank
     */
    public static String requiredText(JsonNode node, String field, String errorCode) {
        String value = node.path(field).asString();
        if (value.isBlank()) {
            throw new ApiException(422, errorCode, "missing " + field + " identifier");
        }
        return value;
    }

    /**
     * Read a required typed reference from a single-valued field.
     *
     * @param holder    object holding the reference field
     * @param field     reference field name
     * @param type      expected resource type
     * @param errorCode stable error code for the enclosing resource
     * @return the referenced identifier
     * @throws ApiException when the reference is absent or has a different type
     */
    public static String referenceId(JsonNode holder, String field, String type, String errorCode) {
        return parseReference(holder.path(field).path("reference").asString(), type, errorCode);
    }

    /**
     * Read an optional typed reference, returning an empty id for a missing or wrong-typed value.
     *
     * @param holder reference object holding a {@code reference} field
     * @param type   expected resource type
     * @return the referenced identifier, or an empty string when absent
     */
    public static String optionalReferenceId(JsonNode holder, String type) {
        String value = holder.path("reference").asString();
        String prefix = type + "/";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : "";
    }

    /**
     * Read a required typed reference from an array-valued field.
     *
     * @param array     array of reference objects
     * @param index     reference position
     * @param type      expected resource type
     * @param errorCode stable error code for the enclosing resource
     * @return the referenced identifier
     * @throws ApiException when the reference is absent or has a different type
     */
    public static String arrayReferenceId(JsonNode array, int index, String type, String errorCode) {
        return parseReference(array.path(index).path("reference").asString(), type, errorCode);
    }

    /**
     * Parse a {@code Type/id} reference without guessing an id from a malformed value.
     *
     * @param value     reference text
     * @param type      expected resource type
     * @param errorCode stable error code for the enclosing resource
     * @return the identifier after the type prefix
     * @throws ApiException when the prefix or identifier is missing
     */
    private static String parseReference(String value, String type, String errorCode) {
        String prefix = type + "/";
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            throw new ApiException(422, errorCode, "missing " + type + " identifier");
        }
        return value.substring(prefix.length());
    }
}
