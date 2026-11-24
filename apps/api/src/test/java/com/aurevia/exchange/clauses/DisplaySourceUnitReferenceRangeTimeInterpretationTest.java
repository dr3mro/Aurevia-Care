package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

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

import com.aurevia.exchange.trend.TrendActionPolicy;

/**
 * XCH-07 clause 2: a patient trend shows each observation's source unit,
 * reference range, effective time and source interpretation. Ineligible results
 * stay visible as exclusions and are never plotted as comparable points; the
 * clinical read is role-scoped and tenant-isolated.
 *
 * <p>HTTP-level proof on a real PostgreSQL instance with forced row-level
 * security, using the same synthetic-auth fixtures as the neighbouring XCH-07
 * acceptance suite. This class owns its own database so it can run alongside
 * other workers. Synthetic fixtures only: no patient data or credentials.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_trend_display_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class DisplaySourceUnitReferenceRangeTimeInterpretationTest {

    private static final String ORG_A = "41111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "42222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_trend_display_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Point the HTTP client at the randomly assigned server port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create a fresh tenant database, migrate it and seed synthetic actors. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_trend_display_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_trend_display_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
        }
    }

    /** Drop the disposable tenant database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_trend_display_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String bootstrap = "bootstrap-" + org.substring(2, 6);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(2, 6) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        String bootstrap = "bootstrap-" + org.substring(2, 6);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /**
     * Create a synthetic patient in an organization.
     *
     * @param org tenant organization identifier
     * @param mrn unique synthetic medical record number
     * @return the tenant-local patient identifier
     */
    private static String createPatient(String org, String mrn) throws Exception {
        String bootstrap = "bootstrap-" + org.substring(2, 6);
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, bootstrap);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO public.patient_record "
                    + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES (CAST(? AS uuid), 1, ?, 'CLINIC', 'Synthetic Trend Patient', "
                    + "'synthetic trend patient', '1990-05-20') RETURNING id::text")) {
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

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Define the effective HBA1C comparability rule through its HTTP entry point. */
    private void defineRule() {
        Map<String, Object> body = new HashMap<>();
        body.put("testCode", "HBA1C");
        body.put("canonicalUnit", "mmol/mol");
        body.put("comparabilityGroup", "LAB-HPLC");
        body.put("trendable", true);
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/trends/comparability",
                body, "admin-a", ORG_A);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
    }

    /** Record one synthetic observation through its HTTP entry point. */
    private String postObservation(String patient, String status, Object value, String unit,
                                   String qualifier, String effectiveAt, String interpretation,
                                   boolean withRange) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patient);
        body.put("testCode", "HBA1C");
        body.put("testDisplay", "Synthetic HBA1C");
        body.put("comparabilityGroup", "LAB-HPLC");
        body.put("revisionStatus", status);
        body.put("value", value);
        body.put("unit", unit);
        body.put("qualifier", qualifier);
        body.put("referenceLow", withRange ? 4.0 : null);
        body.put("referenceHigh", withRange ? 7.0 : null);
        body.put("referenceUnit", withRange ? unit : null);
        body.put("effectiveAt", effectiveAt);
        body.put("interpretation", interpretation);
        body.put("sourceSystem", "SYNTHETIC_LAB");
        body.put("sourceVersion", "v1");
        ResponseEntity<String> response = send(HttpMethod.POST, "/api/v1/trends/observations",
                body, "clinician-a", ORG_A);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return tree(response).get("id").asString();
    }

    private ResponseEntity<String> getTrend(String patient, String subject, String organization) {
        return send(HttpMethod.GET, "/api/v1/trends/" + patient + "?testCode=HBA1C",
                null, subject, organization);
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

    @Test
    void sourceUnitRangeTimeAndInterpretationAreShownOnAComparablePoint() throws Exception {
        String patient = createPatient(ORG_A, "MRN-SYN-DISPLAY-POINT");
        defineRule();
        postObservation(patient, "final", 3.9, "mmol/mol", "none",
                "2026-09-10T00:00:00Z", "low", true);

        ResponseEntity<String> response = getTrend(patient, "clinician-a", ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode view = tree(response);
        assertEquals(1, view.get("points").size());
        JsonNode point = view.get("points").get(0);
        assertEquals("mmol/mol", point.get("unit").asString(), "the reported source unit is shown");
        assertEquals(4.0, point.get("referenceLow").asDouble(), 0.0001);
        assertEquals(7.0, point.get("referenceHigh").asDouble(), 0.0001);
        assertEquals("mmol/mol", point.get("referenceUnit").asString(), "the range keeps its reported unit");
        assertEquals("2026-09-10T00:00:00Z", point.get("effectiveAt").asString(),
                "the clinical effective time drives the trend");
        assertEquals("low", point.get("interpretation").asString(),
                "the source interpretation is shown verbatim");
        assertEquals(3.9, point.get("value").asDouble(), 0.0001);
        assertEquals("SYNTHETIC_LAB", point.get("sourceSystem").asString());
        assertEquals("v1", point.get("sourceVersion").asString());
        assertEquals(0, view.get("excluded").size());
    }

    @Test
    void aNonFinalOrQualifiedResultKeepsItsDisplayFieldsButIsNotPlotted() throws Exception {
        String patient = createPatient(ORG_A, "MRN-SYN-DISPLAY-EXCLUDE");
        defineRule();
        postObservation(patient, "preliminary", 7.3, "mmol/mol", "none",
                "2026-09-16T00:00:00Z", "not final", true);
        postObservation(patient, "final", 3.0, "mmol/mol", "<",
                "2026-09-17T00:00:00Z", "below range", true);

        ResponseEntity<String> response = getTrend(patient, "clinician-a", ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode view = tree(response);
        assertEquals(0, view.get("points").size(),
                "a preliminary or '<' result must never be plotted at its literal value");
        assertEquals(2, view.get("excluded").size(), "no ineligible result may disappear");

        JsonNode preliminary = view.get("excluded").get(0);
        JsonNode qualified = view.get("excluded").get(1);
        assertEquals("NON_FINAL_REVISION", preliminary.get("reason").asString());
        assertEquals("QUALIFIED_NUMERIC", qualified.get("reason").asString());

        JsonNode preliminaryObservation = preliminary.get("observation");
        assertEquals("mmol/mol", preliminaryObservation.get("unit").asString());
        assertEquals(4.0, preliminaryObservation.get("referenceLow").asDouble(), 0.0001);
        assertEquals(7.0, preliminaryObservation.get("referenceHigh").asDouble(), 0.0001);
        assertEquals("mmol/mol", preliminaryObservation.get("referenceUnit").asString());
        assertEquals("2026-09-16T00:00:00Z", preliminaryObservation.get("effectiveAt").asString());
        assertEquals("not final", preliminaryObservation.get("interpretation").asString());

        JsonNode qualifiedObservation = qualified.get("observation");
        assertEquals("less_than", qualifiedObservation.get("qualifier").asString());
        assertEquals(3.0, qualifiedObservation.get("value").asDouble(), 0.0001);
        assertEquals("mmol/mol", qualifiedObservation.get("unit").asString());
        assertEquals(4.0, qualifiedObservation.get("referenceLow").asDouble(), 0.0001);
        assertEquals(7.0, qualifiedObservation.get("referenceHigh").asDouble(), 0.0001);
        assertEquals("mmol/mol", qualifiedObservation.get("referenceUnit").asString());
        assertEquals("2026-09-17T00:00:00Z", qualifiedObservation.get("effectiveAt").asString());
        assertEquals("below range", qualifiedObservation.get("interpretation").asString());
    }

    @Test
    void aWrongRoleIsRefusedTrendRead() throws Exception {
        String patient = createPatient(ORG_A, "MRN-SYN-DISPLAY-ROLE");
        defineRule();
        postObservation(patient, "final", 6.1, "mmol/mol", "none",
                "2026-09-10T00:00:00Z", "low", true);

        assertEquals(200, getTrend(patient, "clinician-a", ORG_A).getStatusCode().value());
        assertEquals(403, getTrend(patient, "admin-a", ORG_A).getStatusCode().value(),
                "an administrator may not read clinical trend values");
        assertEquals(403, getTrend(patient, "reception-a", ORG_A).getStatusCode().value(),
                "a receptionist may not read clinical trend values");

        TrendActionPolicy policy = new TrendActionPolicy();
        assertTrue(policy.allowsRead("clinician"));
        assertFalse(policy.allowsRead("tenant_admin"));
        assertFalse(policy.allowsRead("reception"));
    }

    @Test
    void aPatientInAnotherTenantIsConcealed() throws Exception {
        String foreignPatient = createPatient(ORG_B, "MRN-SYN-DISPLAY-FOREIGN");
        assertEquals(404, getTrend(foreignPatient, "clinician-a", ORG_A).getStatusCode().value(),
                "a foreign patient is concealed rather than included in the trend");
    }

    @Test
    void aComparablePointWithNoApprovedRangeStillShowsUnitTimeAndInterpretation() throws Exception {
        String patient = createPatient(ORG_A, "MRN-SYN-DISPLAY-NORANGE");
        defineRule();
        postObservation(patient, "final", 6.1, "mmol/mol", "none",
                "2026-09-10T00:00:00Z", "within range", false);

        ResponseEntity<String> response = getTrend(patient, "clinician-a", ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode point = tree(response).get("points").get(0);
        assertTrue(point.get("referenceLow").isNull());
        assertTrue(point.get("referenceHigh").isNull());
        assertTrue(point.get("referenceUnit").isNull());
        assertEquals("mmol/mol", point.get("unit").asString());
        assertEquals("2026-09-10T00:00:00Z", point.get("effectiveAt").asString());
        assertEquals("within range", point.get("interpretation").asString());
    }
}
