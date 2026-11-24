package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
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
 * XCH-07 clause 3 (API evidence): qualified, qualitative and incompatible-unit
 * results stay visible as exclusions and are never plotted as comparable points.
 *
 * <p>The positive test drives the real HTTP entry points against PostgreSQL and
 * asserts that a less-than result, a greater-than result, a coded-only result and
 * a result in a non-canonical unit each remain visible with their original
 * qualifier, value, unit and reason while only the ordinary numeric result
 * becomes a trend point. The negative tests assert the rule refuses what it must
 * refuse: a role without clinical authority, a clinician reading another
 * tenant's patient, an unknown qualifier and a qualified result with no numeric
 * value. Synthetic fixtures and forced row-level security confine every row to
 * the active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_keep_qualifiers_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class KeepQualifiersSuchLessGreaterQualitativeResultsTest {

    private static final String ORG_A = "41111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "42222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaa1111-1111-1111-1111-111111111111";
    private static final String PATIENT_B = "bbbb2222-2222-2222-2222-222222222222";
    private static final String SHARED_MRN = "MRN-SYN-KEEP-SHARED";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_keep_qualifiers_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_keep_qualifiers_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_keep_qualifiers_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, PATIENT_A);
            seedOrganization(statement, ORG_B, PATIENT_B);
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_keep_qualifiers_it WITH (FORCE)");
        }
    }

    /** Seed one tenant with a baseline patient sharing the cross-tenant MRN. */
    private static void seedOrganization(Statement statement, String org, String patientId) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(2, 6) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + patientId + "', 1, '" + SHARED_MRN + "', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    /** Create one active organization member with a single role for synthetic fixtures. */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Create a dedicated patient so observation fixtures cannot leak between scenarios. */
    private static String createPatient(String org, String mrn) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "bootstrap");
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO public.patient_record "
                    + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES (CAST(? AS uuid), 1, ?, 'CLINIC', 'Synthetic Rule Patient', "
                    + "'synthetic rule patient', DATE '1990-05-20') RETURNING id::text")) {
                statement.setString(1, org);
                statement.setString(2, mrn);
                statement.execute();
                try (ResultSet result = statement.getResultSet()) {
                    assertTrue(result.next());
                    String id = result.getString(1);
                    connection.commit();
                    return id;
                }
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    /** Bind the tenant context on a raw connection, matching the application's RLS variables. */
    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Send one HTTP request as the given synthetic subject in the given organization. */
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

    /** Build a comparability command with the exact wire field names. */
    private static Map<String, Object> comparability(String test, String unit, String group,
                                                     boolean trendable) {
        Map<String, Object> body = new HashMap<>();
        body.put("testCode", test);
        body.put("canonicalUnit", unit);
        body.put("comparabilityGroup", group);
        body.put("trendable", trendable);
        return body;
    }

    /** Build an observation command with the exact wire field names. */
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

    /** Assert a refused command's HTTP status and its stable domain error code. */
    private static void assertFailure(ResponseEntity<String> response, int status, String code) {
        assertEquals(status, response.getStatusCode().value(), response.getBody());
        assertEquals(code, tree(response).get("code").asString(), response.getBody());
    }

    /** Record an observation as the tenant's clinician and return its opaque id. */
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
    void qualifiedQualitativeAndIncompatibleResultsRemainVisibleButAreNotTrendedOverHttp() throws Exception {
        String patient = createPatient(ORG_A, "MRN-SYN-KEEP-RULE");
        assertEquals(201, send(HttpMethod.POST, "/api/v1/trends/comparability",
                comparability("HBA1C", "mmol/mol", "LAB-HPLC", true), "admin-a", ORG_A)
                .getStatusCode().value());

        String comparable = postObservation(patient, "HBA1C", "LAB-HPLC", "final", 6.1,
                "mmol/mol", "none", null, "2026-08-10T00:00:00Z", "in range");
        String lessThan = postObservation(patient, "HBA1C", "LAB-HPLC", "final", 3.0,
                "mmol/mol", "<", null, "2026-09-01T00:00:00Z", "below range");
        String greaterThan = postObservation(patient, "HBA1C", "LAB-HPLC", "final", 9.0,
                "mmol/mol", ">", null, "2026-09-02T00:00:00Z", "above range");
        String qualitative = postObservation(patient, "HBA1C", "LAB-HPLC", "final", null,
                null, "none", "Negative", "2026-09-03T00:00:00Z", "coded result");
        String percent = postObservation(patient, "HBA1C", "LAB-HPLC", "final", 7.1,
                "%", "none", null, "2026-09-04T00:00:00Z", "reported in percent");

        ResponseEntity<String> response = send(HttpMethod.GET,
                "/api/v1/trends/" + patient + "?testCode=HBA1C", null, "clinician-a", ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode view = tree(response);

        assertEquals(1, view.get("points").size(), "only the ordinary numeric result may be plotted");
        assertEquals(comparable, view.get("points").get(0).get("observationId").asString());
        assertEquals(6.1, view.get("points").get(0).get("value").asDouble(), 0.0001);

        assertEquals(4, view.get("excluded").size(), "no observation may disappear");
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode item : view.get("excluded")) {
            byId.put(item.get("observation").get("id").asString(), item);
        }
        assertEquals(Set.of(lessThan, greaterThan, qualitative, percent), byId.keySet());

        JsonNode below = byId.get(lessThan);
        assertEquals("QUALIFIED_NUMERIC", below.get("reason").asString());
        assertEquals("less_than", below.get("observation").get("qualifier").asString());
        assertEquals(3.0, below.get("observation").get("value").asDouble(), 0.0001);
        assertEquals("mmol/mol", below.get("observation").get("unit").asString());

        JsonNode above = byId.get(greaterThan);
        assertEquals("QUALIFIED_NUMERIC", above.get("reason").asString());
        assertEquals("greater_than", above.get("observation").get("qualifier").asString());
        assertEquals(9.0, above.get("observation").get("value").asDouble(), 0.0001);

        JsonNode coded = byId.get(qualitative);
        assertEquals("QUALITATIVE", coded.get("reason").asString());
        assertEquals("Negative", coded.get("observation").get("qualitativeValue").asString());
        assertTrue(coded.get("observation").get("value").isNull());

        JsonNode incompatible = byId.get(percent);
        assertEquals("INCOMPATIBLE_UNIT", incompatible.get("reason").asString());
        assertEquals("%", incompatible.get("observation").get("unit").asString());
        assertEquals(7.1, incompatible.get("observation").get("value").asDouble(), 0.0001);

        // The qualifier wire value is stored, so the exclusion survives a restart.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT qualifier, value FROM public.trend_observation WHERE id = CAST('"
                            + lessThan + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("less_than", result.getString("qualifier"));
                assertEquals(0, result.getBigDecimal("value").compareTo(new BigDecimal("3.0")));
            }
        }
    }

    @Test
    void receptionCannotRecordOrReadTrendObservations() {
        assertFailure(send(HttpMethod.GET, "/api/v1/trends/" + PATIENT_A + "?testCode=HBA1C",
                null, "reception-a", ORG_A), 403, "FORBIDDEN");
        assertFailure(send(HttpMethod.POST, "/api/v1/trends/observations",
                observation(PATIENT_A, "HBA1C", "LAB-HPLC", "final", 7.0, "mmol/mol", "none", null,
                        "2026-09-10T00:00:00Z", "denied"),
                "reception-a", ORG_A), 403, "FORBIDDEN");
    }

    @Test
    void clinicianOfAnotherTenantCannotReadThisPatientsTrend() {
        // PATIENT_A and PATIENT_B share SHARED_MRN but belong to different organizations.
        assertFailure(send(HttpMethod.GET, "/api/v1/trends/" + PATIENT_B + "?testCode=HBA1C",
                null, "clinician-a", ORG_A), 404, "NOT_FOUND");
        assertEquals(200, send(HttpMethod.GET, "/api/v1/trends/" + PATIENT_A + "?testCode=HBA1C",
                null, "clinician-a", ORG_A).getStatusCode().value(),
                "the same clinician can read a patient of their own tenant");
    }

    @Test
    void unknownQualifierIsRefused() {
        assertFailure(send(HttpMethod.POST, "/api/v1/trends/observations",
                observation(PATIENT_A, "HBA1C", "LAB-HPLC", "final", 7.0, "mmol/mol", "approx", null,
                        "2026-09-10T00:00:00Z", "unknown qualifier"),
                "clinician-a", ORG_A), 422, "INVALID_QUALIFIER");
    }

    @Test
    void qualifiedNumericWithoutAValueIsRefused() {
        assertFailure(send(HttpMethod.POST, "/api/v1/trends/observations",
                observation(PATIENT_A, "HBA1C", "LAB-HPLC", "final", null, null, "<", "Positive",
                        "2026-09-10T00:00:00Z", "qualified without a value"),
                "clinician-a", ORG_A), 422, "INVALID_QUALIFIER");
    }
}
