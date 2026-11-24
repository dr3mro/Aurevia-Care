package com.aurevia.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Enforces the cross-cutting OpenAPI rules that keep tenant selection out of client-controlled
 * parameters and keep state-changing commands safe to retry. The rules run against the committed
 * staff-API contract and against a synthetic fixture, so a regression in either the document or the
 * checker fails a normal JUnit test. No Spring context, network or database is involved.
 */
class OpenApiContractRulesTest {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
    private static final Set<String> WRITE_METHODS = Set.of("post", "put", "patch", "delete");
    private static final String PARAMETER_PREFIX = "#/components/parameters/";
    private static final Pattern TENANT_LIKE = Pattern.compile("(?i).*(tenant|organization).*");

    // TODO: add operation tags to contracts/openapi.yaml, then delete this allow list.
    // The committed contract has no `tags:` field on any operation, yet a generated client and
    // its docs need a tag to group operations. This list is the current debt, not an exemption.
    private static final Set<String> OPERATIONS_WITHOUT_TAGS = Set.of(
            "getFacility", "listFacilities", "createFacility", "createConfiguration",
            "getEffectiveConfiguration", "approveConfiguration", "getConfigurationHistory",
            "listCatalogFamily", "getCatalogEntry", "defineCatalogEntry", "approveCatalogEntry",
            "queryAuditEvents", "listStaff", "inviteStaff", "getStaff", "deactivateStaff",
            "getStaffHistory", "listAppointments", "listLabAccessions", "listFinanceInvoices",
            "listOrders");

    // TODO: declare the Idempotency-Key header on these POST commands in contracts/openapi.yaml.
    // They predate the wire convention documented in contracts/contract/README.md; the owning
    // services must still enforce replay safety, so this is a contract-only allowance.
    private static final Set<String> OPERATIONS_WITHOUT_IDEMPOTENCY_KEY = Set.of(
            "createFacility", "createConfiguration", "approveConfiguration", "defineCatalogEntry",
            "approveCatalogEntry", "inviteStaff", "deactivateStaff");

    /**
     * A minimal but fully compliant synthetic operation. Each negative fixture is one deliberate
     * mutation of this string, so a single rule violation can be asserted in isolation.
     */
    private static final String VALID_CONTRACT = """
openapi: 3.0.3
info: { title: Synthetic, version: '1' }
paths:
  /api/v1/things:
    post:
      operationId: createThing
      summary: Create a thing
      tags: [things]
      security: [ { bearerAuth: [] } ]
      parameters:
        - name: Idempotency-Key
          in: header
          required: true
          schema: { type: string }
      responses:
        '201': { description: Created }
        '401': { description: Authentication required }
        '403': { description: Forbidden }
""";

    /** A second valid operation used only to provoke a duplicate operationId. */
    private static final String SECOND_OPERATION = """
  /api/v1/others:
    get:
      operationId: createThing
      summary: Read another thing
      tags: [things]
      responses:
        '200': { description: Ok }
""";

    @Test
    void validSyntheticContractHasNoViolations() {
        assertEquals(List.of(), violations(parse(VALID_CONTRACT)));
    }

    @Test
    void missingOperationIdIsReported() {
        String yaml = VALID_CONTRACT.replace("      operationId: createThing\n", "");
        assertEquals(List.of("/api/v1/things post: missing operationId"), violations(parse(yaml)));
    }

    @Test
    void blankOperationIdIsReported() {
        String yaml = VALID_CONTRACT.replace("operationId: createThing", "operationId: '   '");
        assertEquals(List.of("/api/v1/things post: missing operationId"), violations(parse(yaml)));
    }

    @Test
    void duplicateOperationIdIsReported() {
        String yaml = VALID_CONTRACT + SECOND_OPERATION;
        assertEquals(List.of("/api/v1/others get: duplicate operationId createThing"),
                violations(parse(yaml)));
    }

