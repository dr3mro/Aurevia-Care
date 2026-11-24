package com.aurevia.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Checks the WEB-04 and UX-01 error-response rules against the committed staff API contract.
 *
 * <p>The contract is data, so the rules are executable: every 4xx/5xx body that the contract
 * documents must be the one shared error schema, that schema must carry a stable machine code
 * and a correlation id, and no error response may expose personal data or a stack trace. The
 * production counterpart is {@code com.aurevia.api.ApiErrorHandler}, which renders
 * one {@code {code, message, correlationId}} body for every protected route.</p>
 *
 * <p>A response that declares no body is not a divergent shape: the contract simply leaves the
 * body undocumented, so this test does not claim to verify the runtime shape of such responses
 * (a 401, for example, is normally emitted by the security filter chain). The rules reject any
 * response that does declare a different body, and require every operation to document the
 * shared body at least once so a silently empty contract cannot pass.</p>
 */
final class OpenApiErrorRulesTest {

    private static final String SHARED_ERROR_SCHEMA = "Error";
    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final String RESPONSE_REF_PREFIX = "#/components/responses/";
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");

    /**
     * Operations allowed to keep a known error-text violation until the contract is corrected.
     */
    private static final Set<String> PATIENT_IDENTIFIER_TEXT_ALLOW_LIST = Set.of(
            // TODO WEB-04: listFinanceInvoices echoes the "patientId" filter name in its 422
            //  description; remove this entry when that response stops naming the request field.
            "listFinanceInvoices");

    /**
     * A pattern is either restricted to examples (names appear in prose status text) or applied
     * to every piece of error text the contract exposes.
     *
     * @param label       violation label used in assertion messages
     * @param pattern     the sensitive-value pattern
     * @param examplesOnly whether prose descriptions are out of scope for this pattern
     */
    private record SensitiveText(String label, Pattern pattern, boolean examplesOnly) {
    }

