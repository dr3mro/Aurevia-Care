package com.aurevia.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WEB-06 unit tests for the fixed-field request-log JSON builder.
 *
 * <p>The formatter is the only place that decides what a request-log line may
 * contain. These tests prove the field list is exactly the five allowed fields,
 * that every value is written with the expected JSON type, and that strings are
 * escaped so no caller-controlled token can break out of the log line.</p>
 */
class RequestLogFormatterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emitsExactlyTheFiveAllowedFields() throws JacksonException {
        String json = RequestLogFormatter.toJson("111e1111-1111-1111-1111-111111111111", "GET",
                "/api/v1/patients/{id}", 200, 7);

        JsonNode node = mapper.readTree(json);
        Set<String> names = node.properties().stream().map(e -> e.getKey()).collect(Collectors.toSet());

        assertEquals(Set.of("correlationId", "method", "route", "status", "latencyMs"), names);
        assertEquals("111e1111-1111-1111-1111-111111111111", node.get("correlationId").asString());
        assertEquals("GET", node.get("method").asString());
        assertEquals("/api/v1/patients/{id}", node.get("route").asString());
        assertEquals(200, node.get("status").asInt());
        assertEquals(7, node.get("latencyMs").asLong());
    }

    @Test
    void escapesCallerControlledStringsSoTheLineStaysOneJsonObject() throws JacksonException {
        String hostile = "GET\"\n\\ZZ-TOKEN-MARKER";

        String json = RequestLogFormatter.toJson("corr", hostile, "/route", 500, 0);
        JsonNode node = mapper.readTree(json);

        assertEquals(hostile, node.get("method").asString());
        assertEquals(5, node.size());
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));
        assertFalse(json.contains("\n"));
    }
}