    @Test
    void missingSummaryIsReported() {
        String yaml = VALID_CONTRACT.replace("      summary: Create a thing\n", "");
        assertEquals(List.of("/api/v1/things post: missing summary"), violations(parse(yaml)));
    }

    @Test
    void missingTagIsReported() {
        String yaml = VALID_CONTRACT.replace("      tags: [things]\n", "");
        assertEquals(List.of("/api/v1/things post: missing tag"), violations(parse(yaml)));
    }

    @Test
    void emptyTagListIsReported() {
        String yaml = VALID_CONTRACT.replace("tags: [things]", "tags: []");
        assertEquals(List.of("/api/v1/things post: missing tag"), violations(parse(yaml)));
    }

    @Test
    void securedOperationWithout401IsReported() {
        String yaml = VALID_CONTRACT.replace("        '401': { description: Authentication required }\n", "");
        assertEquals(List.of("/api/v1/things post: secured operation must document 401 and 403; "
                + "missing 401"), violations(parse(yaml)));
    }

    @Test
    void securedOperationWithout403IsReported() {
        String yaml = VALID_CONTRACT.replace("        '403': { description: Forbidden }\n", "");
        assertEquals(List.of("/api/v1/things post: secured operation must document 401 and 403; "
                + "missing 403"), violations(parse(yaml)));
    }

    @Test
    void globalSecurityAlsoRequires401And403() {
        String yaml = VALID_CONTRACT.replace("      security: [ { bearerAuth: [] } ]\n", "")
                .replace("        '401': { description: Authentication required }\n", "")
                .replace("        '403': { description: Forbidden }\n", "")
                + "security: [ { bearerAuth: [] } ]\n";
        assertEquals(List.of("/api/v1/things post: secured operation must document 401 and 403; "
                + "missing 401, 403"), violations(parse(yaml)));
    }

    @Test
    void explicitlyUnsecuredOperationOverridesGlobalSecurity() {
        String yaml = VALID_CONTRACT.replace("security: [ { bearerAuth: [] } ]", "security: []")
                .replace("        '401': { description: Authentication required }\n", "")
                .replace("        '403': { description: Forbidden }\n", "")
                + "security: [ { bearerAuth: [] } ]\n";
        assertEquals(List.of(), violations(parse(yaml)));
    }

    @Test
    void pathLevelKeysAreNotOperations() {
        String yaml = VALID_CONTRACT.replace("  /api/v1/things:\n",
                "  /api/v1/things:\n    summary: Shared path summary\n");
        assertEquals(List.of(), violations(parse(yaml)));
    }

    @Test
    void organizationQueryParameterIsReported() {
        String yaml = VALID_CONTRACT.replace("      parameters:\n", """
      parameters:
        - name: organizationId
          in: query
          required: false
          schema: { type: string }
""");
        assertEquals(List.of("/api/v1/things post: parameter organizationId is tenant-scoped; "
                + "the tenant comes from the session"), violations(parse(yaml)));
    }

    @Test
    void tenantPathParameterIsReported() {
        String yaml = VALID_CONTRACT.replace("      parameters:\n", """
      parameters:
        - name: tenantId
          in: path
          required: true
          schema: { type: string }
""");
        assertEquals(List.of("/api/v1/things post: parameter tenantId is tenant-scoped; "
                + "the tenant comes from the session"), violations(parse(yaml)));
    }

    @Test
    void referencedTenantParameterIsReported() {
        String yaml = VALID_CONTRACT.replace("      parameters:\n", """
      parameters:
        - $ref: '#/components/parameters/TenantQuery'
""") + """
components:
  parameters:
    TenantQuery:
      name: organizationCode
      in: query
      required: false
      schema: { type: string }
""";
        assertEquals(List.of("/api/v1/things post: parameter organizationCode is tenant-scoped; "
                + "the tenant comes from the session"), violations(parse(yaml)));
    }

