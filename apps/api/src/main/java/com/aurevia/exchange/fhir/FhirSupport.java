package com.aurevia.exchange.fhir;

import java.util.Objects;

import com.aurevia.api.ApiException;

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
    public static final String CODE_SYSTEM = "urn:aurevia:fhir:code";

    /** Synthetic identifier system for the issuer-scoped order identifier. */
    public static final String ORDER_IDENTIFIER_SYSTEM = "urn:aurevia:fhir:identifier:order";

    /** Synthetic identifier system for the disclosure authorization. */
    public static final String AUTHORIZATION_IDENTIFIER_SYSTEM = "urn:aurevia:fhir:identifier:authorization";

    /** UCUM system used by FHIR Quantity values. */
    public static final String UCUM_SYSTEM = "http://unitsofmeasure.org";

    /** Synthetic identifier system for the DICOM Study Instance UID (RAD-01). */
    public static final String STUDY_UID_IDENTIFIER_SYSTEM = "urn:aurevia:fhir:identifier:study-instance-uid";

    /** Synthetic identifier system for an issuer-scoped imaging accession (RAD-01). */
    public static final String ACCESSION_IDENTIFIER_SYSTEM = "urn:aurevia:fhir:identifier:accession";

    /** Synthetic identifier system for a retained document identifier (CLN-07). */
    public static final String DOCUMENT_IDENTIFIER_SYSTEM = "urn:aurevia:fhir:identifier:document";

    /** Synthetic code system for DICOM imaging modality codes (RAD-01). */
    public static final String DICOM_MODALITY_SYSTEM = "urn:aurevia:fhir:dicom:modality";

    /** Extension carrying the recorded void reason of a document (CLN-07). */
    public static final String VOID_REASON_EXTENSION = "urn:aurevia:fhir:void-reason";

    /** Extension carrying the instant a document was voided (CLN-07). */
    public static final String VOIDED_AT_EXTENSION = "urn:aurevia:fhir:voided-at";

    /** Extension carrying the intended pharmacy destination of a medication request (RX-01). */
    public static final String DESTINATION_EXTENSION = "urn:aurevia:fhir:medication-destination";

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
     * Read a required identifier value by its declared system, independent of array order.
     *
     * <p>FHIR identifier arrays are unordered and may carry partner-specific extras. Matching
     * on the system rather than on a fixed position keeps a swapped or augmented array from
     * silently exchanging two business identifiers, one of which may feed a safety check.</p>
     *
     * @param resource  FHIR resource holding an {@code identifier} array
     * @param system    identifier system to match exactly
     * @param errorCode stable error code for the enclosing resource
     * @return the non-blank value of the identifier with that system
     * @throws ApiException when no identifier with the system carries a non-blank value
     */
    public static String identifierValue(JsonNode resource, String system, String errorCode) {
        String value = "";
        for (JsonNode identifier : resource.path("identifier")) {
            if (system.equals(identifier.path("system").asString())) {
                value = identifier.path("value").asString();
                break;
            }
        }
        if (value.isBlank()) {
            throw new ApiException(422, errorCode, "missing " + system + " identifier");
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
