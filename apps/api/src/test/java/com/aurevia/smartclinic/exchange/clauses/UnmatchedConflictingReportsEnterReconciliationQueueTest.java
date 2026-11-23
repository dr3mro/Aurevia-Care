package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

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
 * XCH-04 clause 3 (API evidence): unmatched and identifier-conflicting result
 * reports enter the reconciliation queue instead of being imported.
 *
 * <p>Every report is sent through the real {@code /api/v1/xch04/results} and
 * {@code /api/v1/xch04/reconciliations} entry points against a fresh database
 * created from the production Flyway migrations. A diverted report must return
 * HTTP 202 with an open reconciliation entry, persist one V22 queue row and one
 * {@code XCH04_RESULT_RECONCILED} audit event, and create no clinical revision.
 * The negative cases show that a role without import permission, a blank order
 * identifier, a peer tenant, a role without queue-read permission and an invalid
 * status filter are all refused without a queue row.</p>
 *
 * <p>Fixtures are synthetic: two organizations with distinct local patient
 * identifiers and subjects. No real identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class UnmatchedConflictingReportsEnterReconciliationQueueTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final String QUEUE = "/api/v1/xch04/reconciliations";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed two tenants with the roles needed to prove and refuse enqueueing.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch04_clause_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch04_clause_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, PATIENT_A);
            seedOrganization(statement, ORG_B, PATIENT_B);
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "verifier-a", "lab_verifier");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "verifier-b", "lab_verifier");
            // A peer-tenant queue reader is required to observe RLS concealment
            // as an authorized 200 with an empty page rather than a 403.
            seedMember(statement, ORG_B, "auditor-b", "privacy_auditor");
        }
    }

    /**
     * Drop the fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch04_clause_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     *
     * @param port the local server port supplied by Spring Boot
     */
    @BeforeEach
    void initRest(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedOrganization(Statement statement, String org, String patientId)
            throws Exception {
        String bootstrap = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-CLAUSE', 'CLINIC', "
                + "'Synthetic Clause Patient', 'synthetic clause patient', DATE '1990-05-20')");
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
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
    }

    /**
     * Send one HTTP request with the synthetic identity headers, capturing 4xx
     * responses as bodies instead of letting the client throw.
     *
     * @param method       HTTP method
     * @param uri          request path
     * @param body         JSON body, or null for a request without one
     * @param subject      synthetic subject, or null to omit the header
     * @param organization active organization, or null to omit the header
     * @return the response entity
     */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body,
                                        String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
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

    private static boolean hasEntry(JsonNode queue, String id) {
        for (JsonNode entry : queue) {
            if (id.equals(entry.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read a count under an explicit tenant. FORCE RLS applies to the migration
     * owner too, so the tenant and actor are bound in a rolled-back transaction.
     *
     * @param organization active tenant for the read
     * @param actor        synthetic actor for the read
     * @param sql          aggregate query returning one numeric row
     * @return the count
     * @throws Exception when the query cannot be run
     */
    private static long count(String organization, String actor, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                statement.setString(1, organization);
                statement.setString(2, actor);
                statement.execute();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static String orderBody(String orderRef, String source, String accession) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + PATIENT_A + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"owner\":\"verifier-a\","
                + "\"coverageQueue\":\"queue-a\"}";
    }

    private static String resultBody(String orderRef, String source, String accession) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + PATIENT_A + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"final\","
                + "\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}";
    }

    private ResponseEntity<String> register(String body) {
        return send(HttpMethod.POST, ORDERS, body, "admin-a", ORG_A);
    }

    private ResponseEntity<String> importResult(String body, String subject, String organization) {
        return send(HttpMethod.POST, RESULTS, body, subject, organization);
    }

    private ResponseEntity<String> queue(String status, String subject, String organization) {
        return send(HttpMethod.GET, QUEUE + "?status=" + status, null, subject, organization);
    }

    private static String reconciledAudit(String entryId) {
        return "SELECT count(*) FROM public.audit_event WHERE action = 'XCH04_RESULT_RECONCILED' "
                + "AND resource_id = '" + entryId + "' AND resource_type = 'RECONCILIATION_ENTRY' "
                + "AND outcome = 'SUCCESS' AND purpose = 'XCH04_RESULT_ROUTING' AND scope = 'EXCHANGE'";
    }

    @Test
    void unmatchedReportWithoutRegisteredOrderEntersOpenReconciliationQueue() throws Exception {
        ResponseEntity<String> response = importResult(
                resultBody("ORD-REC-U", "SRC-REC-U", "ACC-REC-U"), "verifier-a", ORG_A);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
        assertEquals("unmatched", text(response, "/reason"));
        assertEquals("open", text(response, "/status"));
        assertEquals("no expected order registered for ORD-REC-U", text(response, "/detail"));
        JsonNode mismatches = json(response).at("/mismatches");
        assertTrue(mismatches.isArray(), response.getBody());
        assertEquals(0, mismatches.size(), response.getBody());
        String id = text(response, "/id");

        assertEquals(1, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE id = '" + id + "' AND order_ref = 'ORD-REC-U' AND accession = 'ACC-REC-U' "
                + "AND source_code = 'SRC-REC-U' AND reason = 'unmatched' AND status = 'open' "
                + "AND mismatches = ''"));
        assertEquals(0, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE order_ref = 'ORD-REC-U'"), "an unmatched report must not be imported");
        assertEquals(1, count(ORG_A, "admin-a", reconciledAudit(id)));
    }

    @Test
    void conflictingIdentifiersEnterOpenReconciliationQueueWithMismatches() throws Exception {
        assertEquals(HttpStatus.CREATED, register(
                orderBody("ORD-REC-C", "SRC-EXPECTED", "ACC-REC-C")).getStatusCode());

        ResponseEntity<String> response = importResult(
                resultBody("ORD-REC-C", "SRC-WRONG", "ACC-REC-C"), "verifier-a", ORG_A);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), response.getBody());
        assertEquals("conflict", text(response, "/reason"));
        assertEquals("open", text(response, "/status"));
        assertEquals("identifier mismatch: sourceId", text(response, "/detail"));
        JsonNode mismatches = json(response).at("/mismatches");
        assertTrue(mismatches.isArray(), response.getBody());
        assertEquals(1, mismatches.size(), response.getBody());
        assertEquals("sourceId", mismatches.get(0).asText());
        String id = text(response, "/id");

        // The database stores the field name in mismatches and the claimed value
        // in source_code; the JSON array assertion above proves the wire form.
        assertEquals(1, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE id = '" + id + "' AND order_ref = 'ORD-REC-C' AND accession = 'ACC-REC-C' "
                + "AND reason = 'conflict' AND status = 'open' AND mismatches = 'sourceId' "
                + "AND source_code = 'SRC-WRONG'"));
        assertEquals(0, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE order_ref = 'ORD-REC-C'"), "a conflicting report must not be imported");
        assertEquals(1, count(ORG_A, "admin-a", reconciledAudit(id)));
    }

    @Test
    void clinicianCannotImportIntoTheQueue() throws Exception {
        // The body is fully valid so validation cannot short-circuit authorization.
        ResponseEntity<String> denied = importResult(
                resultBody("ORD-REC-D", "SRC-REC-D", "ACC-REC-D"), "clinician-a", ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", text(denied, "/code"));
        assertEquals(0, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE accession = 'ACC-REC-D'"));
        assertEquals(0, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-REC-D'"));
        // The shared authorize helper records the denial with the import action
        // and the order reference, before any matching or enqueueing happens.
        assertEquals(1, count(ORG_A, "admin-a", "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'XCH04_RESULT_IMPORT' AND resource_id = 'ORD-REC-D' "
                + "AND resource_type = 'RESULT_REPORT' AND outcome = 'DENIED'"));
    }

    @Test
    void otherTenantCannotReadOrgAQueueEntries() throws Exception {
        ResponseEntity<String> entry = importResult(
                resultBody("ORD-REC-X", "SRC-REC-X", "ACC-REC-X"), "verifier-a", ORG_A);
        assertEquals(HttpStatus.ACCEPTED, entry.getStatusCode(), entry.getBody());
        String id = text(entry, "/id");

        // Positive control: the owning tenant can see the entry it just enqueued.
        ResponseEntity<String> own = queue("open", "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, own.getStatusCode(), own.getBody());
        assertTrue(hasEntry(json(own), id), own.getBody());

        ResponseEntity<String> other = queue("open", "auditor-b", ORG_B);
        assertEquals(HttpStatus.OK, other.getStatusCode(), other.getBody());
        assertTrue(json(other).isArray(), other.getBody());
        assertEquals(0, json(other).size(), other.getBody());
        assertEquals(0, count(ORG_B, "admin-b", "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE id = '" + id + "'"), "FORCE RLS must conceal the peer tenant's queue row");
    }

    @Test
    void blankOrderIdIsRejectedBeforeEnqueue() throws Exception {
        ResponseEntity<String> rejected = importResult(
                resultBody("   ", "SRC-REC-BLANK", "ACC-REC-BLANK"), "verifier-a", ORG_A);

        assertEquals(422, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("INVALID_INPUT", text(rejected, "/code"));
        assertEquals(0, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE accession = 'ACC-REC-BLANK'"));
        assertEquals(0, count(ORG_A, "admin-a", "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-REC-BLANK'"));
    }

    @Test
    void clinicianCannotReadQueue() throws Exception {
        ResponseEntity<String> denied = queue("open", "clinician-a", ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", text(denied, "/code"));
        assertEquals(1, count(ORG_A, "admin-a", "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'XCH04_RECONCILIATION_READ' AND resource_id = 'open' "
                + "AND resource_type = 'RESULT_REPORT' AND outcome = 'DENIED'"));
    }

    @Test
    void invalidStatusFilterIsRejected() {
        ResponseEntity<String> rejected = queue("closed", "auditor-a", ORG_A);

        assertEquals(422, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("INVALID_STATUS", text(rejected, "/code"));
    }
}