    private static final List<SensitiveText> SENSITIVE_TEXT = List.of(
            new SensitiveText("person name",
                    Pattern.compile("\\b[A-Z][a-z]{2,}\\s+[A-Z][a-z]{2,}\\b"), true),
            new SensitiveText("e-mail address",
                    Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), false),
            new SensitiveText("patient identifier",
                    Pattern.compile("(?i)\\b(?:patient[_ -]?id|mrn|pat-\\d+)\\b"), false),
            new SensitiveText("stack trace",
                    Pattern.compile("java\\.|\\bat\\s+[A-Za-z_$][\\w.$]*\\(|StackOverflowError"), false));

    /**
     * A small contract that satisfies all three rules. Each failure test changes exactly one
     * thing in this fixture, so the other two rules still pass and a failure is unambiguous.
     */
    private static final String VALID_CONTRACT = """
            openapi: 3.0.3
            paths:
              /things:
                get:
                  operationId: listThings
                  responses:
                    '200':
                      description: ok
                    '403': { $ref: '#/components/responses/Forbidden' }
                    '422':
                      description: invalid input
            components:
              responses:
                Forbidden:
                  description: forbidden
                  content:
                    application/json:
                      schema: { $ref: '#/components/schemas/Error' }
              schemas:
                Error:
                  type: object
                  properties:
                    code: { type: string }
                    message: { type: string }
                    correlationId: { type: string }
            """;

    private static final String ERROR_BODY_422 = """
                    '422':
                      description: invalid input
            """;

    @Test
    void committedContractSatisfiesEveryErrorRule() throws IOException {
        Map<String, Object> contract = parse(readCommittedContract());
        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void aContractWithoutOperationsFailsTheSharedSchemaRule() {
        Map<String, Object> contract = parse("""
                openapi: 3.0.3
                paths: {}
                components:
                  schemas:
                    Error:
                      type: object
                      properties:
                        code: { type: string }
                        correlationId: { type: string }
                """);

        assertViolation(() -> assertSingleSharedErrorSchema(contract), "no operations");
    }

    @Test
    void aMissingSharedErrorSchemaFailsOnlyTheSchemaShapeRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace("    Error:\n", "    Other:\n"));

        assertSingleSharedErrorSchema(contract);
        assertViolation(() -> assertErrorSchemaShape(contract), "components.schemas.Error is missing");
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void aDifferentErrorBodyFailsOnlyTheSharedSchemaRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(ERROR_BODY_422, """
                    '422':
                      description: invalid input
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/LegacyError' }
            """));

        assertViolation(() -> assertSingleSharedErrorSchema(contract), "LegacyError");
        assertErrorSchemaShape(contract);
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void aFiveHundredWithADifferentBodyFailsTheSharedSchemaRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(ERROR_BODY_422, """
                    '422':
                      description: invalid input
                    '500':
                      description: request could not be processed
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/ServerError' }
            """));

        assertViolation(() -> assertSingleSharedErrorSchema(contract), "ServerError");
        assertErrorSchemaShape(contract);
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void anInlineErrorBodyFailsTheSharedSchemaRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(ERROR_BODY_422, """
                    '422':
                      description: invalid input
                      content:
                        application/json:
                          schema: { type: object }
            """));

        assertViolation(() -> assertSingleSharedErrorSchema(contract), "inline body");
    }

    @Test
    void anOperationWithoutADocumentedErrorBodyFailsTheSharedSchemaRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        '403': { $ref: '#/components/responses/Forbidden' }\n", ""));

        assertViolation(() -> assertSingleSharedErrorSchema(contract), "documents no error response");
    }

    @Test
    void aNonStringMachineCodeFailsOnlyTheSchemaShapeRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        code: { type: string }\n", "        code: { type: integer }\n"));

        assertSingleSharedErrorSchema(contract);
        assertViolation(() -> assertErrorSchemaShape(contract), "Error.code");
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void anEnumeratedMachineCodeSatisfiesTheSchemaShapeRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        code: { type: string }\n",
                "        code: { type: string, enum: [INVALID_INPUT, CONFLICT] }\n"));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void aReferencedMachineCodeSatisfiesTheSchemaShapeRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        code: { type: string }\n",
                "        code: { $ref: '#/components/schemas/ErrorCode' }\n"));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void aMissingMachineCodeFailsOnlyTheSchemaShapeRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        code: { type: string }\n", ""));

        assertViolation(() -> assertErrorSchemaShape(contract), "Error.code");
    }

    @Test
    void aMissingCorrelationIdFailsOnlyTheSchemaShapeRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        correlationId: { type: string }\n", ""));

        assertSingleSharedErrorSchema(contract);
        assertViolation(() -> assertErrorSchemaShape(contract), "Error.correlationId");
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void aNonStringCorrelationIdFailsOnlyTheSchemaShapeRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        correlationId: { type: string }\n",
                "        correlationId: { type: object }\n"));

        assertViolation(() -> assertErrorSchemaShape(contract), "Error.correlationId");
    }

    @Test
    void aNameInAnErrorExampleFailsOnlyTheSensitiveTextRule() {
        Map<String, Object> contract = parse(withErrorExample("Jane Doe is not allowed here"));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertViolation(() -> assertNoSensitiveErrorText(contract), "person name");
    }

    @Test
    void anEmailInAnErrorExampleFailsOnlyTheSensitiveTextRule() {
        Map<String, Object> contract = parse(withErrorExamplesList("jane.doe@example.org"));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertViolation(() -> assertNoSensitiveErrorText(contract), "e-mail address");
    }

    @Test
    void aTitleCaseProseDescriptionIsNotAPersonName() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(ERROR_BODY_422, """
                    '422':
                      description: Not Found
            """));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertNoSensitiveErrorText(contract);
    }

    @Test
    void aPatientIdentifierInAnErrorExampleFailsOnlyTheSensitiveTextRule() {
        Map<String, Object> contract = parse(withErrorExample("patientId PT-12345 was rejected"));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertViolation(() -> assertNoSensitiveErrorText(contract), "patient identifier");
    }

    @Test
    void aStackTraceInAnErrorExampleFailsOnlyTheSensitiveTextRule() {
        Map<String, Object> contract = parse(withErrorExample(
                "java.lang.IllegalStateException at com.example.Service.call(Service.java:42)"));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertViolation(() -> assertNoSensitiveErrorText(contract), "stack trace");
    }

    @Test
    void aSensitiveExampleInTheSharedSchemaFailsTheSensitiveTextRule() {
        Map<String, Object> contract = parse(VALID_CONTRACT.replace(
                "        correlationId: { type: string }\n", """
                        correlationId: { type: string, example: 'jane.doe@example.org' }
                """));

        assertSingleSharedErrorSchema(contract);
        assertErrorSchemaShape(contract);
        assertViolation(() -> assertNoSensitiveErrorText(contract), "e-mail address");
    }

    @Test
    void anAllowListedOperationStillHasToSatisfyTheOtherTextRules() {
        Map<String, Object> contract = parse(VALID_CONTRACT
                .replace("operationId: listThings", "operationId: listFinanceInvoices")
                .replace(ERROR_BODY_422, """
                        '422':
                          description: invalid patientId or tenant selection
                """));

        assertSingleSharedErrorSchema(contract);
        assertNoSensitiveErrorText(contract);
        String withEmail = VALID_CONTRACT
                .replace("operationId: listThings", "operationId: listFinanceInvoices")
                .replace(ERROR_BODY_422, """
                        '422':
                          description: jane.doe@example.org is not allowed here
                """);
        assertViolation(() -> assertNoSensitiveErrorText(parse(withEmail)), "e-mail address");
    }

    private static String withErrorExample(String message) {
        return VALID_CONTRACT.replace(ERROR_BODY_422, """
                    '422':
                      description: invalid input
                      content:
                        application/json:
                          example: { code: INVALID, message: 'PLACEHOLDER', correlationId: abc }
            """.replace("PLACEHOLDER", message));
    }

    private static String withErrorExamplesList(String message) {
        return VALID_CONTRACT.replace(ERROR_BODY_422, """
                    '422':
                      description: invalid input
                      content:
                        application/json:
                          examples:
                            sample:
                              value:
                                - { code: INVALID, message: 'PLACEHOLDER', correlationId: abc }
            """.replace("PLACEHOLDER", message));
    }

    private static void assertSingleSharedErrorSchema(Map<String, Object> contract) {
        List<String> violations = new ArrayList<>();
        if (paths(contract).isEmpty()) {
            violations.add("contract declares no operations with error responses");
        }
        for (Map.Entry<String, Object> pathEntry : paths(contract).entrySet()) {
            if (!(pathEntry.getValue() instanceof Map<?, ?> pathItem)) {
                continue;
            }
            for (Map.Entry<?, ?> methodEntry : pathItem.entrySet()) {
                String method = String.valueOf(methodEntry.getKey());
                if (!HTTP_METHODS.contains(method) || !(methodEntry.getValue() instanceof Map<?, ?> operation)) {
                    continue;
                }
                String operationId = String.valueOf(operation.get("operationId"));
                boolean documentsSharedBody = false;
                for (Map.Entry<?, ?> responseEntry : responses(operation).entrySet()) {
                    String status = String.valueOf(responseEntry.getKey());
                    if (!isErrorStatus(status)) {
                        continue;
                    }
                    String schema = errorSchemaName(contract, responseEntry.getValue());
                    if (SHARED_ERROR_SCHEMA.equals(schema)) {
                        documentsSharedBody = true;
                    } else if (schema != null) {
                        violations.add(operationId + " " + status + " uses error schema " + schema);
                    }
                }
                if (!documentsSharedBody) {
                    violations.add(operationId + " documents no error response with " + SHARED_ERROR_SCHEMA);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                () -> "every error response must use the shared " + SHARED_ERROR_SCHEMA + " schema: " + violations);
    }

    private static void assertErrorSchemaShape(Map<String, Object> contract) {
        Object schema = component(contract, "schemas").get(SHARED_ERROR_SCHEMA);
        assertTrue(schema instanceof Map<?, ?>, "components.schemas." + SHARED_ERROR_SCHEMA + " is missing");
        Object properties = asMap(schema).get("properties");
        assertTrue(properties instanceof Map<?, ?>, SHARED_ERROR_SCHEMA + " has no properties");
        Map<String, Object> declared = asMap(properties);
        assertTrue(isStableMachineCode(declared.get("code")),
                "Error.code must be a string, a non-empty enum or a $ref to a shared code set");
        assertEquals("string", propertyType(declared, "correlationId"),
                "Error.correlationId must be a string");
    }

    private static boolean isStableMachineCode(Object code) {
        if (!(code instanceof Map<?, ?> definition)) {
            return false;
        }
        return "string".equals(definition.get("type"))
                || (definition.get("enum") instanceof List<?> values && !values.isEmpty())
                || definition.get("$ref") instanceof String;
    }

    private static void assertNoSensitiveErrorText(Map<String, Object> contract) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : paths(contract).entrySet()) {
            if (!(pathEntry.getValue() instanceof Map<?, ?> pathItem)) {
                continue;
            }
            for (Map.Entry<?, ?> methodEntry : pathItem.entrySet()) {
                if (!HTTP_METHODS.contains(String.valueOf(methodEntry.getKey()))
                        || !(methodEntry.getValue() instanceof Map<?, ?> operation)) {
                    continue;
                }
                String operationId = String.valueOf(operation.get("operationId"));
                for (Map.Entry<?, ?> responseEntry : responses(operation).entrySet()) {
                    if (!isErrorStatus(String.valueOf(responseEntry.getKey()))) {
                        continue;
                    }
                    Map<String, Object> response = resolveResponse(contract, responseEntry.getValue());
                    StringBuilder examples = new StringBuilder();
                    StringBuilder descriptions = new StringBuilder();
                    collectText(response, false, examples, descriptions);
                    collectSensitiveViolations(violations, operationId, examples.toString(),
                            descriptions.toString());
                }
            }
        }
        Object errorSchema = component(contract, "schemas").get(SHARED_ERROR_SCHEMA);
        if (errorSchema instanceof Map<?, ?>) {
            StringBuilder examples = new StringBuilder();
            StringBuilder descriptions = new StringBuilder();
            collectText(errorSchema, false, examples, descriptions);
            collectSensitiveViolations(violations, SHARED_ERROR_SCHEMA, examples.toString(),
                    descriptions.toString());
        }
        assertTrue(violations.isEmpty(), () -> "error responses expose sensitive text: " + violations);
    }

    private static void collectSensitiveViolations(List<String> violations, String owner,
            String examples, String descriptions) {
        for (SensitiveText sensitive : SENSITIVE_TEXT) {
            String text = sensitive.examplesOnly() ? examples : examples + "\n" + descriptions;
            if (!sensitive.pattern().matcher(text).find()) {
                continue;
            }
            if ("patient identifier".equals(sensitive.label())
                    && PATIENT_IDENTIFIER_TEXT_ALLOW_LIST.contains(owner)) {
                continue;
            }
            violations.add(owner + " exposes a " + sensitive.label());
        }
    }

    private static void collectText(Object node, boolean inExample, StringBuilder examples,
            StringBuilder descriptions) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                boolean nested = inExample || "example".equals(entry.getKey())
                        || "examples".equals(entry.getKey());
                collectText(entry.getValue(), nested, examples, descriptions);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectText(item, inExample, examples, descriptions);
            }
        } else {
            (inExample ? examples : descriptions).append(node).append('\n');
        }
    }

    private static String errorSchemaName(Map<String, Object> contract, Object responseValue) {
        Map<String, Object> response = resolveResponse(contract, responseValue);
        if (response == null || !(response.get("content") instanceof Map<?, ?> content)) {
            return null;
        }
        if (!(content.get("application/json") instanceof Map<?, ?> json)) {
            return null;
        }
        Object body = json.get("schema");
        if (body == null) {
            return null;
        }
        if (!(body instanceof Map<?, ?> schema)) {
            return "<inline body>";
        }
        Object ref = schema.get("$ref");
        if (ref instanceof String reference && reference.startsWith(SCHEMA_REF_PREFIX)) {
            return reference.substring(SCHEMA_REF_PREFIX.length());
        }
        return "<inline body>";
    }

    private static Map<String, Object> resolveResponse(Map<String, Object> contract, Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object ref = map.get("$ref");
        if (ref instanceof String reference && reference.startsWith(RESPONSE_REF_PREFIX)) {
            String name = reference.substring(RESPONSE_REF_PREFIX.length());
            Object resolved = component(contract, "responses").get(name);
            return resolved instanceof Map<?, ?> response ? asMap(response) : asMap(map);
        }
        return asMap(map);
    }

    private static Map<String, Object> responses(Map<?, ?> operation) {
        Object value = operation.get("responses");
        return value instanceof Map<?, ?> map ? asMap(map) : Map.of();
    }

    private static Map<String, Object> paths(Map<String, Object> contract) {
        Object value = contract.get("paths");
        return value instanceof Map<?, ?> map ? asMap(map) : Map.of();
    }

    private static Map<String, Object> component(Map<String, Object> contract, String kind) {
        if (contract.get("components") instanceof Map<?, ?> components
                && components.get(kind) instanceof Map<?, ?> section) {
            return asMap(section);
        }
        return Map.of();
    }

    private static String propertyType(Map<String, Object> properties, String name) {
        Object property = properties.get(name);
        return property instanceof Map<?, ?> map ? String.valueOf(map.get("type")) : "<missing>";
    }

    private static boolean isErrorStatus(String status) {
        return status.length() == 3 && (status.startsWith("4") || status.startsWith("5"));
    }

    private static void assertViolation(Runnable rule, String expectedFragment) {
        AssertionError error = assertThrows(AssertionError.class, rule::run);
        assertTrue(error.getMessage() != null && error.getMessage().contains(expectedFragment),
                () -> "expected a violation naming " + expectedFragment + " but got " + error.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> parse(String yaml) {
        Object parsed = new Yaml().load(yaml);
        assertTrue(parsed instanceof Map<?, ?>, "contract fixture must be a YAML mapping");
        return asMap(parsed);
    }

    private static String readCommittedContract() throws IOException {
        for (String candidate : List.of("../contracts/openapi.yaml", "contracts/openapi.yaml",
                "../../contracts/openapi.yaml")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            Path path = Path.of(basedir).resolve("../contracts/openapi.yaml").normalize();
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IllegalStateException("cannot locate contracts/openapi.yaml");
    }
}
