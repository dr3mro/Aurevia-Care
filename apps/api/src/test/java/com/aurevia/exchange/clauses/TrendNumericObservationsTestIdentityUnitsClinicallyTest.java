package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

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
 * XCH-07 clause 1 (API evidence): a numeric observation enters the trend only
 * when its test identity, source unit and an effective comparability rule are
 * established. A result that fails any of those gates stays visible with a
 * machine-readable exclusion reason and is never plotted.
 *
 * <p>Each test owns a distinct test code and patient so the shared database
 * cannot make one scenario's comparability rule change another's expected rule
 * version. Synthetic fixtures only; forced row-level security confines every
 * row to the active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_trend_identity_units_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class TrendNumericObservationsTestIdentityUnitsClinicallyTest {

    private static final String ORG_A = "41111111-1111-1111-1111-111111111111";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/orevia_trend_identity_units_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /* Each scenario has its own patient and test code. A comparability rule is
     * tenant-wide per test code, so reusing one code would make the expected
     * ruleVersion depend on JUnit method order (XCH-07). */
    private static final String MRN_MATCH = "MRN-SYN-XCH07-IDU-MATCH";
    private static final String MRN_UNIT = "MRN-SYN-XCH07-IDU-UNIT";
    private static final String MRN_IDENTITY = "MRN-SYN-XCH07-IDU-IDENTITY";
    private static final String MRN_NO_RULE = "MRN-SYN-XCH07-IDU-NORULE";
    private static final String MRN_GROUP = "MRN-SYN-XCH07-IDU-GROUP";
    private static final String MRN_NOT_TRENDABLE = "MRN-SYN-XCH07-IDU-OFF";
    private static final String MRN_FUTURE = "MRN-SYN-XCH07-IDU-FUTURE";

    @LocalServerPort
    int port;

    RestClient rest;

    @BeforeEach
    void initRestClient() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_trend_identity_units_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_trend_identity_units_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedMember(statement, "clinician-a", "clinician");
            seedMember(statement, "admin-a", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_trend_identity_units_it WITH (FORCE)");
        }
    }

    /** Seed the active tenant and one member with a single role for the fixtures. */
    private static void seedMember(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + ORG_A + "', 'Trend Identity Units Org' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + ORG_A + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG_A + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
    }

    /**
     * Create one synthetic patient and return its tenant-local identifier.
     *
     * @param mrn unique synthetic medical record number for the scenario
     * @return the persisted patient identifier
     * @throws Exception when the fixture cannot be inserted
     */
    private static String createPatient(String mrn) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "INSERT INTO public.patient_record "
                    + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', 1, '" + mrn + "', 'CLINIC', 'Synthetic Trend Patient', "
                    + "'synthetic trend patient', DATE '1990-05-20') RETURNING id::text")) {
                assertTrue(result.next(), "synthetic patient must be created");
                return result.getString(1);
            }
        }
    }

    /**
     * Send a request as a synthetic subject in the active organization.
     *
     * @param method       HTTP method
     * @param uri          request path
     * @param body         JSON payload, or {@code null} for a read
     * @param subject      synthetic authenticated subject
     * @param organization active organization identifier
     * @return the raw response so status and body can both be asserted
     */
    private ResponseEntity<String> send(HttpMethod method, String uri, Object body, String subject,
                                         String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(json(body));
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Serialize a synthetic JSON fixture. */
    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize synthetic fixture", exception);
        }
    }

    /** Parse a JSON response body. */
    private static JsonNode tree(ResponseEntity<String> response) {
        assertNotNull(response.getBody(), "response must carry a body");
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response", exception);
        }
    }

    /**
     * Build a comparability command, optionally not yet effective or not trendable.
     *
     * @param testCode      test identity the rule governs
     * @param unit          the only unit eligible for trending
     * @param group         the comparability group the rule governs
     * @param trendable     whether clinical trending is permitted
     * @param effectiveFrom ISO-8601 effective instant, or {@code null} for now
     * @return the JSON-ready command
     */
    private static Map<String, Object> comparability(String testCode, String unit, String group,
                                                      boolean trendable, String effectiveFrom) {
        Map<String, Object> body = new HashMap<>();
        body.put("testCode", testCode);
        body.put("canonicalUnit", unit);
        body.put("comparabilityGroup", group);
        body.put("trendable", trendable);
        if (effectiveFrom != null) {
            body.put("effectiveFrom", effectiveFrom);
        }
        return body;
    }

    /** Build a final numeric observation command with full source provenance. */
    private static Map<String, Object> observation(String patient, String testCode, String group,
                                                   Object value, String unit, String effectiveAt) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patient);
        body.put("testCode", testCode);
        body.put("testDisplay", "Synthetic " + testCode);
        body.put("comparabilityGroup", group);
        body.put("revisionStatus", "final");
        body.put("value", value);
        body.put("unit", unit);
        body.put("qualifier", "none");
        body.put("effectiveAt", effectiveAt);
        body.put("interpretation", "synthetic interpretation");
        body.put("sourceSystem", "SYNTHETIC_LAB");
        body.put("sourceVersion", "v1");
        return body;
    }

    /** Define a trendable rule as tenant admin and return its allocated version. */
    private int defineRule(String testCode, String unit, String group) {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability(testCode, unit, group, true, null), "admin-a", ORG_A);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return tree(response).get("version").asInt();
    }

    /** Record one final numeric observation as a clinician and return its id. */
    private String postObservation(String patient, String testCode, String group, Object value,
                                   String unit, String effectiveAt) {
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/trends/observations",
                observation(patient, testCode, group, value, unit, effectiveAt), "clinician-a", ORG_A);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return tree(response).get("id").asString();
    }

    /** Read a patient's trend through HTTP and assert its rule version. */
    private JsonNode readTrend(String patient, String testCode, int expectedVersion) {
        ResponseEntity<String> response = send(HttpMethod.GET,
                "/api/v1/trends/" + patient + "?testCode=" + testCode, null, "clinician-a", ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode view = tree(response);
        assertEquals(expectedVersion, view.get("ruleVersion").asInt(), view.toString());
        return view;
    }

    @Test
    void trendsNumericObservationsWhenIdentityUnitAndComparabilityMatch() throws Exception {
        String patient = createPatient(MRN_MATCH);
        int version = defineRule("HBA1C", "mmol/mol", "LAB-HPLC");
        String first = postObservation(patient, "HBA1C", "LAB-HPLC", 6.1, "mmol/mol",
                "2026-08-10T00:00:00Z");
        String second = postObservation(patient, "HBA1C", "LAB-HPLC", 7.0, "mmol/mol",
                "2026-09-10T00:00:00Z");

        JsonNode view = readTrend(patient, "HBA1C", version);

        assertEquals(2, view.get("points").size(), view.toString());
        assertEquals(List.of(first, second), StreamSupport
                .stream(view.get("points").spliterator(), false)
                .map(point -> point.get("observationId").asString()).toList());
        assertEquals(6.1, view.get("points").get(0).get("value").asDouble(), 0.0001);
        assertEquals("mmol/mol", view.get("points").get(0).get("unit").asString());
        assertEquals(7.0, view.get("points").get(1).get("value").asDouble(), 0.0001);
        assertEquals("mmol/mol", view.get("points").get(1).get("unit").asString());
        assertEquals(0, view.get("excluded").size(), view.toString());
    }

    @Test
    void anIncompatibleUnitIsVisibleButNotTrended() throws Exception {
        String patient = createPatient(MRN_UNIT);
        int version = defineRule("UNITCHK", "mmol/mol", "LAB-HPLC");
        String recorded = postObservation(patient, "UNITCHK", "LAB-HPLC", 7.1, "%",
                "2026-09-12T00:00:00Z");

        JsonNode view = readTrend(patient, "UNITCHK", version);

        assertEquals(0, view.get("points").size(), view.toString());
        assertEquals(1, view.get("excluded").size(), view.toString());
        JsonNode item = view.get("excluded").get(0);
        assertEquals("INCOMPATIBLE_UNIT", item.get("reason").asString());
        JsonNode retained = item.get("observation");
        assertEquals(recorded, retained.get("id").asString());
        assertEquals(7.1, retained.get("value").asDouble(), 0.0001);
        assertEquals("%", retained.get("unit").asString());
        assertEquals("2026-09-12T00:00:00Z", retained.get("effectiveAt").asString());
    }

    @Test
    void aDifferentTestIdentityIsVisibleButNotTrended() throws Exception {
        String patient = createPatient(MRN_IDENTITY);
        int version = defineRule("IDENTCHK", "mmol/mol", "LAB-HPLC");
        String recorded = postObservation(patient, "GLU", "LAB-HPLC", 90, "mg/dL",
                "2026-09-14T00:00:00Z");

        JsonNode view = readTrend(patient, "IDENTCHK", version);

        assertEquals(0, view.get("points").size(), view.toString());
        assertEquals(1, view.get("excluded").size(), view.toString());
        JsonNode item = view.get("excluded").get(0);
        assertEquals("DIFFERENT_TEST", item.get("reason").asString());
        JsonNode retained = item.get("observation");
        assertEquals(recorded, retained.get("id").asString());
        assertEquals("GLU", retained.get("testCode").asString());
        assertEquals(90, retained.get("value").asInt());
    }

    @Test
    void withoutAnEstablishedComparabilityRuleNothingIsTrended() throws Exception {
        String patient = createPatient(MRN_NO_RULE);
        // No comparability rule is defined for NORULE in this tenant.
        String recorded = postObservation(patient, "NORULE", "LAB-HPLC", 7.4, "mmol/mol",
                "2026-08-10T00:00:00Z");

        JsonNode view = readTrend(patient, "NORULE", 0);

        assertEquals(0, view.get("points").size(), view.toString());
        assertEquals(1, view.get("excluded").size(), view.toString());
        view.get("excluded").forEach(item ->
                assertEquals("COMPARABILITY_NOT_ESTABLISHED", item.get("reason").asString()));
        JsonNode retained = view.get("excluded").get(0).get("observation");
        assertEquals(recorded, retained.get("id").asString());
        assertEquals(7.4, retained.get("value").asDouble(), 0.0001);
        assertEquals("mmol/mol", retained.get("unit").asString());
        assertEquals("2026-08-10T00:00:00Z", retained.get("effectiveAt").asString());
    }

    @Test
    void aDifferentComparabilityGroupIsNeverTrended() throws Exception {
        String patient = createPatient(MRN_GROUP);
        int version = defineRule("GROUPCHK", "mmol/mol", "LAB-HPLC");
        String recorded = postObservation(patient, "GROUPCHK", "LAB-IMMUNO", 7.2, "mmol/mol",
                "2026-09-15T00:00:00Z");

        JsonNode view = readTrend(patient, "GROUPCHK", version);

        assertEquals(0, view.get("points").size(), view.toString());
        assertEquals(1, view.get("excluded").size(), view.toString());
        JsonNode item = view.get("excluded").get(0);
        assertEquals("DIFFERENT_COMPARABILITY_GROUP", item.get("reason").asString());
        JsonNode retained = item.get("observation");
        assertEquals(recorded, retained.get("id").asString());
        assertEquals("GROUPCHK", retained.get("testCode").asString(),
                "the same test identity in a different assay must not be a DIFFERENT_TEST result");
        assertEquals("LAB-IMMUNO", retained.get("comparabilityGroup").asString());
        assertEquals("2026-09-15T00:00:00Z", retained.get("effectiveAt").asString());
    }

    @Test
    void aRuleThatDisallowsTrendingKeepsObservationsVisibleButNotPlotted() throws Exception {
        String patient = createPatient(MRN_NOT_TRENDABLE);
        ResponseEntity<String> defined = send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("OFFCHK", "mmol/mol", "LAB-HPLC", false, null), "admin-a", ORG_A);
        assertEquals(201, defined.getStatusCode().value(), defined.getBody());
        int version = tree(defined).get("version").asInt();
        String recorded = postObservation(patient, "OFFCHK", "LAB-HPLC", 7.6, "mmol/mol",
                "2026-09-10T00:00:00Z");

        JsonNode view = readTrend(patient, "OFFCHK", version);

        assertEquals(0, view.get("points").size(), view.toString());
        assertEquals(1, view.get("excluded").size(), view.toString());
        view.get("excluded").forEach(item ->
                assertEquals("NOT_TRENDABLE", item.get("reason").asString()));
        assertEquals(recorded, view.get("excluded").get(0).get("observation").get("id").asString());
    }

    @Test
    void aFutureEffectiveComparabilityRuleDoesNotEstablishTrending() throws Exception {
        String patient = createPatient(MRN_FUTURE);
        ResponseEntity<String> defined = send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("FUTURECHK", "mmol/mol", "LAB-HPLC", true, "2999-01-01T00:00:00Z"),
                "admin-a", ORG_A);
        assertEquals(201, defined.getStatusCode().value(), defined.getBody());
        String recorded = postObservation(patient, "FUTURECHK", "LAB-HPLC", 7.5, "mmol/mol",
                "2026-09-10T00:00:00Z");

        // The fold hides a rule whose effective instant has not arrived, so no
        // rule is established for this read and the version falls back to zero.
        JsonNode view = readTrend(patient, "FUTURECHK", 0);

        assertEquals(0, view.get("points").size(), view.toString());
        assertEquals(1, view.get("excluded").size(), view.toString());
        view.get("excluded").forEach(item ->
                assertEquals("COMPARABILITY_NOT_ESTABLISHED", item.get("reason").asString()));
        assertEquals(recorded, view.get("excluded").get(0).get("observation").get("id").asString());
    }
}
