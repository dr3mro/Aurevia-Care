package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * XCH-04 clause 1 proof through the normal HTTP entry point: every final,
 * corrected and critical result is routed to the owner and coverage queue
 * registered for its order. A result must not be routed for a role without
 * import permission, nor for an order that belongs to another tenant.
 *
 * <p>The suite recreates its own database from the authoritative Flyway
 * migrations, seeds two tenants with bounded roles, and drives the rule through
 * {@code POST /api/v1/xch04/orders} and {@code POST /api/v1/xch04/results} under
 * the synthetic-auth profile. All fixtures are synthetic; no patient data or
 * credentials are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_route_owner_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class RouteFinalCorrectedCriticalResultNamedOwnerTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_route_owner_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final String SOURCE = "SRC-CLAIM";
    private static final String ACCESSION = "ACC-CLAIM";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String patientA;
    private String patientB;

    /** Point the HTTP client at the random port chosen for this test context. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientA = patientId(ORG_A);
        patientB = patientId(ORG_B);
    }

    /** Recreate the synthetic database from the authoritative migrations and seed bounded roles. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_route_owner_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_route_owner_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "verifier-a", "lab_verifier");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "verifier-b", "lab_verifier");
        }
    }

    /** Drop the synthetic database so each run starts from a clean state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_route_owner_it WITH (FORCE)");
        }
    }

    /** Seed one tenant with two synthetic patients and a bootstrap actor. */
    private static void seedOrganization(Statement statement, String org) throws Exception {
        String bootstrap = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-2', 'CLINIC', 'Second Synthetic', 'second synthetic', "
                + "'1991-06-21')");
    }

    /** Seed one synthetic tenant member with a bounded role. */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Read the identifier of a seeded patient inside its owning tenant. */
    private static String patientId(String organization) {
        return patientId(organization, "MRN-1");
    }

    /** Read a seeded patient identifier by its local MRN inside its owning tenant. */
    private static String patientId(String organization, String mrn) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = '" + mrn + "'")) {
                assertTrue(result.next(), "seeded patient must exist in " + organization);
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    /** Bind the tenant and actor configuration variables for one database connection. */
    private static void bind(Connection connection, String organization, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Count rows visible in one tenant, used to assert that no report was persisted. */
    private static long count(String organization, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    /** Build the synthetic-auth headers that carry the caller's subject and active tenant. */
    private static HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Send a request and capture any status instead of throwing on an error response. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Parse a JSON response body so absence of a field can be asserted directly. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /** Read one field from a JSON response by pointer, failing with the body when it is absent. */
    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = json(response).at(pointer);
            assertFalse(value.isMissingNode(), "missing field " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer + " from " + response.getBody(), exception);
        }
    }

    /** Count audit events of one action for one resource inside the active tenant. */
    private static long auditCount(String action, String resourceId) throws Exception {
        return count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = '" + action + "' AND resource_id = '" + resourceId + "'");
    }

    /** Build the order-registration body carrying the named owner and coverage queue. */
    private static String orderBody(String orderRef, String patientId, String source, String accession,
                                    String owner, String queue) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"owner\":\"" + owner + "\","
                + "\"coverageQueue\":\"" + queue + "\"}";
    }

    /** Build the result-import body; the kind is sent in lower-case wire form. */
    private String resultBody(String orderRef, String source, String accession, String kind,
                              Integer supersedes) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientA + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"" + kind + "\""
                + (supersedes == null ? "" : ",\"supersedesVersion\":" + supersedes)
                + ",\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}";
    }

    /** Register an expected order through the documented endpoint. */
    private ResponseEntity<String> register(String organization, String admin, String body) {
        return send(HttpMethod.POST, ORDERS, body, admin, organization);
    }

    /** Import a result through the documented endpoint. */
    private ResponseEntity<String> importResult(String organization, String subject, String body) {
        return send(HttpMethod.POST, RESULTS, body, subject, organization);
    }

    @Test
    void routesFinalCorrectedAndCriticalResultsToTheRegisteredOwnerAndQueue() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CLAIM-1", patientA, SOURCE, ACCESSION, "owner-a", "queue-a")).getStatusCode());

        ResponseEntity<String> finalResult = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CLAIM-1", SOURCE, ACCESSION, "final", null));
        assertEquals(HttpStatus.CREATED, finalResult.getStatusCode(), finalResult.getBody());
        assertEquals("final", text(finalResult, "/kind"));
        assertEquals("1", text(finalResult, "/version"));
        assertEquals("owner-a", text(finalResult, "/owner"));
        assertEquals("queue-a", text(finalResult, "/coverageQueue"));

        ResponseEntity<String> corrected = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CLAIM-1", SOURCE, ACCESSION, "corrected", 1));
        assertEquals(HttpStatus.CREATED, corrected.getStatusCode(), corrected.getBody());
        assertEquals("corrected", text(corrected, "/kind"));
        assertEquals("2", text(corrected, "/version"));
        assertEquals(text(finalResult, "/id"), text(corrected, "/previousId"));
        assertEquals("owner-a", text(corrected, "/owner"));
        assertEquals("queue-a", text(corrected, "/coverageQueue"));

        ResponseEntity<String> critical = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CLAIM-1", SOURCE, ACCESSION, "critical", null));
        assertEquals(HttpStatus.CREATED, critical.getStatusCode(), critical.getBody());
        assertEquals("critical", text(critical, "/kind"));
        assertEquals("3", text(critical, "/version"));
        assertEquals("owner-a", text(critical, "/owner"));
        assertEquals("queue-a", text(critical, "/coverageQueue"));

        // The three revisions are distinct findings, and only the critical one
        // takes the critical routing marker; final and corrected share the
        // ordinary import audit action.
        assertFalse(text(finalResult, "/id").equals(text(corrected, "/id")));
        assertFalse(text(corrected, "/id").equals(text(critical, "/id")));
        assertEquals(1, auditCount("XCH04_CRITICAL_ROUTE", text(critical, "/id")));
        assertEquals(0, auditCount("XCH04_CRITICAL_ROUTE", text(finalResult, "/id")));
        assertEquals(0, auditCount("XCH04_CRITICAL_ROUTE", text(corrected, "/id")));

        // A second order with different routing values proves the destination
        // comes from the matched order, not from a fixed constant.
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CLAIM-1B", patientA, "SRC-CLAIM-B", "ACC-CLAIM-B", "owner-b", "queue-b"))
                .getStatusCode());
        ResponseEntity<String> other = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CLAIM-1B", "SRC-CLAIM-B", "ACC-CLAIM-B", "final", null));
        assertEquals(HttpStatus.CREATED, other.getStatusCode(), other.getBody());
        assertEquals("owner-b", text(other, "/owner"));
        assertEquals("queue-b", text(other, "/coverageQueue"));
    }

    @Test
    void refusesImportForRoleWithoutResultImport() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CLAIM-2", patientA, "SRC-CLAIM-2", "ACC-CLAIM-2", "owner-a", "queue-a"))
                .getStatusCode());

        // Reception has an active membership but no RESULT_IMPORT permission:
        // authorization fails inside the transaction before any routing occurs.
        ResponseEntity<String> denied = importResult(ORG_A, "reception-a",
                resultBody("ORD-CLAIM-2", "SRC-CLAIM-2", "ACC-CLAIM-2", "final", null));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertTrue(json(denied).at("/owner").isMissingNode(), "a denied import must not disclose routing");
        assertTrue(json(denied).at("/coverageQueue").isMissingNode(), "a denied import must not disclose routing");
        assertTrue(json(denied).at("/id").isMissingNode(), "a denied import must not return a report");
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CLAIM-2'"));
    }

    @Test
    void doesNotRouteAnotherTenantsOrder() throws Exception {
        // The peer tenant owns the order. Forced RLS hides it from the active
        // tenant, so matching is UNMATCHED and the report is reconciled, never routed.
        assertEquals(HttpStatus.CREATED, register(ORG_B, "admin-b",
                orderBody("ORD-CLAIM-3", patientB, "SRC-CLAIM-3", "ACC-CLAIM-3", "owner-b", "queue-b"))
                .getStatusCode());

        ResponseEntity<String> diverted = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CLAIM-3", "SRC-CLAIM-3", "ACC-CLAIM-3", "final", null));

        assertEquals(HttpStatus.ACCEPTED, diverted.getStatusCode(), diverted.getBody());
        assertEquals("unmatched", text(diverted, "/reason"));
        assertEquals("open", text(diverted, "/status"));
        assertEquals(0, json(diverted).at("/mismatches").size(),
                "an absent order is unmatched, not an identifier conflict");
        assertTrue(json(diverted).at("/owner").isMissingNode(), "an unmatched report must not be routed");
        assertTrue(json(diverted).at("/coverageQueue").isMissingNode(), "an unmatched report must not be routed");
        assertEquals(1, auditCount("XCH04_RESULT_RECONCILED", text(diverted, "/id")));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CLAIM-3'"));
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CLAIM-3'"));
    }

    @Test
    void rejectsRegistrationWithoutOwnerOrCoverageQueue() {
        // Every other field is valid, so a 422 here can only come from the
        // owner / coverageQueue guard rather than another boundary check.
        ResponseEntity<String> noOwner = send(HttpMethod.POST, ORDERS,
                orderBody("ORD-CLAIM-4", patientA, "SRC-CLAIM-4", "ACC-CLAIM-4", "", "queue-a"),
                "admin-a", ORG_A);
        assertEquals(422, noOwner.getStatusCode().value(), noOwner.getBody());
        assertTrue(noOwner.getBody().contains("INVALID_INPUT"), noOwner.getBody());
        assertTrue(noOwner.getBody().contains("owner is required"), noOwner.getBody());
        assertFalse(noOwner.getBody().contains("\"owner\":"), "an invalid order must not be registered");

        ResponseEntity<String> noQueue = send(HttpMethod.POST, ORDERS,
                orderBody("ORD-CLAIM-5", patientA, "SRC-CLAIM-5", "ACC-CLAIM-5", "owner-a", ""),
                "admin-a", ORG_A);
        assertEquals(422, noQueue.getStatusCode().value(), noQueue.getBody());
        assertTrue(noQueue.getBody().contains("INVALID_INPUT"), noQueue.getBody());
        assertTrue(noQueue.getBody().contains("coverageQueue is required"), noQueue.getBody());
        assertFalse(noQueue.getBody().contains("\"coverageQueue\":"), "an invalid order must not be registered");

        // An omitted owner deserializes to null and takes the other side of the
        // blank/null guard, so a missing field is rejected the same way.
        String omittedOwner = "{\"orderId\":\"ORD-CLAIM-6\",\"patientId\":\"" + patientA + "\","
                + "\"sourceId\":\"SRC-CLAIM-6\",\"accessionId\":\"ACC-CLAIM-6\","
                + "\"destinationId\":\"CLINIC-A\",\"coverageQueue\":\"queue-a\"}";
        ResponseEntity<String> missingOwner = send(HttpMethod.POST, ORDERS, omittedOwner, "admin-a", ORG_A);
        assertEquals(422, missingOwner.getStatusCode().value(), missingOwner.getBody());
        assertTrue(missingOwner.getBody().contains("owner is required"), missingOwner.getBody());
    }
}
