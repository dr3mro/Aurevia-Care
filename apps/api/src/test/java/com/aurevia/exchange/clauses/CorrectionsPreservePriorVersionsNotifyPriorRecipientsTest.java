package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * XCH-04 clause 4 (API evidence): corrections preserve prior versions, notify
 * prior recipients where appropriate, and reopen review even after acknowledgment.
 *
 * <p>The positive case acknowledges an imported final result by a human, then
 * imports a correction through the real {@code /api/v1/xch04} entry point. It
 * asserts both the HTTP response and the persisted lineage, notification,
 * review and audit rows: the original revision and its acknowledgment survive,
 * the correction is a new unreviewed version linked to the original, the prior
 * recipient is notified, and the reopen is audited. Negative cases show that a
 * correction which omits the superseded version is refused, one that targets a
 * stale tip is diverted to reconciliation, a non-correction cannot carry a
 * supersession, and an actor without the import role or without membership in
 * the active tenant cannot write a correction.</p>
 *
 * <p>Every request runs against a fresh real-PostgreSQL database created from
 * the production Flyway migrations. Fixtures are synthetic: two organizations
 * carry synthetic subjects and one synthetic patient each; no real identity or
 * patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_xch04_correction_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class CorrectionsPreservePriorVersionsNotifyPriorRecipientsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_xch04_correction_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String patientA;

    /**
     * Create the isolated fixture database, apply the production migrations and
     * seed synthetic organizations, memberships and patients.
     *
     * @throws Exception when the database cannot be created or seeded
     */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            // Both roles are needed: Flyway migrates as aurevia_migrate while
            // the application datasource connects as aurevia_app under RLS.
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch04_correction_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_xch04_correction_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch04_correction_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port and resolve the
     * synthetic patient of the origin tenant.
     *
     * @param port randomly assigned local server port
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientA = patientId(ORG_A);
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        // V7 requires a civil date or an explicit unknown reason; a synthetic
        // date keeps the append-only patient invariant true for the fixture.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', DATE '1990-05-20')");
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

    private static String patientId(String organization) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
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
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
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

    private long auditCount(String action, String resourceId) throws Exception {
        return count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = '" + action + "' AND resource_id = '" + resourceId + "'");
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
                                        String organization, Map<String, String> extraHeaders) {
        HttpHeaders headers = headers(subject, organization);
        extraHeaders.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertTrue(!value.isMissingNode(), "missing field " + pointer + " in " + response.getBody());
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

    private String resultBody(String orderRef, String source, String accession, String kind,
                              Integer supersedes) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientA + "\","
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

    private ResponseEntity<String> acknowledge(String id, int version, String subject, String organization) {
        return send(HttpMethod.POST, RESULTS + "/" + id + "/acknowledge", null, subject, organization,
                Map.of("If-Match", Integer.toString(version)));
    }

    /**
     * Assert that a refused correction wrote no clinical state for its lineage.
     * An authorization denial may still append a DENIED audit row, so the audit
     * assertion is scoped to the reopen event that a real correction would emit.
     *
     * @param organization    tenant that owns the lineage
     * @param accession       accession identifying the lineage
     * @param priorId         identifier of the original revision
     * @param reconciliations expected number of reconciliation rows for the accession
     * @throws Exception when the fixture database cannot be read
     */
    private static void assertNoCorrection(String organization, String accession, String priorId,
                                           long reconciliations) throws Exception {
        assertEquals(1, count(organization, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = '" + accession + "'"));
        assertEquals(0, count(organization, "SELECT count(*) FROM public.xch04_notification n "
                + "JOIN public.xch04_report_revision r ON r.organization_id = n.organization_id "
                + "AND r.id = n.report_id WHERE r.accession = '" + accession + "'"));
        assertEquals(reconciliations, count(organization, "SELECT count(*) FROM public.xch04_reconciliation "
                + "WHERE accession = '" + accession + "'"));
        assertEquals(0, count(organization, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'XCH04_REVIEW_REOPENED' AND resource_id = '" + priorId + "'"));
    }

    @Test
    void correctionPreservesPriorRevisionNotifiesPriorRecipientsAndReopensAcknowledgedReview()
            throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-1", patientA, "SRC-CORR-1", "ACC-CORR-1", "verifier-a", "queue-a"))
                .getStatusCode());
        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-1", "SRC-CORR-1", "ACC-CORR-1", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());
        assertEquals("1", text(original, "/version"));
        String firstId = text(original, "/id");

        ResponseEntity<String> acknowledged = acknowledge(firstId, 1, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));

        ResponseEntity<String> corrected = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-1", "SRC-CORR-1", "ACC-CORR-1", "corrected", 1));
        assertEquals(HttpStatus.CREATED, corrected.getStatusCode(), corrected.getBody());
        assertEquals("2", text(corrected, "/version"));
        assertEquals("corrected", text(corrected, "/kind"));
        assertEquals(firstId, text(corrected, "/previousId"));
        String secondId = text(corrected, "/id");
        assertNotEquals(firstId, secondId, "a correction must append a distinct immutable revision");

        assertEquals(2, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CORR-1'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_notification "
                + "WHERE report_id = '" + secondId + "' AND recipient_subject = 'verifier-a' "
                + "AND superseded_version = 1"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_review "
                + "WHERE report_id = '" + firstId + "' AND review_state = 'acknowledged'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_review "
                + "WHERE report_id = '" + secondId + "' AND review_state = 'unreviewed'"));
        assertEquals(1, auditCount("XCH04_REVIEW_REOPENED", firstId));

        assertEquals("acknowledged", text(send(HttpMethod.GET, RESULTS + "/" + firstId, null,
                "clinician-a", ORG_A), "/review/state"));
        ResponseEntity<String> secondDetail = send(HttpMethod.GET, RESULTS + "/" + secondId, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, secondDetail.getStatusCode(), secondDetail.getBody());
        assertEquals("unreviewed", text(secondDetail, "/review/state"));
        assertEquals(2, json(secondDetail).at("/lineage").size());
        assertEquals("verifier-a", text(secondDetail, "/notifications/0/recipient"));
    }

    @Test
    void correctionWithoutSupersedesIsRefused() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-2", patientA, "SRC-CORR-2", "ACC-CORR-2", "verifier-a", "queue-a"))
                .getStatusCode());
        String firstId = text(importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-2", "SRC-CORR-2", "ACC-CORR-2", "final", null)), "/id");

        ResponseEntity<String> refused = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-2", "SRC-CORR-2", "ACC-CORR-2", "corrected", null));
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertTrue(refused.getBody().contains("SUPERSEDES_REQUIRED"), refused.getBody());
        assertNoCorrection(ORG_A, "ACC-CORR-2", firstId, 0);
    }

    @Test
    void staleCorrectionIsDivertedToReconciliation() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-3", patientA, "SRC-CORR-3", "ACC-CORR-3", "verifier-a", "queue-a"))
                .getStatusCode());
        String firstId = text(importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-3", "SRC-CORR-3", "ACC-CORR-3", "final", null)), "/id");
        assertEquals("acknowledged", text(acknowledge(firstId, 1, "clinician-a", ORG_A), "/state"));

        ResponseEntity<String> stale = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-3", "SRC-CORR-3", "ACC-CORR-3", "corrected", 9));
        assertEquals(HttpStatus.ACCEPTED, stale.getStatusCode(), stale.getBody());
        assertEquals("conflict", text(stale, "/reason"));
        assertEquals("supersedesVersion", text(stale, "/mismatches/0"));
        assertNoCorrection(ORG_A, "ACC-CORR-3", firstId, 1);
        assertEquals("acknowledged", text(send(HttpMethod.GET, RESULTS + "/" + firstId, null,
                "clinician-a", ORG_A), "/review/state"));
    }

    @Test
    void finalResultWithSupersedesIsRefused() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-4", patientA, "SRC-CORR-4", "ACC-CORR-4", "verifier-a", "queue-a"))
                .getStatusCode());
        String firstId = text(importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-4", "SRC-CORR-4", "ACC-CORR-4", "final", null)), "/id");

        ResponseEntity<String> refused = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-4", "SRC-CORR-4", "ACC-CORR-4", "final", 1));
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertTrue(refused.getBody().contains("SUPERSEDES_NOT_ALLOWED"), refused.getBody());
        assertNoCorrection(ORG_A, "ACC-CORR-4", firstId, 0);
    }

    @Test
    void actorWithoutImportRoleCannotPostCorrection() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-5", patientA, "SRC-CORR-5", "ACC-CORR-5", "verifier-a", "queue-a"))
                .getStatusCode());
        String firstId = text(importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-5", "SRC-CORR-5", "ACC-CORR-5", "final", null)), "/id");

        ResponseEntity<String> denied = importResult(ORG_A, "reception-a",
                resultBody("ORD-CORR-5", "SRC-CORR-5", "ACC-CORR-5", "corrected", 1));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertNoCorrection(ORG_A, "ACC-CORR-5", firstId, 0);
    }

    @Test
    void correctionByActorWhoIsNotAMemberOfTheActiveTenantIsDenied() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-6", patientA, "SRC-CORR-6", "ACC-CORR-6", "verifier-a", "queue-a"))
                .getStatusCode());
        String firstId = text(importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-6", "SRC-CORR-6", "ACC-CORR-6", "final", null)), "/id");

        // verifier-a belongs to ORG_A: under ORG_B the membership fold is empty,
        // so the correction is denied before any import can be attempted.
        ResponseEntity<String> denied = importResult(ORG_B, "verifier-a",
                resultBody("ORD-CORR-6", "SRC-CORR-6", "ACC-CORR-6", "corrected", 1));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertTrue(denied.getBody().contains("FORBIDDEN"), denied.getBody());
        assertNoCorrection(ORG_A, "ACC-CORR-6", firstId, 0);
        assertEquals(0, count(ORG_B, "SELECT count(*) FROM public.xch04_report_revision"));
    }
}
