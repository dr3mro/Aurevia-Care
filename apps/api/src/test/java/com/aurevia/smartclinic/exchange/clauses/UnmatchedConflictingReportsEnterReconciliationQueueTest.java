package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * XCH-04 clause 3 proof at the HTTP boundary: an inbound result whose identifiers
 * do not match a registered order is parked in an OPEN reconciliation queue
 * instead of being imported, and the rule refuses unauthorized or cross-tenant
 * use of that queue.
 *
 * <p>The two synthetic organizations share the same local order, accession and
 * patient MRN space, so forced RLS concealment is observable on a real
 * PostgreSQL database. Every row-count assertion is scoped to one tenant and
 * executed inside that tenant's context. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_clause3_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class UnmatchedConflictingReportsEnterReconciliationQueueTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DATABASE = "smartclinic_xch04_clause3_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final String QUEUE = "/api/v1/xch04/reconciliations";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String patientA;

    /** Create the isolated database, migrate it and seed two synthetic tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER smartclinic_migrate");
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
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "verifier-b", "lab_verifier");
            seedMember(statement, ORG_B, "auditor-b", "privacy_auditor");
        }
    }

    /** Drop the isolated database so the suite leaves no state behind. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** Bind the HTTP client and resolve the seeded synthetic patient for ORG_A. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientA = patientId(ORG_A, "MRN-1");
    }

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
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        String bootstrap = "bootstrap-" + org.substring(0, 4);
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

    private static String patientId(String organization, String mrn) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin-" + organization.substring(0, 1) + "a");
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

    /** Count rows visible inside one tenant's forced-RLS context. */
    private static long count(String organization, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin-" + organization.substring(0, 1) + "a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
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

    /** Whether a queue read contains the entry with the given identifier. */
    private static boolean containsEntryId(ResponseEntity<String> response, String entryId) {
        for (JsonNode node : json(response)) {
            JsonNode id = node.at("/id");
            if (!id.isMissingNode() && entryId.equals(id.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Find one queue entry by the order identifier carried in its identifiers. */
    private static JsonNode queueEntry(ResponseEntity<String> response, String orderId) {
        for (JsonNode node : json(response)) {
            JsonNode identifier = node.at("/identifiers/orderId");
            if (!identifier.isMissingNode() && orderId.equals(identifier.asText())) {
                return node;
            }
        }
        return null;
    }

    private String orderBody(String orderRef, String source) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientA + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + orderRef + "-ACC\","
                + "\"destinationId\":\"CLINIC-A\",\"owner\":\"verifier-a\","
                + "\"coverageQueue\":\"queue-a\"}";
    }

    private String resultBody(String orderRef, String source, String accession) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientA + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"final\","
                + "\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}";
    }

    private ResponseEntity<String> register(String organization, String admin, String body) {
        return send(HttpMethod.POST, ORDERS, body, admin, organization);
    }

    private ResponseEntity<String> importResult(String organization, String subject, String body) {
        return send(HttpMethod.POST, RESULTS, body, subject, organization);
    }

    @Test
    void unmatchedResultIsDivertedToOpenReconciliationQueue() throws Exception {
        // No order is registered for ORD-C3-UNMATCHED, so the report must be
        // parked, never attached to a chart or stored as a clinical revision.
        ResponseEntity<String> response = importResult(ORG_A, "verifier-a",
                resultBody("ORD-C3-UNMATCHED", "SRC-C3-U", "ACC-C3-UNMATCHED"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
        assertEquals("unmatched", text(response, "/reason"));
        assertEquals("open", text(response, "/status"));
        assertTrue(text(response, "/detail").contains("no expected order"));
        String entryId = text(response, "/id");
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-C3-UNMATCHED'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_review r "
                + "JOIN public.xch04_report_revision p ON p.organization_id = r.organization_id "
                + "AND p.id = r.report_id WHERE p.accession = 'ACC-C3-UNMATCHED'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-C3-UNMATCHED' AND reason = 'unmatched' AND status = 'open'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'XCH04_RESULT_RECONCILED' AND outcome = 'SUCCESS' "
                + "AND resource_id = '" + entryId + "'"));
    }

    @Test
    void conflictingIdentifiersAreDivertedToOpenReconciliationQueue() throws Exception {
        // An order exists, but the issuer identifier disagrees. The report must
        // be parked with the mismatching facet named rather than linked to the
        // wrong order.
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-C3-CONFLICT", "SRC-C3-EXPECTED")).getStatusCode());

        ResponseEntity<String> response = importResult(ORG_A, "verifier-a",
                resultBody("ORD-C3-CONFLICT", "SRC-C3-WRONG", "ORD-C3-CONFLICT-ACC"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
        assertEquals("conflict", text(response, "/reason"));
        assertEquals("open", text(response, "/status"));
        assertEquals("sourceId", text(response, "/mismatches/0"));
        assertTrue(response.getBody().contains("sourceId"));
        String entryId = text(response, "/id");
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ORD-C3-CONFLICT-ACC'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_review r "
                + "JOIN public.xch04_report_revision p ON p.organization_id = r.organization_id "
                + "AND p.id = r.report_id WHERE p.accession = 'ORD-C3-CONFLICT-ACC'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-C3-CONFLICT' AND reason = 'conflict' AND status = 'open'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'XCH04_RESULT_RECONCILED' AND outcome = 'SUCCESS' "
                + "AND resource_id = '" + entryId + "'"));
    }

    @Test
    void divertedResultsAppearInTheOpenQueue() throws Exception {
        // Divert one unmatched and one conflicting report here, so the assertion
        // never depends on another test's order. Capture the returned entry ids
        // and require the queue read to contain exactly those entries.
        ResponseEntity<String> unmatchedImport = importResult(ORG_A, "verifier-a",
                resultBody("ORD-C3-Q1", "SRC-C3-Q1", "ACC-C3-Q1"));
        assertEquals(HttpStatus.ACCEPTED, unmatchedImport.getStatusCode(), unmatchedImport.getBody());
        String unmatchedId = text(unmatchedImport, "/id");
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-C3-Q2", "SRC-C3-Q2-EXPECTED")).getStatusCode());
        ResponseEntity<String> conflictImport = importResult(ORG_A, "verifier-a",
                resultBody("ORD-C3-Q2", "SRC-C3-Q2-WRONG", "ACC-C3-Q2"));
        assertEquals(HttpStatus.ACCEPTED, conflictImport.getStatusCode(), conflictImport.getBody());
        String conflictId = text(conflictImport, "/id");

        ResponseEntity<String> queue = send(HttpMethod.GET, QUEUE + "?status=open", null,
                "auditor-a", ORG_A);

        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        assertTrue(containsEntryId(queue, unmatchedId), "the unmatched report waits in the open queue");
        assertTrue(containsEntryId(queue, conflictId), "the conflicting report waits in the open queue");
        JsonNode unmatched = queueEntry(queue, "ORD-C3-Q1");
        JsonNode conflict = queueEntry(queue, "ORD-C3-Q2");
        assertNotNull(unmatched);
        assertNotNull(conflict);
        assertEquals("open", unmatched.at("/status").asText());
        assertEquals("unmatched", unmatched.at("/reason").asText());
        assertEquals("open", conflict.at("/status").asText());
        assertEquals("conflict", conflict.at("/reason").asText());
    }

    @Test
    void receptionRoleCannotImportOrDivert() throws Exception {
        // A reception role has active membership but lacks RESULT_IMPORT, so it
        // must be refused before any reconciliation or report row is written.
        String orderRef = "ORD-C3-DENIED";
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = '" + orderRef + "'"));

        ResponseEntity<String> denied = importResult(ORG_A, "reception-a",
                resultBody(orderRef, "SRC-C3-D", "ACC-C3-DENIED"));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", text(denied, "/code"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = '" + orderRef + "'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-C3-DENIED'"));
    }

    @Test
    void otherTenantCannotSeeTheQueueEntry() throws Exception {
        // ORG_A parks a report; ORG_B shares the same local identifier space
        // but forced RLS must hide ORG_A's queue entry from it.
        assertEquals(HttpStatus.ACCEPTED, importResult(ORG_A, "verifier-a",
                resultBody("ORD-C3-TENANT", "SRC-C3-T", "ACC-C3-TENANT")).getStatusCode());

        ResponseEntity<String> peerQueue = send(HttpMethod.GET, QUEUE + "?status=open", null,
                "auditor-b", ORG_B);
        assertEquals(HttpStatus.OK, peerQueue.getStatusCode(), peerQueue.getBody());
        assertNull(queueEntry(peerQueue, "ORD-C3-TENANT"),
                "the peer tenant must not see ORG_A's queue entry");
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-C3-TENANT'"));

        // Positive control: the owning tenant does see its own entry.
        ResponseEntity<String> ownerQueue = send(HttpMethod.GET, QUEUE + "?status=open", null,
                "auditor-a", ORG_A);
        assertNotNull(queueEntry(ownerQueue, "ORD-C3-TENANT"));
    }

    @Test
    void invalidQueueStatusIsRejected() {
        ResponseEntity<String> rejected = send(HttpMethod.GET, QUEUE + "?status=closed", null,
                "auditor-a", ORG_A);

        assertEquals(422, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("INVALID_STATUS", text(rejected, "/code"));
    }
}
