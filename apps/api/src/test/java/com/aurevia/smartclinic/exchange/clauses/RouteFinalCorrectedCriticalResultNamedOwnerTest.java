package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * XCH-04 clause 1 proof against a real PostgreSQL database through the normal HTTP entry points.
 *
 * <p>The clause says every final, corrected and critical result is routed to the named owner and
 * coverage queue registered on its matched order. One order is given distinctive routing values and
 * all three dispositions are imported in one lineage, so a hard-coded fallback owner or queue cannot
 * satisfy the assertions. Two negative paths show the rule refusing what it must refuse: an actor
 * without the import permission, and a result whose order exists only in the peer tenant.</p>
 *
 * <p>Fixtures are synthetic: two organizations share local patient MRNs and order codes so forced
 * row level security concealment is observable. {@code ResultReport} and {@code xch04_report_revision}
 * carry no critical field; the critical routing marker is therefore evidenced by {@code kind} plus
 * the durable {@code XCH04_CRITICAL_ROUTE} audit action, with final and corrected as negative controls.
 * Escalation and acknowledgment belong to XCH-05 and are not asserted here.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_route_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RouteFinalCorrectedCriticalResultNamedOwnerTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_xch04_route_clause_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final String OWNER = "owner-named-route";
    private static final String QUEUE = "queue-named-route";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String patientA;
    private String patientB;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientA = patientId(ORG_A, "MRN-1");
        patientB = patientId(ORG_B, "MRN-1");
    }

    /** Create an isolated database, apply the production migrations and seed two synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "verifier-a", "lab_verifier");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "verifier-b", "lab_verifier");
        }
    }

    /** Drop the synthetic database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String prefix = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + prefix + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + prefix + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

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

    private static void bind(Connection connection, String organization, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

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

    private static long auditCount(String organization, String action, String resourceId, String outcome)
            throws Exception {
        return count(organization, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = '" + action + "' AND resource_id = '" + resourceId
                + "' AND outcome = '" + outcome + "'");
    }

    private HttpHeaders headers(String subject, String organization) {
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

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertFalse(value.isMissingNode(), "missing field " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer + " from " + response.getBody(), exception);
        }
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    private static String orderBody(String orderRef, String patientId, String source, String accession,
                                    String owner, String queue) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"owner\":\"" + owner + "\","
                + "\"coverageQueue\":\"" + queue + "\"}";
    }

    private static String resultBody(String orderRef, String source, String accession, String kind,
                                     Integer supersedes, String patientId) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"" + kind + "\""
                + (supersedes == null ? "" : ",\"supersedesVersion\":" + supersedes)
                + ",\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}";
    }

    private ResponseEntity<String> register(String organization, String admin, String body) {
        return send(HttpMethod.POST, ORDERS, body, admin, organization);
    }

    private ResponseEntity<String> importResult(String organization, String subject, String body) {
        return send(HttpMethod.POST, RESULTS, body, subject, organization);
    }

    /** All three dispositions from one matched order carry its exact owner and coverage queue. */
    @Test
    void everyFinalCorrectedAndCriticalResultIsRoutedToTheRegisteredOwnerAndCoverageQueue() throws Exception {
        ResponseEntity<String> registration = register(ORG_A, "admin-a",
                orderBody("ORD-ROUTE-POS", patientA, "SRC-ROUTE-POS", "ACC-ROUTE-POS", OWNER, QUEUE));
        assertEquals(HttpStatus.CREATED, registration.getStatusCode(), registration.getBody());
        assertEquals(OWNER, text(registration, "/owner"));
        assertEquals(QUEUE, text(registration, "/coverageQueue"));

        ResponseEntity<String> first = importResult(ORG_A, "verifier-a",
                resultBody("ORD-ROUTE-POS", "SRC-ROUTE-POS", "ACC-ROUTE-POS", "final", null, patientA));
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        assertEquals("1", text(first, "/version"));
        assertEquals("final", text(first, "/kind"));
        assertEquals(OWNER, text(first, "/owner"));
        assertEquals(QUEUE, text(first, "/coverageQueue"));
        String firstId = text(first, "/id");

        // The correction must target the current tip (version 1); any other value diverts to
        // reconciliation and would leave no routed revision to assert on.
        ResponseEntity<String> second = importResult(ORG_A, "verifier-a",
                resultBody("ORD-ROUTE-POS", "SRC-ROUTE-POS", "ACC-ROUTE-POS", "corrected", 1, patientA));
        assertEquals(HttpStatus.CREATED, second.getStatusCode(), second.getBody());
        assertEquals("2", text(second, "/version"));
        assertEquals("corrected", text(second, "/kind"));
        assertEquals("1", text(second, "/supersedesVersion"));
        assertEquals(firstId, text(second, "/previousId"));
        assertEquals(OWNER, text(second, "/owner"));
        assertEquals(QUEUE, text(second, "/coverageQueue"));
        String secondId = text(second, "/id");

        ResponseEntity<String> third = importResult(ORG_A, "verifier-a",
                resultBody("ORD-ROUTE-POS", "SRC-ROUTE-POS", "ACC-ROUTE-POS", "critical", null, patientA));
        assertEquals(HttpStatus.CREATED, third.getStatusCode(), third.getBody());
        assertEquals("3", text(third, "/version"));
        assertEquals("critical", text(third, "/kind"));
        assertEquals(OWNER, text(third, "/owner"));
        assertEquals(QUEUE, text(third, "/coverageQueue"));
        String thirdId = text(third, "/id");

        ResponseEntity<String> firstDetail = send(HttpMethod.GET, RESULTS + "/" + firstId, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, firstDetail.getStatusCode(), firstDetail.getBody());
        assertEquals(OWNER, text(firstDetail, "/current/owner"));
        assertEquals(QUEUE, text(firstDetail, "/current/coverageQueue"));
        // The detail view always exposes the whole order lineage, not just revisions up to this one.
        assertEquals(3, json(firstDetail).at("/lineage").size());

        ResponseEntity<String> secondDetail = send(HttpMethod.GET, RESULTS + "/" + secondId, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, secondDetail.getStatusCode(), secondDetail.getBody());
        assertEquals(OWNER, text(secondDetail, "/current/owner"));
        assertEquals(QUEUE, text(secondDetail, "/current/coverageQueue"));
        assertEquals(firstId, text(secondDetail, "/current/previousId"));
        assertEquals(3, json(secondDetail).at("/lineage").size());
        assertEquals(OWNER, text(secondDetail, "/lineage/0/owner"));
        assertEquals(QUEUE, text(secondDetail, "/lineage/2/coverageQueue"));

        ResponseEntity<String> thirdDetail = send(HttpMethod.GET, RESULTS + "/" + thirdId, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, thirdDetail.getStatusCode(), thirdDetail.getBody());
        assertEquals("critical", text(thirdDetail, "/current/kind"));
        assertEquals(OWNER, text(thirdDetail, "/current/owner"));
        assertEquals(QUEUE, text(thirdDetail, "/current/coverageQueue"));
        assertEquals(3, json(thirdDetail).at("/lineage").size());

        assertEquals(3L, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE order_ref = 'ORD-ROUTE-POS' AND owner_subject = '" + OWNER
                + "' AND coverage_queue = '" + QUEUE + "'"));
        assertEquals(1L, auditCount(ORG_A, "XCH04_CRITICAL_ROUTE", thirdId, "SUCCESS"));
        assertEquals(0L, auditCount(ORG_A, "XCH04_CRITICAL_ROUTE", firstId, "SUCCESS"));
        assertEquals(0L, auditCount(ORG_A, "XCH04_CRITICAL_ROUTE", secondId, "SUCCESS"));
        assertEquals(1L, auditCount(ORG_A, "XCH04_RESULT_IMPORT", firstId, "SUCCESS"));
    }

    /** An actor without the import permission cannot route a result, and no row is written. */
    @Test
    void aReceptionActorCannotImportOrRouteAResult() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-ROUTE-DENY", patientA, "SRC-ROUTE-DENY", "ACC-ROUTE-DENY", OWNER, QUEUE))
                .getStatusCode());

        ResponseEntity<String> denied = importResult(ORG_A, "reception-a",
                resultBody("ORD-ROUTE-DENY", "SRC-ROUTE-DENY", "ACC-ROUTE-DENY", "final", null, patientA));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", text(denied, "/code"));
        assertEquals(0L, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE order_ref = 'ORD-ROUTE-DENY'"));
        assertEquals(0L, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE accession = 'ACC-ROUTE-DENY'"));
        assertEquals(1L, auditCount(ORG_A, "XCH04_RESULT_IMPORT", "ORD-ROUTE-DENY", "DENIED"));
    }

    /** A result for a peer-tenant order is diverted, never routed to that tenant's owner. */
    @Test
    void aResultForAnotherTenantsOrderIsNotRoutedToThatTenantsOwner() throws Exception {
        String peerOwner = "owner-org-b-only";
        String peerQueue = "queue-org-b-only";
        ResponseEntity<String> peerOrder = register(ORG_B, "admin-b",
                orderBody("ORD-ROUTE-XB", patientB, "SRC-ROUTE-XB", "ACC-ROUTE-XB", peerOwner, peerQueue));
        assertEquals(HttpStatus.CREATED, peerOrder.getStatusCode(), peerOrder.getBody());
        assertEquals(peerOwner, text(peerOrder, "/owner"));
        assertEquals(peerQueue, text(peerOrder, "/coverageQueue"));

        // The ORG_A caller carries an ORG_A patient: the only invisible fact is the peer's order.
        ResponseEntity<String> diverted = importResult(ORG_A, "verifier-a",
                resultBody("ORD-ROUTE-XB", "SRC-ROUTE-XB", "ACC-ROUTE-XB", "final", null, patientA));
        assertEquals(HttpStatus.ACCEPTED, diverted.getStatusCode(), diverted.getBody());
        assertEquals("unmatched", text(diverted, "/reason"));
        assertEquals("open", text(diverted, "/status"));

        assertEquals(0L, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE order_ref = 'ORD-ROUTE-XB'"));
        assertEquals(0L, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE owner_subject = '" + peerOwner + "' OR coverage_queue = '" + peerQueue + "'"));
        assertEquals(1L, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE accession = 'ACC-ROUTE-XB' AND reason = 'unmatched'"));
        assertEquals(1L, count(ORG_B, "SELECT count(*) FROM public.xch04_expected_order "
                + "WHERE order_ref = 'ORD-ROUTE-XB' AND owner_subject = '" + peerOwner + "'"));
    }
}
