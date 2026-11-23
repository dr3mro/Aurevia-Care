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
 * XCH-04 clause 2 proof through the normal HTTP entry point: an inbound result is
 * imported only when patient, source, accession, destination and order identifiers
 * all agree with the locally registered expectation. A disagreement or an absent
 * expectation diverts the report to the reconciliation queue before any clinical
 * revision or review task is created. Synthetic fixtures only; no patient data or
 * credentials are used.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_match_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class MatchPatientSourceAccessionDestinationOrderIdentifiersTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_match_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final String QUEUE = "/api/v1/xch04/reconciliations";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String patientA;
    private String secondPatientA;
    private String patientB;

    /** Point the HTTP client at the random port chosen for this test context. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientA = patientId(ORG_A, "MRN-MATCH-1");
        secondPatientA = patientId(ORG_A, "MRN-MATCH-2");
        patientB = patientId(ORG_B, "MRN-MATCH-1");
    }

    /** Recreate the synthetic database from the authoritative migrations and seed two tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch04_match_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch04_match_it OWNER smartclinic_migrate");
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch04_match_it WITH (FORCE)");
        }
    }

    /**
     * Seed one tenant root and two local patients referenced by the xch04 foreign keys.
     *
     * @param statement migration-privileged statement on the synthetic database
     * @param org       tenant identifier
     */
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
                + "VALUES ('" + org + "', 1, 'MRN-MATCH-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-MATCH-2', 'CLINIC', 'Second Synthetic', "
                + "'second synthetic', '1991-06-21')");
    }

    /**
     * Seed one synthetic tenant member with a bounded role.
     *
     * @param statement migration-privileged statement on the synthetic database
     * @param org       tenant identifier
     * @param subject   synthetic actor subject
     * @param role      bounded role key
     */
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

    /** Read one seeded patient identifier under the tenant's forced RLS policy. */
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

    /** Count rows visible inside one tenant, using the authoritative RLS policy. */
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

    /** Set the transaction-local tenant and actor used by every RLS-bounded statement. */
    private static void bind(Connection connection, String organization, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Build authenticated headers for a synthetic actor in a given organization. */
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

    /** Send a synthetic request, capturing any status instead of throwing on an error response. */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        HttpHeaders headers = headers(subject, organization);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Read a required text field from a JSON response, failing when it is absent. */
    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertFalse(value.isMissingNode(), "missing field " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer + " from " + response.getBody(), exception);
        }
    }

    /** Parse a JSON response body for structural assertions. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /** Build a register-expectation request with the five identifier values. */
    private static String orderBody(String orderRef, String patientId, String source, String accession,
                                    String destination, String owner, String queue) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"" + destination + "\",\"owner\":\"" + owner + "\","
                + "\"coverageQueue\":\"" + queue + "\"}";
    }

    /** Build an import-result request whose five identifier values can be varied independently. */
    private static String resultBody(String orderRef, String patientId, String source, String accession,
                                     String destination, String kind, Integer supersedes) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"" + destination + "\",\"kind\":\"" + kind + "\""
                + (supersedes == null ? "" : ",\"supersedesVersion\":" + supersedes)
                + ",\"effectiveAt\":\"2026-11-23T12:00:00Z\",\"checksum\":\"match-checksum\"}";
    }

    /** Register an expectation as the tenant administrator. */
    private ResponseEntity<String> register(String organization, String admin, String body) {
        return send(HttpMethod.POST, ORDERS, body, admin, organization);
    }

    /** Import a result as the named actor. */
    private ResponseEntity<String> importResult(String organization, String subject, String body) {
        return send(HttpMethod.POST, RESULTS, body, subject, organization);
    }

    /** Count imported revisions for one test's order and accession in the local tenant. */
    private static long reportCount(String orderRef, String accession) throws Exception {
        return count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE order_ref = '" + orderRef + "' AND accession = '" + accession + "'");
    }

    /**
     * Assert the 202 diversion body: a reason and queue status, no imported report
     * fields, and either the single disagreeing field or none for an absent order.
     */
    private static void assertDivertedBody(ResponseEntity<String> result, String reason, String field) {
        assertEquals(reason, text(result, "/reason"));
        assertEquals("open", text(result, "/status"));
        JsonNode mismatches = json(result).at("/mismatches");
        assertEquals("conflict".equals(reason) ? 1 : 0, mismatches.size(), result.getBody());
        if (field != null) {
            assertEquals(field, mismatches.get(0).asText(), result.getBody());
            assertTrue(text(result, "/detail").contains(field), result.getBody());
        } else {
            assertTrue(text(result, "/detail").contains("no expected order"), result.getBody());
        }
        assertTrue(json(result).at("/version").isMissingNode(),
                "a diversion must not carry an imported report version");
        assertTrue(json(result).at("/kind").isMissingNode(),
                "a diversion must not carry an imported report kind");
    }

    /** Read the diverted entry back through the tenant-bounded reconciliation queue. */
    private void assertPersistedReconciliation(String organization, String reader, String id,
                                               String reason, String field, String detail) {
        ResponseEntity<String> queue = send(HttpMethod.GET, QUEUE + "?status=open", null, reader, organization);
        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        for (JsonNode entry : json(queue)) {
            if (id.equals(entry.get("id").asText())) {
                assertEquals(reason, entry.get("reason").asText());
                assertEquals("open", entry.get("status").asText());
                assertEquals("conflict".equals(reason) ? 1 : 0, entry.get("mismatches").size());
                if (field != null) {
                    assertEquals(field, entry.get("mismatches").get(0).asText());
                }
                assertEquals(detail, entry.get("detail").asText(), "the stored decision must match the response");
                return;
            }
        }
        throw new AssertionError("reconciliation entry not persisted: " + id);
    }

    /**
     * Register an expectation, vary one identifier and prove the result is diverted
     * to reconciliation with only that field named and no clinical revision stored.
     */
    private void assertConflictDiverts(String orderRef, String actualPatientId, String actualSource,
                                       String actualAccession, String actualDestination, String field)
            throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody(orderRef, patientA, "SRC-" + orderRef, "ACC-" + orderRef,
                        "CLINIC-A", "owner-a", "queue-a")).getStatusCode());

        ResponseEntity<String> result = importResult(ORG_A, "verifier-a",
                resultBody(orderRef, actualPatientId, actualSource, actualAccession, actualDestination,
                        "final", null));

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode(), result.getBody());
        assertDivertedBody(result, "conflict", field);
        assertEquals(0, reportCount(orderRef, actualAccession), "a conflicting result must not be imported");
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = '" + orderRef + "'"));
        assertPersistedReconciliation(ORG_A, "auditor-a", text(result, "/id"),
                "conflict", field, text(result, "/detail"));
    }

    @Test
    void importsWhenAllFiveIdentifiersMatch() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-MATCH", patientA, "SRC-MATCH", "ACC-MATCH", "CLINIC-A",
                        "owner-a", "queue-a")).getStatusCode());

        ResponseEntity<String> created = importResult(ORG_A, "verifier-a",
                resultBody("ORD-MATCH", patientA, "SRC-MATCH", "ACC-MATCH", "CLINIC-A", "final", null));

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("1", text(created, "/version"));
        assertEquals("final", text(created, "/kind"));
        assertEquals("owner-a", text(created, "/owner"));
        assertEquals("queue-a", text(created, "/coverageQueue"));
        assertEquals("ORD-MATCH", text(created, "/orderId"));
        assertEquals(patientA, text(created, "/identifiers/patientId"));
        assertEquals("SRC-MATCH", text(created, "/identifiers/sourceId"));
        assertEquals("ACC-MATCH", text(created, "/identifiers/accessionId"));
        assertEquals("CLINIC-A", text(created, "/identifiers/destinationId"));
        String id = text(created, "/id");
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE id = '" + id + "'"), "the matched report is durable");
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_review "
                + "WHERE report_id = '" + id + "'"), "the matched report gets a review task");
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-MATCH'"), "a matched result is never reconciled");
    }

    @Test
    void divertsToReconciliationWhenPatientIdentifierDiffers() throws Exception {
        assertConflictDiverts("ORD-PATIENT", secondPatientA, "SRC-ORD-PATIENT",
                "ACC-ORD-PATIENT", "CLINIC-A", "patientId");
    }

    @Test
    void divertsToReconciliationWhenSourceIdentifierDiffers() throws Exception {
        assertConflictDiverts("ORD-SOURCE", patientA, "SRC-WRONG",
                "ACC-ORD-SOURCE", "CLINIC-A", "sourceId");
    }

    @Test
    void divertsToReconciliationWhenAccessionIdentifierDiffers() throws Exception {
        assertConflictDiverts("ORD-ACCESSION", patientA, "SRC-ORD-ACCESSION",
                "ACC-WRONG", "CLINIC-A", "accessionId");
    }

    @Test
    void divertsToReconciliationWhenDestinationIdentifierDiffers() throws Exception {
        assertConflictDiverts("ORD-DESTINATION", patientA, "SRC-ORD-DESTINATION",
                "ACC-ORD-DESTINATION", "CLINIC-OTHER", "destinationId");
    }

    @Test
    void divertsToReconciliationWhenOrderIdentifierHasNoRegisteredExpectation() throws Exception {
        ResponseEntity<String> result = importResult(ORG_A, "verifier-a",
                resultBody("ORD-UNREGISTERED", patientA, "SRC-UNREGISTERED", "ACC-UNREGISTERED",
                        "CLINIC-A", "final", null));

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode(), result.getBody());
        assertDivertedBody(result, "unmatched", null);
        assertEquals(0, reportCount("ORD-UNREGISTERED", "ACC-UNREGISTERED"),
                "an unmatched result must not be imported");
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-UNREGISTERED'"));
        assertPersistedReconciliation(ORG_A, "auditor-a", text(result, "/id"),
                "unmatched", null, text(result, "/detail"));
    }

    @Test
    void deniesImportToRoleWithoutResultImport() throws Exception {
        ResponseEntity<String> denied = importResult(ORG_A, "auditor-a",
                resultBody("ORD-DENIED", patientA, "SRC-DENIED", "ACC-DENIED", "CLINIC-A", "final", null));

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals(0, reportCount("ORD-DENIED", "ACC-DENIED"), "a denied import must not create a report");
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-DENIED'"), "authorization precedes reconciliation");
        assertEquals(HttpStatus.OK, send(HttpMethod.GET, QUEUE + "?status=open", null,
                "auditor-a", ORG_A).getStatusCode(),
                "the same actor may read the queue; the denial is specific to the import action");
    }

    @Test
    void doesNotImportAnotherTenantsMatchingOrder() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_B, "admin-b",
                orderBody("ORD-PEER", patientB, "SRC-PEER", "ACC-PEER", "CLINIC-A",
                        "owner-b", "queue-b")).getStatusCode());
        assertEquals(1, count(ORG_B, "SELECT count(*) FROM public.xch04_expected_order "
                + "WHERE order_ref = 'ORD-PEER'"), "the peer expectation exists and is non-vacuous");
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_expected_order "
                + "WHERE order_ref = 'ORD-PEER'"), "the local tenant has no such expectation");

        // The peer's expectation is invisible under this tenant's forced RLS, so a
        // locally authorized verifier cannot import against it.
        ResponseEntity<String> diverted = importResult(ORG_A, "verifier-a",
                resultBody("ORD-PEER", patientA, "SRC-PEER", "ACC-PEER", "CLINIC-A", "final", null));
        assertEquals(HttpStatus.ACCEPTED, diverted.getStatusCode(), diverted.getBody());
        assertDivertedBody(diverted, "unmatched", null);
        assertEquals(0, reportCount("ORD-PEER", "ACC-PEER"));
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE order_ref = 'ORD-PEER' AND accession = 'ACC-PEER'"),
                "no cross-tenant report may be created");
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-PEER'"), "no cross-tenant queue entry may be created");
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE order_ref = 'ORD-PEER'"), "the local diversion is recorded locally only");
        assertPersistedReconciliation(ORG_A, "auditor-a", text(diverted, "/id"),
                "unmatched", null, text(diverted, "/detail"));

        // A local actor has no active membership in the peer tenant and fails closed.
        ResponseEntity<String> foreign = importResult(ORG_B, "verifier-a",
                resultBody("ORD-PEER", patientB, "SRC-PEER", "ACC-PEER", "CLINIC-A", "final", null));
        assertEquals(HttpStatus.FORBIDDEN, foreign.getStatusCode(), foreign.getBody());
    }

    @Test
    void rejectsMissingOrMalformedIdentifier() throws Exception {
        ResponseEntity<String> blankOrder = importResult(ORG_A, "verifier-a",
                resultBody("", patientA, "SRC-BLANK", "ACC-BLANK", "CLINIC-A", "final", null));
        assertEquals(422, blankOrder.getStatusCode().value(), blankOrder.getBody());

        String missingOrder = "{\"patientId\":\"" + patientA + "\",\"sourceId\":\"SRC-MISSING\","
                + "\"accessionId\":\"ACC-MISSING\",\"destinationId\":\"CLINIC-A\",\"kind\":\"final\","
                + "\"effectiveAt\":\"2026-11-23T12:00:00Z\",\"checksum\":\"match-checksum\"}";
        ResponseEntity<String> omittedOrder = importResult(ORG_A, "verifier-a", missingOrder);
        assertEquals(422, omittedOrder.getStatusCode().value(), omittedOrder.getBody());

        ResponseEntity<String> malformedPatient = importResult(ORG_A, "verifier-a",
                resultBody("ORD-MALFORMED", "not-a-uuid", "SRC-MALFORMED", "ACC-MALFORMED",
                        "CLINIC-A", "final", null));
        assertEquals(422, malformedPatient.getStatusCode().value(), malformedPatient.getBody());

        assertEquals(0, reportCount("", "ACC-BLANK"));
        assertEquals(0, reportCount("", "ACC-MISSING"));
        assertEquals(0, reportCount("ORD-MALFORMED", "ACC-MALFORMED"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE accession IN ('ACC-BLANK', 'ACC-MISSING', 'ACC-MALFORMED')"),
                "invalid identifiers are rejected before any report or queue write");
    }
}
