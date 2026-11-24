package com.aurevia.it.exchangeresult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * S-G2-04 acceptance (XCH-04) against real PostgreSQL.
 *
 * <p>Fixtures are synthetic: two organizations share the same local patient MRN
 * and identical order/accession codes, so forced RLS concealment is observable.
 * The suite exercises every route and repository path, including identifier
 * matching, reconciliation, correction lineage, review reopen and notification
 * evidence.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_xch04_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class ResultRoutingIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_xch04_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final String QUEUE = "/api/v1/xch04/reconciliations";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String patientA;
    private String patientB;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientA = patientId(ORG_A);
        patientB = patientId(ORG_B);
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch04_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_xch04_it OWNER aurevia_migrate");
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
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "revoked-a", "lab_verifier", true);
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "verifier-b", "lab_verifier");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch04_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-2', 'CLINIC', 'Second Synthetic', 'second synthetic', "
                + "'1991-06-21')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        seedMember(statement, org, subject, role, false);
    }

    private static void seedMember(Statement statement, String org, String subject, String role,
                                   boolean revoked) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + (revoked ? "revoked" : "active")
                + "' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static String patientId(String organization) {
        return patientId(organization, "MRN-1");
    }

    private static String patientId(String organization, String mrn) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
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
        return send(method, uri, body, subject, organization, Map.of());
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

    @Test
    void matchedResultIsImportedRoutedAndAudited() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R1", patientA, "SRC-1", "ACC-R1", "verifier-a", "queue-a")).getStatusCode());

        ResponseEntity<String> created = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R1", "SRC-1", "ACC-R1", "final", null));
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("1", text(created, "/version"));
        assertEquals("final", text(created, "/kind"));
        assertEquals("verifier-a", text(created, "/owner"));
        assertEquals("queue-a", text(created, "/coverageQueue"));
        String id = text(created, "/id");

        ResponseEntity<String> detail = send(HttpMethod.GET, RESULTS + "/" + id, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, detail.getStatusCode(), detail.getBody());
        assertEquals("unreviewed", text(detail, "/review/state"));
        assertEquals(1, json(detail).at("/lineage").size());
        assertEquals(0, json(detail).at("/notifications").size());
        assertEquals(1, auditCount("XCH04_RESULT_IMPORT", id));
    }

    @Test
    void unmatchedAndConflictingReportsEnterTheReconciliationQueue() {
        ResponseEntity<String> unmatched = importResult(ORG_A, "verifier-a",
                resultBody("ORD-NOPE", "SRC-X", "ACC-R2", "final", null));
        assertEquals(HttpStatus.ACCEPTED, unmatched.getStatusCode(), unmatched.getBody());
        assertEquals("unmatched", text(unmatched, "/reason"));
        assertEquals("open", text(unmatched, "/status"));

        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R2B", patientA, "SRC-2", "ACC-R2B", "verifier-a", "queue-a")).getStatusCode());
        ResponseEntity<String> conflict = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R2B", "WRONG-SOURCE", "ACC-R2B", "final", null));
        assertEquals(HttpStatus.ACCEPTED, conflict.getStatusCode(), conflict.getBody());
        assertEquals("conflict", text(conflict, "/reason"));
        assertTrue(conflict.getBody().contains("sourceId"));

        ResponseEntity<String> queue = send(HttpMethod.GET, QUEUE + "?status=open", null,
                "auditor-a", ORG_A);
        assertEquals(HttpStatus.OK, queue.getStatusCode(), queue.getBody());
        assertTrue(json(queue).size() >= 2);
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.GET, QUEUE + "?status=open", null,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals(422, send(HttpMethod.GET, QUEUE + "?status=closed", null,
                "auditor-a", ORG_A).getStatusCode().value());
    }

    @Test
    void correctionPreservesTheOriginalReopensReviewAndNotifiesPriorRecipients() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R3", patientA, "SRC-3", "ACC-R3", "verifier-a", "queue-a")).getStatusCode());
        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R3", "SRC-3", "ACC-R3", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());
        String firstId = text(original, "/id");

        ResponseEntity<String> acknowledged = send(HttpMethod.POST, RESULTS + "/" + firstId + "/acknowledge",
                null, "clinician-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));

        ResponseEntity<String> corrected = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R3", "SRC-3", "ACC-R3", "corrected", 1));
        assertEquals(HttpStatus.CREATED, corrected.getStatusCode(), corrected.getBody());
        assertEquals("2", text(corrected, "/version"));
        assertEquals(firstId, text(corrected, "/previousId"));
        String secondId = text(corrected, "/id");

        assertEquals(2, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-R3'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_notification "
                + "WHERE report_id = '" + secondId + "'"));
        assertEquals(1, auditCount("XCH04_REVIEW_REOPENED", firstId));

        assertEquals("acknowledged", text(send(HttpMethod.GET, RESULTS + "/" + firstId, null,
                "clinician-a", ORG_A), "/review/state"));
        ResponseEntity<String> secondDetail = send(HttpMethod.GET, RESULTS + "/" + secondId, null,
                "clinician-a", ORG_A);
        assertEquals("unreviewed", text(secondDetail, "/review/state"));
        assertEquals(2, json(secondDetail).at("/lineage").size());
        assertEquals("verifier-a", text(secondDetail, "/notifications/0/recipient"));
    }

    @Test
    void criticalResultIsRoutedWithItsCriticalOwnerAndQueue() {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R4", patientA, "SRC-4", "ACC-R4", "clinician-a", "critical-queue"))
                .getStatusCode());
        ResponseEntity<String> critical = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R4", "SRC-4", "ACC-R4", "critical", null));
        assertEquals(HttpStatus.CREATED, critical.getStatusCode(), critical.getBody());
        assertEquals("critical", text(critical, "/kind"));
        assertEquals("clinician-a", text(critical, "/owner"));
        assertEquals("critical-queue", text(critical, "/coverageQueue"));
    }

    @Test
    void replayOfTheSameReportDoesNotDuplicateIt() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R5", patientA, "SRC-5", "ACC-R5", "verifier-a", "queue-a")).getStatusCode());
        ResponseEntity<String> first = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R5", "SRC-5", "ACC-R5", "final", null));
        ResponseEntity<String> replay = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R5", "SRC-5", "ACC-R5", "final", null));
        assertEquals(HttpStatus.CREATED, replay.getStatusCode(), replay.getBody());
        assertEquals(text(first, "/id"), text(replay, "/id"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-R5'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_review "
                + "WHERE report_id = '" + text(first, "/id") + "'"));
    }

    @Test
    void aReorderedFinalAfterACorrectionDoesNotDuplicateOrOverwrite() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-RO", patientA, "SRC-RO", "ACC-RO", "verifier-a", "queue-a"))
                .getStatusCode());
        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-RO", "SRC-RO", "ACC-RO", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());
        String firstId = text(original, "/id");

        ResponseEntity<String> corrected = importResult(ORG_A, "verifier-a",
                resultBody("ORD-RO", "SRC-RO", "ACC-RO", "corrected", 1));
        assertEquals(HttpStatus.CREATED, corrected.getStatusCode(), corrected.getBody());
        String secondId = text(corrected, "/id");
        assertFalse(firstId.equals(secondId));

        // Re-deliver the original final after the correction advanced the tip.
        ResponseEntity<String> reordered = importResult(ORG_A, "verifier-a",
                resultBody("ORD-RO", "SRC-RO", "ACC-RO", "final", null));
        assertEquals(HttpStatus.CREATED, reordered.getStatusCode(), reordered.getBody());
        assertEquals(firstId, text(reordered, "/id"),
                "a reordered final must resolve to its stored revision");
        assertEquals(2, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-RO'"), "no duplicate revision may be inserted");
        assertEquals("unreviewed", text(send(HttpMethod.GET, RESULTS + "/" + secondId, null,
                "clinician-a", ORG_A), "/review/state"),
                "the correction's review task must not be overwritten by the replay");
    }

    @Test
    void authorizationAndTenantIsolationFailClosed() {
        assertEquals(HttpStatus.UNAUTHORIZED, importResult(ORG_A, null,
                resultBody("ORD-R6", "SRC-6", "ACC-R6", "final", null)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, importResult(ORG_A, "reception-a",
                resultBody("ORD-R6", "SRC-6", "ACC-R6", "final", null)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, importResult(ORG_A, "auditor-a",
                resultBody("ORD-R6", "SRC-6", "ACC-R6", "final", null)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, importResult(ORG_A, "revoked-a",
                resultBody("ORD-R6", "SRC-6", "ACC-R6", "final", null)).getStatusCode());

        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R6", patientA, "SRC-6", "ACC-R6", "verifier-a", "queue-a")).getStatusCode());
        ResponseEntity<String> alpha = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R6", "SRC-6", "ACC-R6", "final", null));
        assertEquals(HttpStatus.CREATED, alpha.getStatusCode(), alpha.getBody());
        String alphaId = text(alpha, "/id");

        // The peer tenant registers the same local order, accession and patient MRN.
        assertEquals(HttpStatus.CREATED, register(ORG_B, "admin-b",
                orderBody("ORD-R6", patientB, "SRC-6", "ACC-R6", "verifier-b", "queue-b")).getStatusCode());
        ResponseEntity<String> beta = importResult(ORG_B, "verifier-b",
                resultBodyForPatient("ORD-R6", "SRC-6", "ACC-R6", patientB));
        assertEquals(HttpStatus.CREATED, beta.getStatusCode(), beta.getBody());
        assertFalse(alphaId.equals(text(beta, "/id")));

        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, RESULTS + "/" + alphaId, null,
                "verifier-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.GET, RESULTS + "/" + alphaId, null,
                "verifier-a", ORG_A).getStatusCode());
    }

    @Test
    void versionPreconditionsAndInvalidInputsAreEnforced() {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R7", patientA, "SRC-7", "ACC-R7", "verifier-a", "queue-a")).getStatusCode());
        String id = text(importResult(ORG_A, "verifier-a",
                resultBody("ORD-R7", "SRC-7", "ACC-R7", "final", null)), "/id");

        assertEquals(428, send(HttpMethod.POST, RESULTS + "/" + id + "/acknowledge", null,
                "clinician-a", ORG_A).getStatusCode().value());
        assertEquals(412, send(HttpMethod.POST, RESULTS + "/" + id + "/acknowledge", null,
                "clinician-a", ORG_A, Map.of("If-Match", "7")).getStatusCode().value());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST, RESULTS + "/" + id + "/acknowledge", null,
                "clinician-a", ORG_A, Map.of("If-Match", "1")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, send(HttpMethod.POST, RESULTS + "/" + id + "/acknowledge", null,
                "clinician-a", ORG_A, Map.of("If-Match", "2")).getStatusCode());

        assertEquals(422, importResult(ORG_A, "verifier-a",
                resultBody("ORD-R7", "SRC-7", "ACC-R7", "unknown", null)).getStatusCode().value());
        assertEquals(422, importResult(ORG_A, "verifier-a",
                resultBody("ORD-R7", "SRC-7", "ACC-R7", "corrected", 0)).getStatusCode().value());
        assertEquals(422, send(HttpMethod.GET, RESULTS + "/not-a-uuid", null,
                "clinician-a", ORG_A).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET,
                RESULTS + "/44444444-4444-4444-4444-444444444444", null,
                "clinician-a", ORG_A).getStatusCode());
        assertEquals(409, register(ORG_A, "admin-a",
                orderBody("ORD-R7", patientA, "SRC-7", "ACC-R7", "verifier-a", "queue-a"))
                .getStatusCode().value());
    }

    @Test
    void outOfOrderCorrectionIsQueuedWithoutImporting() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-R8", patientA, "SRC-8", "ACC-R8", "verifier-a", "queue-a")).getStatusCode());
        ResponseEntity<String> outOfOrder = importResult(ORG_A, "verifier-a",
                resultBody("ORD-R8", "SRC-8", "ACC-R8", "corrected", 9));
        assertEquals(HttpStatus.ACCEPTED, outOfOrder.getStatusCode(), outOfOrder.getBody());
        assertEquals("conflict", text(outOfOrder, "/reason"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-R8'"));
    }

    @Test
    void sharedAccessionAcrossOrdersDoesNotReplayAnotherPatientsReport() throws Exception {
        String secondPatient = patientId(ORG_A, "MRN-2");
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-S1", patientA, "SRC-SHARED", "ACC-SHARED", "verifier-a", "queue-a"))
                .getStatusCode());
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-S2", secondPatient, "SRC-SHARED", "ACC-SHARED", "verifier-a", "queue-a"))
                .getStatusCode());

        ResponseEntity<String> first = importResult(ORG_A, "verifier-a",
                resultBodyForPatient("ORD-S1", "SRC-SHARED", "ACC-SHARED", patientA));
        ResponseEntity<String> second = importResult(ORG_A, "verifier-a",
                resultBodyForPatient("ORD-S2", "SRC-SHARED", "ACC-SHARED", secondPatient));
        assertEquals(HttpStatus.CREATED, first.getStatusCode(), first.getBody());
        assertEquals(HttpStatus.CREATED, second.getStatusCode(), second.getBody());

        assertFalse(text(first, "/id").equals(text(second, "/id")));
        assertEquals(patientA, text(first, "/identifiers/patientId"));
        assertEquals("ORD-S1", text(first, "/orderId"));
        assertEquals(secondPatient, text(second, "/identifiers/patientId"));
        assertEquals("ORD-S2", text(second, "/orderId"));
        assertEquals(2, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-SHARED'"));
    }

    @Test
    void concurrentOrderRegistrationProducesOneOrderAndOneConflict() throws Exception {
        String body = orderBody("ORD-CONC", patientA, "SRC-CONC", "ACC-CONC", "verifier-a", "queue-a");
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<String>> task = () -> {
            start.await();
            return register(ORG_A, "admin-a", body);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = pool.submit(task);
            Future<ResponseEntity<String>> second = pool.submit(task);
            start.countDown();
            ResponseEntity<String> a = first.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> b = second.get(30, TimeUnit.SECONDS);
            // Either request may win the race; the contract is one winner and
            // one ORDER_EXISTS conflict, not which Future was submitted first.
            assertTrue((a.getStatusCode().value() == 201 && b.getStatusCode().value() == 409)
                            || (a.getStatusCode().value() == 409 && b.getStatusCode().value() == 201),
                    "expected one 201 and one 409 but was " + a.getStatusCode()
                            + " and " + b.getStatusCode());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_expected_order "
                + "WHERE order_ref = 'ORD-CONC'"));
    }

    @Test
    void concurrentReplayProducesOneRevisionAndOneReview() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-C1", patientA, "SRC-C", "ACC-C", "verifier-a", "queue-a")).getStatusCode());

        String body = resultBodyForPatient("ORD-C1", "SRC-C", "ACC-C", patientA);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ResponseEntity<String>> task = () -> {
            start.await();
            return importResult(ORG_A, "verifier-a", body);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = pool.submit(task);
            Future<ResponseEntity<String>> second = pool.submit(task);
            start.countDown();
            ResponseEntity<String> a = first.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> b = second.get(30, TimeUnit.SECONDS);
            assertEquals(HttpStatus.CREATED, a.getStatusCode(), a.getBody());
            assertEquals(HttpStatus.CREATED, b.getStatusCode(), b.getBody());
            assertEquals(text(a, "/id"), text(b, "/id"));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-C'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_review r "
                + "JOIN public.xch04_report_revision p "
                + "ON p.organization_id = r.organization_id AND p.id = r.report_id "
                + "WHERE p.accession = 'ACC-C'"));
    }

    private static String resultBodyForPatient(String orderRef, String source, String accession,
                                               String patientId) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"final\","
                + "\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}";
    }

    private long auditCount(String action, String resourceId) throws Exception {
        return count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = '" + action + "' AND resource_id = '" + resourceId + "'");
    }
}
