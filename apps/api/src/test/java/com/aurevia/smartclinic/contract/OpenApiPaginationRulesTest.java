package com.aurevia.smartclinic.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * OPS-05/WEB-09 guard for the staff OpenAPI contract: every GET operation that
 * returns a list must share one cursor/limit query convention and one
 * next-cursor response field. Parses the committed contract with SnakeYAML and
 * runs without a Spring context or a database.
 */
class OpenApiPaginationRulesTest {

    /* The convention established by listFacilities, listStaff, listAppointments,
       listLabAccessions, listFinanceInvoices, listOrders and queryAuditEvents. */
    private static final String CURSOR = "cursor";
    private static final String LIMIT = "limit";
    private static final String NEXT_CURSOR = "nextCursor";
    private static final int LIMIT_MAXIMUM = 200;
    private static final String SCHEMA_PREFIX = "#/components/schemas/";
    private static final String PARAMETER_PREFIX = "#/components/parameters/";

    // listCatalogFamily still returns a bare array with no cursor/limit/nextCursor.
    // TODO: paginate catalog families in a follow-up story, then remove this exemption.
    private static final Set<String> ALLOWED = Set.of("listCatalogFamily");

    private static final String CURSOR_PARAMETER = String.join("\n",
            "        - name: cursor",
            "          in: query",
            "          required: false",
            "          schema: { type: string }",
            "");
    private static final String LIMIT_PARAMETER = String.join("\n",
            "        - name: limit",
            "          in: query",
            "          required: false",
            "          schema: { type: integer, minimum: 1, maximum: 200 }",
            "");
    private static final String LIMIT_SCHEMA = "schema: { type: integer, minimum: 1, maximum: 200 }";
    private static final String LIMIT_SCHEMA_NO_MAXIMUM = "schema: { type: integer, minimum: 1 }";
    private static final String LIMIT_SCHEMA_WRONG_MAXIMUM = "schema: { type: integer, minimum: 1, maximum: 50 }";
    private static final String NEXT_CURSOR_PROPERTY = "        nextCursor: { type: string, nullable: true }\n";
    private static final String PAGE_SCHEMA = "              schema: { $ref: '#/components/schemas/ThingPage' }";

    private static final String VALID_LIST_CONTRACT = String.join("\n",
            "openapi: 3.0.3",
            "paths:",
            "  /api/v1/things:",
            "    get:",
            "      operationId: listThings",
            "      parameters:",
            "        - name: cursor",
            "          in: query",
            "          required: false",
            "          schema: { type: string }",
            "        - name: limit",
            "          in: query",
            "          required: false",
            "          schema: { type: integer, minimum: 1, maximum: 200 }",
            "      responses:",
            "        '200':",
            "          content:",
            "            application/json:",
            "              schema: { $ref: '#/components/schemas/ThingPage' }",
            "components:",
            "  schemas:",
            "    ThingPage:",
            "      type: object",
            "      properties:",
            "        items: { type: array }",
            "        nextCursor: { type: string, nullable: true }",
            "");

    @Test
    void committedContractPassesEveryPaginationRule() throws IOException {
        Map<String, Object> document = parse(committedContract());
        List<String> listOperations = listGetOperationIds(document);
        assertEquals(Set.of("listFacilities", "queryAuditEvents", "listStaff", "listAppointments",
                "listLabAccessions", "listFinanceInvoices", "listOrders", "listCatalogFamily",
                "pollInstantEvents"),
                new HashSet<>(listOperations), "every list GET must be detected, got " + listOperations);
        assertEquals(List.of(), violations(document, true), "committed contract must obey every pagination rule");
    }

    @Test
    void theOnlyExemptionIsTheBareArrayCatalogFamilyList() throws IOException {
        Map<String, Object> document = parse(committedContract());
        List<String> raw = violations(document, false);
        assertEquals(3, raw.size(), raw.toString());
        assertTrue(raw.stream().allMatch(problem -> problem.startsWith("listCatalogFamily")), raw.toString());
        assertTrue(raw.stream().anyMatch(problem -> problem.contains("cursor")), raw.toString());
        assertTrue(raw.stream().anyMatch(problem -> problem.contains("limit")), raw.toString());
        assertTrue(raw.stream().anyMatch(problem -> problem.contains("nextCursor")), raw.toString());
    }

    @Test
    void aListOperationWithoutCursorFails() {
        List<String> problems = violations(parse(mutate(VALID_LIST_CONTRACT, CURSOR_PARAMETER, "")), true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("cursor"), problems.toString());
    }

    @Test
    void aListOperationWithoutLimitFails() {
        List<String> problems = violations(parse(mutate(VALID_LIST_CONTRACT, LIMIT_PARAMETER, "")), true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("limit"), problems.toString());
    }

    @Test
    void aListOperationWithUnboundedLimitFails() {
        String yaml = mutate(VALID_LIST_CONTRACT, LIMIT_SCHEMA, LIMIT_SCHEMA_NO_MAXIMUM);
        List<String> problems = violations(parse(yaml), true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("maximum"), problems.toString());
    }

    @Test
    void aListOperationWithTheWrongLimitMaximumFails() {
        String yaml = mutate(VALID_LIST_CONTRACT, LIMIT_SCHEMA, LIMIT_SCHEMA_WRONG_MAXIMUM);
        List<String> problems = violations(parse(yaml), true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("maximum must be 200"), problems.toString());
    }

