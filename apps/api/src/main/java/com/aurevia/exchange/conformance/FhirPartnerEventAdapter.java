package com.aurevia.exchange.conformance;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.aurevia.exchange.fhir.FhirSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FHIR R4 conformance wire adapter for synthetic partner events.
 *
 * <p>The event is carried as a conformance {@code Observation} built with the
 * same JSON primitives as the exchange FHIR mappers. The immutable event
 * identity, sender, aggregate, revision, identifier and payload are preserved
 * in extensions so a fixture replay through this adapter reaches the same
 * ledger outcome as a direct apply (ARC-05, XCH-04).</p>
 */
public final class FhirPartnerEventAdapter implements PartnerEventAdapter {

    private static final String PROFILE = "synthetic-partner-conformance-r4";
    private static final String RESOURCE_TYPE = "Observation";

    @Override
    public String name() {
        return "fhir";
    }

    @Override
    public String encode(PartnerEvent event) {
        ObjectNode resource = FhirSupport.JSON.objectNode();
        resource.put("resourceType", RESOURCE_TYPE);
        resource.put("id", event.eventId());
        resource.set("meta", FhirSupport.meta(PROFILE));
        resource.set("code", FhirSupport.concept(event.kind().wire()));
        resource.set("subject", FhirSupport.reference("Patient", event.aggregateKey()));
        ArrayNode extensions = FhirSupport.JSON.arrayNode();
        extensions.add(extension("sender", event.sender()));
        extensions.add(extension("aggregate-key", event.aggregateKey()));
        extensions.add(extension("revision", Long.toString(event.revision())));
        extensions.add(extension("identifier", event.identifier()));
        extensions.add(extension("unit", event.unit()));
        extensions.add(extension("payload", event.payload()));
        resource.set("extension", extensions);
        return resource.toString();
    }

    @Override
    public PartnerEvent decode(String wire) {
        JsonNode resource = new ObjectMapper().readTree(wire);
        if (!RESOURCE_TYPE.equals(resource.path("resourceType").asString())) {
            throw new IllegalArgumentException("not a conformance Observation");
        }
        String kind = resource.path("code").path("coding").path(0).path("code").asString();
        Map<String, String> fields = new HashMap<>();
        for (JsonNode extension : resource.path("extension")) {
            fields.put(extension.path("url").asString(), extension.path("valueString").asString());
        }
        String unit = fields.get("unit");
        if ("".equals(unit)) {
            unit = null;
        }
        String identifier = fields.get("identifier");
        if ("".equals(identifier)) {
            identifier = null;
        }
        return new PartnerEvent(resource.path("id").asString(), fields.get("sender"),
                EventKind.from(kind), fields.get("aggregate-key"),
                Long.parseLong(fields.get("revision")), identifier, unit,
                fields.get("payload"));
    }

    private static ObjectNode extension(String url, String value) {
        ObjectNode node = FhirSupport.JSON.objectNode();
        node.put("url", url);
        node.put("valueString", Objects.toString(value, ""));
        return node;
    }
}