    @Test
    void headerParameterNamedLikeTenantIsAllowed() {
        String yaml = VALID_CONTRACT.replace("      parameters:\n", """
      parameters:
        - name: X-Active-Organization
          in: header
          required: true
          schema: { type: string }
        - name: Idempotency-Key
          in: header
          required: true
          schema: { type: string }
""");
        assertEquals(List.of(), violations(parse(yaml)));
    }

    @Test
    void postWithoutIdempotencyKeyIsReported() {
        String yaml = VALID_CONTRACT.replace("        - name: Idempotency-Key\n"
                + "          in: header\n"
                + "          required: true\n"
                + "          schema: { type: string }\n",
                "        - name: X-Correlation-Id\n"
                        + "          in: header\n"
                        + "          required: false\n"
                        + "          schema: { type: string }\n");
        assertEquals(List.of("/api/v1/things post: post must declare the Idempotency-Key header"),
                violations(parse(yaml)));
    }

    @Test
    void postWithNoParametersIsReported() {
        String yaml = VALID_CONTRACT.replace("      parameters:\n", "")
                .replace("        - name: Idempotency-Key\n", "")
                .replace("          in: header\n", "")
                .replace("          required: true\n", "")
                .replace("          schema: { type: string }\n", "");
        assertEquals(List.of("/api/v1/things post: post must declare the Idempotency-Key header"),
                violations(parse(yaml)));
    }

    @Test
    void idempotencyKeyPathParameterDoesNotSatisfyTheRule() {
        String yaml = VALID_CONTRACT.replace("        - name: Idempotency-Key\n          in: header\n",
                "        - name: Idempotency-Key\n          in: path\n");
        assertEquals(List.of("/api/v1/things post: post must declare the Idempotency-Key header"),
                violations(parse(yaml)));
    }

    @Test
    void committedStaffApiContractSatisfiesEveryRule() throws IOException {
        Map<String, Object> contract = parse(Files.readString(locateContract()));
        assertEquals(List.of(), violations(contract));
    }

