package com.aurevia.smartclinic.it.s_g2_07;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S-G2-07 acceptance (XCH-07) on a real PostgreSQL instance through the HTTP
 * entry points. Synthetic fixtures only: two tenants share the same test code
 * and patient MRN so cross-tenant concealment is observable.
 *
 * <p>Every exclusion reason is exercised and every observation remains visible;
 * qualified, qualitative, incompatible-unit, wrong-test, wrong-group and
 * non-final results never enter the numeric trend. Comparability is versioned
 * and effective-dated, clinical access is role-scoped, and denials are audited.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_trending_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class TrendingTest {

    private static final String ORG_A = "41111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "42222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_trending_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_trending_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_trending_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "clinician-temp", "clinician");
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_trending_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(2, 6) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(2, 6) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(2, 6) + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-SYN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(2, 6) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static String patientId(String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "bootstrap-" + org.substring(2, 6));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-SYN-1'")) {
                assertTrue(result.next(), "seeded patient must exist in " + org);
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static long countAudit(String action, String outcome) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event WHERE action = ? AND outcome = ?")) {
                statement.setString(1, action);
                statement.setString(2, outcome);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, Object body, String subject,
                                        String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(json(body));
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize synthetic fixture", exception);
        }
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response", exception);
        }
    }

    private static Map<String, Object> comparability(String test, String unit, String group,
                                                     boolean trendable, String effectiveFrom) {
        Map<String, Object> body = new HashMap<>();
        body.put("testCode", test);
        body.put("canonicalUnit", unit);
        body.put("comparabilityGroup", group);
        body.put("trendable", trendable);
        if (effectiveFrom != null) {
            body.put("effectiveFrom", effectiveFrom);
        }
        return body;
    }

    private static Map<String, Object> observation(String patient, String test, String group,
                                                   String status, Object value, String unit,
                                                   String qualifier, String qualitative,
                                                   String effectiveAt, String interpretation) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patient);
        body.put("testCode", test);
        body.put("testDisplay", "Synthetic " + test);
        body.put("comparabilityGroup", group);
        body.put("revisionStatus", status);
        body.put("value", value);
        body.put("unit", unit);
        body.put("qualifier", qualifier);
        if (qualitative != null) {
            body.put("qualitativeValue", qualitative);
        }
        body.put("referenceLow", unit == null || !unit.equals("mmol/mol") ? null : 4.0);
        body.put("referenceHigh", unit == null || !unit.equals("mmol/mol") ? null : 7.0);
        body.put("referenceUnit", unit == null || !unit.equals("mmol/mol") ? null : unit);
        body.put("effectiveAt", effectiveAt);
        body.put("interpretation", interpretation);
        body.put("sourceSystem", "SYNTHETIC_LAB");
        body.put("sourceVersion", "v1");
        return body;
    }

    private String postObservation(String patient, String test, String group, String status,
                                   Object value, String unit, String qualifier, String qualitative,
                                   String effectiveAt, String interpretation) {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/trends/observations",
                observation(patient, test, group, status, value, unit, qualifier, qualitative,
                        effectiveAt, interpretation), "clinician-a", ORG_A);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return tree(response).get("id").asString();
    }

    @Test
    void trendsOnlyComparableObservationsAndKeepsEveryExclusionVisible() throws Exception {
        String patient = patientId(ORG_A);
        assertEquals(201, send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("HBA1C", "mmol/mol", "LAB-HPLC", true, null), "admin-a", ORG_A)
                .getStatusCode().value());

        String first = postObservation(patient, "HBA1C", "LAB-HPLC", "final", 6.1,
                "mmol/mol", "none", null, "2026-08-10T00:00:00Z", "low");
        String second = postObservation(patient, "HBA1C", "LAB-HPLC", "final", 7.0,
                "mmol/mol", "none", null, "2026-09-10T00:00:00Z", "high");
        String third = postObservation(patient, "HBA1C", "LAB-HPLC", "final", 8.2,
                "mmol/mol", "none", null, "2026-10-10T00:00:00Z", "critical high");
        postObservation(patient, "HBA1C", "LAB-HPLC", "final", 3.0, "mmol/mol", "<", null,
                "2026-09-11T00:00:00Z", "below range");
        postObservation(patient, "HBA1C", "LAB-HPLC", "final", 7.1, "%", "none", null,
                "2026-09-12T00:00:00Z", "reported in percent");
        postObservation(patient, "HBA1C", "LAB-HPLC", "final", null, null, "none", "Negative",
                "2026-09-13T00:00:00Z", "coded result");
        postObservation(patient, "GLU", "LAB-HPLC", "final", 90, "mg/dL", "none", null,
                "2026-09-14T00:00:00Z", "different test");
        postObservation(patient, "HBA1C", "LAB-IMMUNO", "final", 7.2, "mmol/mol", "none", null,
                "2026-09-15T00:00:00Z", "different assay");
        postObservation(patient, "HBA1C", "LAB-HPLC", "preliminary", 7.3, "mmol/mol", "none", null,
                "2026-09-16T00:00:00Z", "not final");

        ResponseEntity<String> response = send(HttpMethod.GET,
                "/api/v1/trends/" + patient + "?testCode=HBA1C", null, "clinician-a", ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode view = tree(response);

        assertEquals(3, view.get("points").size());
        assertEquals(List.of(first, second, third), java.util.stream.StreamSupport
                .stream(view.get("points").spliterator(), false)
                .map(point -> point.get("observationId").asString()).toList());
        JsonNode point = view.get("points").get(0);
        assertEquals(6.1, point.get("value").asDouble(), 0.0001);
        assertEquals("mmol/mol", point.get("unit").asString());
        assertEquals(4.0, point.get("referenceLow").asDouble(), 0.0001);
        assertEquals(7.0, point.get("referenceHigh").asDouble(), 0.0001);
        assertEquals("mmol/mol", point.get("referenceUnit").asString());
        assertEquals("low", point.get("interpretation").asString());
        assertEquals("SYNTHETIC_LAB", point.get("sourceSystem").asString());

        assertEquals(6, view.get("excluded").size(), "no observation may disappear");
        Map<String, Integer> reasons = new HashMap<>();
        java.util.stream.StreamSupport.stream(view.get("excluded").spliterator(), false).forEach(item -> {
            String reason = item.get("reason").asString();
            reasons.merge(reason, 1, Integer::sum);
            assertNotNull(item.get("observation").get("effectiveAt"));
        });
        assertEquals(Set.of("QUALIFIED_NUMERIC", "INCOMPATIBLE_UNIT", "QUALITATIVE",
                        "DIFFERENT_TEST", "DIFFERENT_COMPARABILITY_GROUP", "NON_FINAL_REVISION"),
                reasons.keySet());
        assertTrue(countAudit("OBSERVATION_RECORD", "SUCCESS") >= 9);
        assertTrue(countAudit("COMPARABILITY_DEFINE", "SUCCESS") >= 1);
        assertTrue(countAudit("TREND_READ", "SUCCESS") >= 1);
    }

    @Test
    void comparabilityIsVersionedAndAFutureRuleDoesNotBecomeEffectiveEarly() throws Exception {
        String patient = patientId(ORG_A);
        ResponseEntity<String> first = send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("GLU", "mg/dL", "LAB-HPLC", true, "2026-01-01T00:00:00Z"),
                "admin-a", ORG_A);
        assertEquals(201, first.getStatusCode().value());
        ResponseEntity<String> future = send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("GLU", "mmol/L", "LAB-HPLC", true, "2999-01-01T00:00:00Z"),
                "admin-a", ORG_A);
        assertEquals(201, future.getStatusCode().value());
        assertTrue(tree(future).get("version").asInt() > tree(first).get("version").asInt());

        postObservation(patient, "GLU", "LAB-HPLC", "final", 88, "mg/dL", "none", null,
                "2026-09-10T00:00:00Z", "within range");

        JsonNode before = tree(send(HttpMethod.GET, "/api/v1/trends/" + patient + "?testCode=GLU",
                null, "clinician-a", ORG_A));
        assertEquals(tree(first).get("version").asInt(), before.get("ruleVersion").asInt());
        assertEquals(1, before.get("points").size());

        assertEquals(201, send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("GLU", "mg/dL", "LAB-HPLC", true, "2026-01-02T00:00:00Z"),
                "admin-a", ORG_A).getStatusCode().value());
        JsonNode after = tree(send(HttpMethod.GET, "/api/v1/trends/" + patient + "?testCode=GLU",
                null, "clinician-a", ORG_A));
        assertTrue(after.get("ruleVersion").asInt() > before.get("ruleVersion").asInt());
    }

    @Test
    void tenantsStayIsolatedAndRolesAreEnforced() throws Exception {
        String patientA = patientId(ORG_A);
        String patientB = patientId(ORG_B);
        assertEquals(201, send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("CREAT", "umol/L", "LAB-HPLC", true, null), "admin-a", ORG_A)
                .getStatusCode().value());
        assertEquals(201, send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("CREAT", "umol/L", "LAB-HPLC", true, null), "admin-b", ORG_B)
                .getStatusCode().value());

        String aObservation = postObservation(patientA, "CREAT", "LAB-HPLC", "final", 70,
                "umol/L", "none", null, "2026-09-10T00:00:00Z", "within range");
        ResponseEntity<String> bRecord = send(HttpMethod.POST, "/api/v1/trends/observations",
                observation(patientB, "CREAT", "LAB-HPLC", "final", 900, "umol/L", "none", null,
                        "2026-09-10T00:00:00Z", "critical high"), "clinician-b", ORG_B);
        assertEquals(201, bRecord.getStatusCode().value(), bRecord.getBody());

        JsonNode viewA = tree(send(HttpMethod.GET, "/api/v1/trends/" + patientA + "?testCode=CREAT",
                null, "clinician-a", ORG_A));
        assertEquals(1, viewA.get("points").size());
        assertEquals(aObservation, viewA.get("points").get(0).get("observationId").asString());
        assertFalse(viewA.toString().contains(tree(bRecord).get("id").asString()));

        assertEquals(404, send(HttpMethod.GET, "/api/v1/trends/" + patientB + "?testCode=CREAT",
                null, "clinician-a", ORG_A).getStatusCode().value(), "a foreign patient is concealed");

        assertEquals(403, send(HttpMethod.GET, "/api/v1/trends/" + patientA + "?testCode=CREAT",
                null, "admin-a", ORG_A).getStatusCode().value(), "admin has no clinical read");
        assertEquals(403, send(HttpMethod.GET, "/api/v1/trends/" + patientA + "?testCode=CREAT",
                null, "reception-a", ORG_A).getStatusCode().value());
        assertEquals(403, send(HttpMethod.GET, "/api/v1/trends/" + patientA + "?testCode=CREAT",
                null, "ghost", ORG_A).getStatusCode().value());
        assertEquals(403, send(HttpMethod.POST, "/api/v1/trends/observations",
                observation(patientA, "CREAT", "LAB-HPLC", "final", 71, "umol/L", "none", null,
                        "2026-09-11T00:00:00Z", "x"), "admin-a", ORG_A).getStatusCode().value());
        assertEquals(403, send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("CREAT", "umol/L", "LAB-HPLC", true, null), "clinician-a", ORG_A)
                .getStatusCode().value());

        assertEquals(200, send(HttpMethod.POST, "/api/v1/staff/clinician-temp/deactivate",
                null, "admin-a", ORG_A).getStatusCode().value());
        assertEquals(403, send(HttpMethod.GET, "/api/v1/trends/" + patientA + "?testCode=CREAT",
                null, "clinician-temp", ORG_A).getStatusCode().value(),
                "revocation takes effect on the next protected request");
        assertTrue(countAudit("TREND_READ", "DENIED") >= 4);
    }

    @Test
    void invalidInputsFailAtTheBoundary() throws Exception {
        String patient = patientId(ORG_A);
        assertEquals(422, send(HttpMethod.GET,
                "/api/v1/trends/not-a-uuid?testCode=HBA1C", null, "clinician-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, send(HttpMethod.GET,
                "/api/v1/trends/" + patient + "?testCode=%20", null, "clinician-a", ORG_A)
                .getStatusCode().value());
        assertEquals(404, send(HttpMethod.GET,
                "/api/v1/trends/99999999-9999-9999-9999-999999999999?testCode=HBA1C",
                null, "clinician-a", ORG_A).getStatusCode().value());

        Map<String, Object> badQualifier = observation(patient, "HBA1C", "LAB-HPLC", "final", 7,
                "mmol/mol", "approx", null, "2026-09-10T00:00:00Z", "x");
        assertEquals(422, send(HttpMethod.POST, "/api/v1/trends/observations", badQualifier,
                "clinician-a", ORG_A).getStatusCode().value());
        Map<String, Object> badStatus = observation(patient, "HBA1C", "LAB-HPLC", "bogus", 7,
                "mmol/mol", "none", null, "2026-09-10T00:00:00Z", "x");
        assertEquals(422, send(HttpMethod.POST, "/api/v1/trends/observations", badStatus,
                "clinician-a", ORG_A).getStatusCode().value());
        Map<String, Object> badTime = observation(patient, "HBA1C", "LAB-HPLC", "final", 7,
                "mmol/mol", "none", null, "yesterday", "x");
        assertEquals(422, send(HttpMethod.POST, "/api/v1/trends/observations", badTime,
                "clinician-a", ORG_A).getStatusCode().value());
    }
}
