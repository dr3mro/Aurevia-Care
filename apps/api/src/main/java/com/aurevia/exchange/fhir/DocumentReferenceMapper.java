package com.aurevia.exchange.fhir;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.exchange.fhir.FhirModel.DocumentReferenceData;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps a retained original document to and from a FHIR R4 DocumentReference (CLN-07).
 *
 * <p>The retained SHA-256 digest is carried as an attachment checksum, the immutable
 * document version as {@code meta.versionId}, and the void state as the FHIR document
 * status: a voided association maps to {@code entered-in-error} and never to
 * {@code current}. The recorded void reason and instant survive the round trip as
 * extensions so the historical decision remains auditable.</p>
 */
public final class DocumentReferenceMapper {

    private static final String RESOURCE_TYPE = "DocumentReference";
    private static final String ERROR_CODE = "INVALID_DOCUMENT_REFERENCE";

    private DocumentReferenceMapper() {
    }

    /**
     * Build the FHIR-boundary projection of a retained document association.
     *
     * @param association retained original association
     * @param profile     partner profile name
     * @return the projection to map to FHIR JSON
     */
    public static DocumentReferenceData fromAssociation(DocumentAssoc association, String profile) {
        return new DocumentReferenceData(profile, Objects.toString(association.subjectId(), ""),
                Objects.toString(association.id(), ""), Objects.toString(association.sha256(), ""),
                association.version(), Objects.toString(association.status(), ""),
                Objects.toString(association.voidReason(), ""),
                Objects.toString(association.voidedAt(), ""),
                Objects.toString(association.mediaType(), ""));
    }

    /**
     * Render the projection as a FHIR R4 DocumentReference JSON resource.
     *
     * @param data projection to render
     * @return the FHIR resource
     */
    public static JsonNode toFhir(DocumentReferenceData data) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("resourceType", RESOURCE_TYPE);
        node.put("id", Objects.toString(data.documentId(), ""));
        ObjectNode meta = FhirSupport.meta(data.profile());
        meta.put("versionId", Integer.toString(data.revision()));
        node.set("meta", meta);

        ArrayNode identifiers = FhirSupport.JSON.arrayNode();
        identifiers.add(FhirSupport.identifier(FhirSupport.DOCUMENT_IDENTIFIER_SYSTEM, data.documentId()));
        node.set("identifier", identifiers);

        boolean voided = "voided".equals(data.status());
        node.put("status", voided ? "entered-in-error" : "current");
        node.set("subject", FhirSupport.reference("Patient", data.patientId()));

        ArrayNode extensions = FhirSupport.JSON.arrayNode();
        ObjectNode reason = FhirSupport.JSON.objectNode();
        reason.put("url", FhirSupport.VOID_REASON_EXTENSION);
        reason.put("valueString", Objects.toString(data.voidReason(), ""));
        extensions.add(reason);
        ObjectNode voidedAt = FhirSupport.JSON.objectNode();
        voidedAt.put("url", FhirSupport.VOIDED_AT_EXTENSION);
        voidedAt.put("valueString", Objects.toString(data.voidedAt(), ""));
        extensions.add(voidedAt);
        node.set("extension", extensions);

        ObjectNode attachment = FhirSupport.JSON.objectNode();
        attachment.put("contentType", Objects.toString(data.mediaType(), ""));
        attachment.put("checksum", Base64.getEncoder().encodeToString(
                HexFormat.of().parseHex(data.checksum())));
        ObjectNode content = FhirSupport.JSON.objectNode();
        content.set("attachment", attachment);
        node.set("content", FhirSupport.JSON.arrayNode().add(content));
        return node;
    }

    /**
     * Read a FHIR R4 DocumentReference, rejecting one without a patient or document identifier.
     *
     * @param resource FHIR resource
     * @return the FHIR-boundary projection
     * @throws ApiException when the resource type or a required identifier is invalid
     */
    public static DocumentReferenceData fromFhir(JsonNode resource) {
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new ApiException(422, ERROR_CODE, "resource is not a DocumentReference");
        }
        String patientId = FhirSupport.referenceId(resource, "subject", "Patient", ERROR_CODE);
        String documentId = FhirSupport.identifierValue(resource, FhirSupport.DOCUMENT_IDENTIFIER_SYSTEM,
                ERROR_CODE);
        String status = "entered-in-error".equals(resource.path("status").asString()) ? "voided" : "active";

        String voidReason = "";
        String voidedAt = "";
        for (JsonNode extension : resource.path("extension")) {
            String url = extension.path("url").asString();
            String value = extension.path("valueString").asString();
            if (FhirSupport.VOID_REASON_EXTENSION.equals(url)) {
                voidReason = value;
            }
            if (FhirSupport.VOIDED_AT_EXTENSION.equals(url)) {
                voidedAt = value;
            }
        }

        JsonNode attachment = resource.path("content").path(0).path("attachment");
        String checksum = HexFormat.of().formatHex(Base64.getDecoder()
                .decode(attachment.path("checksum").asString()));
        return new DocumentReferenceData(FhirSupport.profile(resource), patientId, documentId, checksum,
                resource.path("meta").path("versionId").asInt(), status, voidReason, voidedAt,
                attachment.path("contentType").asString());
    }
}
