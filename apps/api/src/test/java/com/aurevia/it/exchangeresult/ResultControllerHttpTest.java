package com.aurevia.it.exchangeresult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
 * HTTP security contract for every {@code ResultController} route (XCH-04, ARC-03, ARC-04).
 *
 * <p>Each endpoint is exercised with no subject (401), a subject without an
 * active membership (403) and a malformed active organization (422). The
 * reconciliation list also rejects an unknown status filter with 422. A final
 * chained test drives every route with an allowed subject and asserts the
 * documented success status. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_resulthttp_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ResultControllerHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_resulthttp_it";
    private static final String DB = "orevia_resulthttp_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String ADMIN_SUBJECT = "admin-1111";
    private static final String VERIFIER = "verifier-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Endpoint(String name, HttpMethod method, String path, String body, String ifMatch) {
    }

    private RestClient rest;
    private String patientId;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientId = patientId();
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG + "', 'Synthetic 1111')");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG + "', 'bootstrap-1111', 'Bootstrap')");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                    + "'synthetic patient', '1990-05-20')");
            seedMember(statement, ADMIN_SUBJECT, "tenant_admin");
            seedMember(statement, VERIFIER, "lab_verifier");
            seedMember(statement, CLINICIAN, "clinician");
            seedMember(statement, "auditor-1111", "privacy_auditor");
            seedMember(statement, "reception-1111", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedMember(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG + "'");
        statement.execute("SET app.actor = 'bootstrap-1111'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static String patientId() {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG);
                bind.setString(2, ADMIN_SUBJECT);
                bind.execute();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                return result.next() ? result.getString(1) : ID;
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private String orderBody(String orderId) {
        return "{\"orderId\":\"" + orderId + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"SRC-1\",\"accessionId\":\"ACC-1\",\"destinationId\":\"CLINIC-A\","
                + "\"owner\":\"" + VERIFIER + "\",\"coverageQueue\":\"queue-a\"}";
    }

    private String resultBody(String orderId) {
        return "{\"orderId\":\"" + orderId + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"SRC-1\",\"accessionId\":\"ACC-1\","
                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"final\","
                + "\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}";
    }

    private static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint("registerOrder", HttpMethod.POST, "/api/v1/xch04/orders",
                        "{\"orderId\":\"HTTP-ORD-A\",\"patientId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                                + "\"sourceId\":\"SRC-1\",\"accessionId\":\"ACC-1\","
                                + "\"destinationId\":\"CLINIC-A\",\"owner\":\"verifier-1111\","
                                + "\"coverageQueue\":\"queue-a\"}", null),
                new Endpoint("importResult", HttpMethod.POST, "/api/v1/xch04/results",
                        "{\"orderId\":\"HTTP-ORD-A\",\"patientId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\","
                                + "\"sourceId\":\"SRC-1\",\"accessionId\":\"ACC-1\","
                                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"final\","
                                + "\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}", null),
                new Endpoint("getReport", HttpMethod.GET, "/api/v1/xch04/results/" + ID, null, null),
                new Endpoint("acknowledge", HttpMethod.POST, "/api/v1/xch04/results/" + ID + "/acknowledge",
                        null, "1"),
                new Endpoint("reconciliations", HttpMethod.GET, "/api/v1/xch04/reconciliations?status=open",
                        null, null));
    }

    private ResponseEntity<String> send(Endpoint endpoint, String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (endpoint.ifMatch() != null) {
            headers.set("If-Match", endpoint.ifMatch());
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(endpoint.method()).uri(endpoint.path())
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = endpoint.body() == null ? spec : spec.body(endpoint.body());
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    @ParameterizedTest(name = "{0}: no subject is rejected")
    @MethodSource("endpoints")
    void missingSubjectReturnsUnauthorized(Endpoint endpoint) {
        assertEquals(401, send(endpoint, null, ORG).getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: subject without active membership is denied")
    @MethodSource("endpoints")
    void unauthorizedRoleReturnsForbidden(Endpoint endpoint) {
        assertEquals(403, send(endpoint, "ghost-1111", ORG).getStatusCode().value(), endpoint.name());
    }

    @ParameterizedTest(name = "{0}: malformed active organization is rejected")
    @MethodSource("endpoints")
    void malformedOrganizationReturnsUnprocessable(Endpoint endpoint) {
        assertEquals(422, send(endpoint, VERIFIER, "not-a-uuid").getStatusCode().value(), endpoint.name());
    }

    @Test
    void invalidBodyReturnsUnprocessableWithErrorCode() {
        ResponseEntity<String> response = send(new Endpoint("invalidOrder", HttpMethod.POST,
                "/api/v1/xch04/orders", "{}", null), ADMIN_SUBJECT, ORG);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"INVALID_INPUT\""), response.getBody());
    }

    @Test
    void unknownListStatusReturnsUnprocessable() {
        ResponseEntity<String> response = send(new Endpoint("badStatus", HttpMethod.GET,
                "/api/v1/xch04/reconciliations?status=closed", null, null), "auditor-1111", ORG);
        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"INVALID_STATUS\""), response.getBody());
    }

    @Test
    void allowedSubjectReachesSuccessStatusOnEveryRoute() {
        ResponseEntity<String> order = send(new Endpoint("registerOrder", HttpMethod.POST,
                "/api/v1/xch04/orders", orderBody("HTTP-ORD-1"), null), ADMIN_SUBJECT, ORG);
        assertEquals(201, order.getStatusCode().value(), order.getBody());

        ResponseEntity<String> imported = send(new Endpoint("importResult", HttpMethod.POST,
                "/api/v1/xch04/results", resultBody("HTTP-ORD-1"), null), VERIFIER, ORG);
        assertEquals(201, imported.getStatusCode().value(), imported.getBody());
        String id = tree(imported).at("/id").asText();

        ResponseEntity<String> detail = send(new Endpoint("getReport", HttpMethod.GET,
                "/api/v1/xch04/results/" + id, null, null), CLINICIAN, ORG);
        assertEquals(200, detail.getStatusCode().value(), detail.getBody());

        ResponseEntity<String> acknowledged = send(new Endpoint("acknowledge", HttpMethod.POST,
                "/api/v1/xch04/results/" + id + "/acknowledge", null, "1"), CLINICIAN, ORG);
        assertEquals(200, acknowledged.getStatusCode().value(), acknowledged.getBody());

        ResponseEntity<String> queue = send(new Endpoint("reconciliations", HttpMethod.GET,
                "/api/v1/xch04/reconciliations?status=open", null, null), ADMIN_SUBJECT, ORG);
        assertEquals(200, queue.getStatusCode().value(), queue.getBody());
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse result response", exception);
        }
    }
}