    @Test
    void aListOperationWithoutNextCursorFails() {
        List<String> problems = violations(parse(mutate(VALID_LIST_CONTRACT, NEXT_CURSOR_PROPERTY, "")), true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("nextCursor"), problems.toString());
    }

    @Test
    void aGetOperationThatDoesNotReturnAListIsIgnored() {
        String yaml = mutate(VALID_LIST_CONTRACT, PAGE_SCHEMA, "              schema: { type: object }");
        assertEquals(List.of(), violations(parse(yaml), true));
    }

    /** Guards the synthetic fixtures against a no-op replacement and a vacuous green. */
    private static String mutate(String contract, String fragment, String replacement) {
        String mutated = contract.replace(fragment, replacement);
        assertNotEquals(contract, mutated, "fixture mutation did not change the contract");
        return mutated;
    }

    // --- contract model ---------------------------------------------------

    private static String committedContract() throws IOException {
        String basedir = System.getProperty("basedir", ".");
        Path path = Path.of(basedir).resolve("..").resolve("..").resolve("contracts").resolve("openapi.yaml")
                .normalize();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> parse(String yaml) {
        return asMap(new Yaml().load(yaml));
    }

    private static List<String> listGetOperationIds(Map<String, Object> document) {
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Object> path : asMap(document.get("paths")).entrySet()) {
            Map<String, Object> get = asMap(asMap(path.getValue()).get("get"));
            if (!get.isEmpty() && isListResponse(responseSchema(get))) {
                ids.add(String.valueOf(get.get("operationId")));
            }
        }
        return ids;
    }

    private static List<String> violations(Map<String, Object> document, boolean honorAllowList) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Object> path : asMap(document.get("paths")).entrySet()) {
            Map<String, Object> get = asMap(asMap(path.getValue()).get("get"));
            if (get.isEmpty() || !isListResponse(responseSchema(get))) {
                continue;
            }
            String operationId = String.valueOf(get.get("operationId"));
            if (honorAllowList && ALLOWED.contains(operationId)) {
                continue;
            }
            problems.addAll(violationsFor(operationId, get, document));
        }
        return problems;
    }

    private static List<String> violationsFor(String operationId, Map<String, Object> operation,
            Map<String, Object> document) {
        List<String> problems = new ArrayList<>();
        List<Map<String, Object>> query = queryParameters(operation, document);
        Map<String, Object> cursor = named(query, CURSOR);
        Map<String, Object> limit = named(query, LIMIT);
        if (cursor == null) {
            problems.add(operationId + " must declare query parameter '" + CURSOR + "'");
        }
        if (limit == null) {
            problems.add(operationId + " must declare query parameter '" + LIMIT + "'");
        } else {
            Object maximum = asMap(limit.get("schema")).get("maximum");
            if (maximum == null) {
                problems.add(operationId + " limit must declare a maximum");
            } else if (!Integer.valueOf(LIMIT_MAXIMUM).equals(maximum)) {
                problems.add(operationId + " limit maximum must be " + LIMIT_MAXIMUM);
            }
        }
        Map<String, Object> page = resolvedSchema(responseSchema(operation), document);
        if (!asMap(page.get("properties")).containsKey(NEXT_CURSOR)) {
            problems.add(operationId + " response must declare field '" + NEXT_CURSOR + "'");
        }
        return problems;
    }

    private static boolean isListResponse(Map<String, Object> schema) {
        if ("array".equals(schema.get("type"))) {
            return true;
        }
        Object ref = schema.get("$ref");
        if (!(ref instanceof String reference) || !reference.startsWith(SCHEMA_PREFIX)) {
            return false;
        }
        return reference.substring(SCHEMA_PREFIX.length()).endsWith("Page");
    }

    private static Map<String, Object> responseSchema(Map<String, Object> operation) {
        Map<String, Object> responses = asMap(operation.get("responses"));
        Map<String, Object> ok = asMap(responses.get("200"));
        Map<String, Object> content = asMap(ok.get("content"));
        return asMap(asMap(content.get("application/json")).get("schema"));
    }

    private static List<Map<String, Object>> queryParameters(Map<String, Object> operation,
            Map<String, Object> document) {
        List<Map<String, Object>> query = new ArrayList<>();
        for (Object raw : asList(operation.get("parameters"))) {
            Map<String, Object> parameter = resolvedParameter(asMap(raw), document);
            if ("query".equals(parameter.get("in"))) {
                query.add(parameter);
            }
        }
        return query;
    }

    private static Map<String, Object> resolvedParameter(Map<String, Object> parameter,
            Map<String, Object> document) {
        Object ref = parameter.get("$ref");
        if (ref instanceof String reference && reference.startsWith(PARAMETER_PREFIX)) {
            Map<String, Object> parameters = asMap(asMap(document.get("components")).get("parameters"));
            return asMap(parameters.get(reference.substring(PARAMETER_PREFIX.length())));
        }
        return parameter;
    }

    private static Map<String, Object> resolvedSchema(Map<String, Object> schema, Map<String, Object> document) {
        Object ref = schema.get("$ref");
        if (ref instanceof String reference && reference.startsWith(SCHEMA_PREFIX)) {
            Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
            return asMap(schemas.get(reference.substring(SCHEMA_PREFIX.length())));
        }
        return schema;
    }

    private static Map<String, Object> named(List<Map<String, Object>> parameters, String name) {
        for (Map<String, Object> parameter : parameters) {
            if (name.equals(parameter.get("name"))) {
                return parameter;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<Object> asList(Object value) {
        return value instanceof List<?> list ? new ArrayList<>(list) : List.of();
    }
}