    /**
     * Runs every rule and returns one message per violated rule, keyed by path and method so a
     * reader can find the operation in {@code contracts/openapi.yaml} without reading this class.
     *
     * @param contract a parsed OpenAPI document with {@code paths} at the top level
     * @return the rule violations, empty when the contract is compliant
     */
    private static List<String> violations(Map<String, Object> contract) {
        List<String> problems = new ArrayList<>();
        Set<String> seenOperationIds = new HashSet<>();
        Map<String, Object> paths = map(contract.get("paths"));
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String path = pathEntry.getKey();
            Map<String, Object> pathItem = map(pathEntry.getValue());
            for (Map.Entry<String, Object> methodEntry : pathItem.entrySet()) {
                String method = methodEntry.getKey();
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                Map<String, Object> operation = map(methodEntry.getValue());
                String label = path + " " + method;
                checkOperationId(operation, label, seenOperationIds, problems);
                checkSummaryAndTag(operation, label, problems);
                checkSecurityResponses(contract, operation, label, problems);
                checkTenantParameters(contract, operation, label, problems);
                checkIdempotencyKey(contract, operation, method, label, problems);
            }
        }
        return problems;
    }

    private static void checkOperationId(Map<String, Object> operation, String label,
            Set<String> seenOperationIds, List<String> problems) {
        String operationId = str(operation.get("operationId"));
        if (operationId == null || operationId.isBlank()) {
            problems.add(label + ": missing operationId");
        } else if (!seenOperationIds.add(operationId)) {
            problems.add(label + ": duplicate operationId " + operationId);
        }
    }

    private static void checkSummaryAndTag(Map<String, Object> operation, String label,
            List<String> problems) {
        String summary = str(operation.get("summary"));
        if (summary == null || summary.isBlank()) {
            problems.add(label + ": missing summary");
        }
        Object tags = operation.get("tags");
        boolean tagged = tags instanceof List<?> tagList && !tagList.isEmpty();
        String operationId = str(operation.get("operationId"));
        boolean allowListed = operationId != null && OPERATIONS_WITHOUT_TAGS.contains(operationId);
        if (!tagged && !allowListed) {
            problems.add(label + ": missing tag");
        }
    }

    private static void checkSecurityResponses(Map<String, Object> contract,
            Map<String, Object> operation, String label, List<String> problems) {
        if (!isSecured(contract, operation)) {
            return;
        }
        Map<String, Object> responses = map(operation.get("responses"));
        List<String> missing = new ArrayList<>();
        if (!responses.containsKey("401")) {
            missing.add("401");
        }
        if (!responses.containsKey("403")) {
            missing.add("403");
        }
        if (!missing.isEmpty()) {
            problems.add(label + ": secured operation must document 401 and 403; missing "
                    + String.join(", ", missing));
        }
    }

    private static void checkTenantParameters(Map<String, Object> contract,
            Map<String, Object> operation, String label, List<String> problems) {
        Object parameters = operation.get("parameters");
        if (!(parameters instanceof List<?> parameterList)) {
            return;
        }
        for (Object item : parameterList) {
            Map<String, Object> parameter = resolveParameter(contract, map(item));
            String in = str(parameter.get("in"));
            if (("path".equals(in) || "query".equals(in)) && tenantLike(parameter.get("name"))) {
                problems.add(label + ": parameter " + parameter.get("name")
                        + " is tenant-scoped; the tenant comes from the session");
            }
        }
    }

    private static void checkIdempotencyKey(Map<String, Object> contract,
            Map<String, Object> operation, String method, String label, List<String> problems) {
        if (!WRITE_METHODS.contains(method)) {
            return;
        }
        String operationId = str(operation.get("operationId"));
        if (operationId != null && OPERATIONS_WITHOUT_IDEMPOTENCY_KEY.contains(operationId)) {
            return;
        }
        Object parameters = operation.get("parameters");
        if (!(parameters instanceof List<?> parameterList)) {
            problems.add(label + ": " + method + " must declare the Idempotency-Key header");
            return;
        }
        for (Object item : parameterList) {
            Map<String, Object> parameter = resolveParameter(contract, map(item));
            if ("header".equals(str(parameter.get("in")))
                    && "idempotency-key".equalsIgnoreCase(str(parameter.get("name")))) {
                return;
            }
        }
        problems.add(label + ": " + method + " must declare the Idempotency-Key header");
    }

    private static boolean isSecured(Map<String, Object> contract, Map<String, Object> operation) {
        Object security = operation.containsKey("security")
                ? operation.get("security") : contract.get("security");
        return security instanceof List<?> list && !list.isEmpty();
    }

    private static boolean tenantLike(Object name) {
        return name instanceof String value && TENANT_LIKE.matcher(value).matches();
    }

    private static Map<String, Object> resolveParameter(Map<String, Object> contract,
            Map<String, Object> parameter) {
        Object reference = parameter.get("$ref");
        if (reference instanceof String value && value.startsWith(PARAMETER_PREFIX)) {
            Map<String, Object> components = map(contract.get("components"));
            Map<String, Object> parameters = map(components.get("parameters"));
            return map(parameters.get(value.substring(PARAMETER_PREFIX.length())));
        }
        return parameter;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static String str(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Map<String, Object> parse(String yaml) {
        return map(new Yaml().load(yaml));
    }

    /**
     * Locate the authoritative contract whatever directory Maven was invoked from. Surefire uses
     * the module basedir, but the same test also runs from the repository root in some tooling.
     *
     * @return the path of {@code contracts/openapi.yaml}
     */
    private static Path locateContract() {
        for (String candidate : List.of("../../contracts/openapi.yaml", "contracts/openapi.yaml",
                "../contracts/openapi.yaml")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            Path path = Path.of(basedir).resolve("../contracts/openapi.yaml").normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("cannot locate contracts/openapi.yaml");
    }
}
