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
 * XCH-04 clause 4 API proof. A corrected result must append a new immutable
 * revision that links to the acknowledged prior revision, notify the owners of
 * earlier revisions and open its own fresh unreviewed task, while the prior
 * revision, its acknowledgment and its recipients stay untouched. The rule must
 * also refuse a correction from a subject without import permission, from a peer
 * tenant, against a non-tip version or without a named superseded version.
 *
 * <p>Every fixture is synthetic and the suite drives the real HTTP entry point
 * against a freshly migrated, isolated database. This class proves only this
 * clause; the wider identifier matching and reconciliation lifecycle is covered
 * by its owning tests.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_correct_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class CorrectionsPreservePriorVersionsNotifyPriorRecipientsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch04_correct_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String ORDERS = "/api/v1/xch04/orders";
    private static final String RESULTS = "/api/v1/xch04/results";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;
    private String patientA;
    private String patientB;

    /**
     * Bind a fresh REST client and the two tenants' synthetic patient identifiers.
     *
     * @param port randomly assigned HTTP port of the application under test
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        patientA = patientId(ORG_A);
        patientB = patientId(ORG_B);
    }

    /**
     * Create the isolated database, apply every migration and seed two tenants.
     *
     * @throws Exception when a connection, migration or seed statement fails
     */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            // The runtime and migration roles are created here so this class is
            // self-contained when the gate runs it alone with -Dtest=... .
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch04_correct_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch04_correct_it OWNER smartclinic_migrate");
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
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "verifier-b", "lab_verifier");
        }
    }

    /**
     * Remove the isolated database after the suite, whatever the test outcome.
     *
     * @throws Exception when the drop statement fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch04_correct_it WITH (FORCE)");
        }
    }

    /**
     * Seed one tenant, its bootstrap identity and two synthetic patients.
     *
     * @param statement statement on the freshly migrated test database
     * @param org       organization identifier
     * @throws Exception when a seed statement fails
     */
    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-2', 'CLINIC', 'Second Synthetic', 'second synthetic', "
                + "'1991-06-21')");
    }

    /**
     * Seed one synthetic member and a single role assignment in one tenant.
     *
     * @param statement statement on the freshly migrated test database
     * @param org       organization identifier
     * @param subject   synthetic user subject
     * @param role      role key granted in that organization
     * @throws Exception when a seed statement fails
     */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /**
     * Read the identifier of a seeded patient inside one tenant's RLS scope.
     *
     * @param organization organization identifier
     * @return the synthetic patient's opaque identifier
     */
    private static String patientId(String organization) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
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

    /**
     * Bind the transaction-local tenant and actor context used by forced RLS.
     *
     * @param connection   connection whose transaction carries the context
     * @param organization active organization identifier
     * @param subject      acting synthetic subject
     * @throws Exception when the context statement fails
     */
    private static void bind(Connection connection, String organization, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /**
     * Count rows visible in one tenant for an arbitrary SQL predicate.
     *
     * @param organization organization whose RLS scope is applied
     * @param sql          aggregate query returning a single long
     * @return the observed count
     * @throws Exception when the query fails
     */
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

    /**
     * Build the synthetic-auth headers for one request.
     *
     * @param subject      acting subject, or {@code null} to omit it
     * @param organization active organization, or {@code null} to omit it
     * @return the request headers
     */
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

    /**
     * Send one request through the real HTTP entry point.
     *
     * @param method       HTTP method
     * @param uri          request path
     * @param body         JSON body, or {@code null} when there is none
     * @param subject      acting synthetic subject
     * @param organization active organization
     * @return the untouched response so any status is assertable
     */
    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    /**
     * Send one request with extra headers such as {@code If-Match}.
     *
     * @param method       HTTP method
     * @param uri          request path
     * @param body         JSON body, or {@code null} when there is none
     * @param subject      acting synthetic subject
     * @param organization active organization
     * @param extraHeaders additional headers applied after the synthetic ones
     * @return the untouched response so any status is assertable
     */
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

    /**
     * Read one text field from a JSON response body.
     *
     * @param response response received from the HTTP entry point
     * @param pointer  JSON pointer to the field
     * @return the field as text, or {@code null} for a JSON null
     */
    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode value = MAPPER.readTree(response.getBody()).at(pointer);
            assertFalse(value.isMissingNode(), "missing field " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer + " from " + response.getBody(), exception);
        }
    }

    /**
     * Parse a JSON response body for structural assertions.
     *
     * @param response response received from the HTTP entry point
     * @return the parsed JSON tree
     */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /**
     * Build a synthetic expected-order registration body.
     *
     * @param orderRef   tenant-local order identifier
     * @param patientId  synthetic patient identifier
     * @param source     issuer source code
     * @param accession  issuer accession code
     * @param owner      named result owner
     * @param queue      coverage queue
     * @return the JSON request body
     */
    private static String orderBody(String orderRef, String patientId, String source, String accession,
                                    String owner, String queue) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"owner\":\"" + owner + "\","
                + "\"coverageQueue\":\"" + queue + "\"}";
    }

    /**
     * Build a synthetic inbound-result body with an optional superseded version.
     *
     * @param orderRef   tenant-local order identifier
     * @param patientId  synthetic patient identifier
     * @param source     issuer source code
     * @param accession  issuer accession code
     * @param kind       {@code final}, {@code corrected} or {@code critical}
     * @param supersedes superseded version, or {@code null} to omit the field
     * @return the JSON request body
     */
    private String resultBody(String orderRef, String patientId, String source, String accession,
                              String kind, Integer supersedes) {
        return "{\"orderId\":\"" + orderRef + "\",\"patientId\":\"" + patientId + "\","
                + "\"sourceId\":\"" + source + "\",\"accessionId\":\"" + accession + "\","
                + "\"destinationId\":\"CLINIC-A\",\"kind\":\"" + kind + "\""
                + (supersedes == null ? "" : ",\"supersedesVersion\":" + supersedes)
                + ",\"effectiveAt\":\"2026-11-19T12:00:00Z\",\"checksum\":\"abc123\"}";
    }

    /**
     * Register an expected order as the tenant administrator.
     *
     * @param organization active organization
     * @param admin        tenant administrator subject
     * @param body         registration body
     * @return the HTTP response
     */
    private ResponseEntity<String> register(String organization, String admin, String body) {
        return send(HttpMethod.POST, ORDERS, body, admin, organization);
    }

    /**
     * Import one inbound result as the given subject.
     *
     * @param organization active organization
     * @param subject      acting synthetic subject
     * @param body         result body
     * @return the HTTP response
     */
    private ResponseEntity<String> importResult(String organization, String subject, String body) {
        return send(HttpMethod.POST, RESULTS, body, subject, organization);
    }

    /**
     * Count audit rows for one action and resource in organization A.
     *
     * @param action     audit action key
     * @param resourceId audited resource identifier
     * @return the observed audit count
     * @throws Exception when the query fails
     */
    private static long auditCount(String action, String resourceId) throws Exception {
        return count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = '" + action + "' AND resource_id = '" + resourceId + "'");
    }

    @Test
    void correctionPreservesPriorRevisionNotifiesPriorRecipientAndReopensAcknowledgedReview() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-P", patientA, "SRC-CORR-P", "ACC-CORR-P", "verifier-a", "queue-a"))
                .getStatusCode());

        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-P", patientA, "SRC-CORR-P", "ACC-CORR-P", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());
        assertEquals("1", text(original, "/version"));
        String firstId = text(original, "/id");

        ResponseEntity<String> acknowledged = send(HttpMethod.POST, RESULTS + "/" + firstId + "/acknowledge",
                null, "clinician-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, acknowledged.getStatusCode(), acknowledged.getBody());
        assertEquals("acknowledged", text(acknowledged, "/state"));

        ResponseEntity<String> corrected = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-P", patientA, "SRC-CORR-P", "ACC-CORR-P", "corrected", 1));
        assertEquals(HttpStatus.CREATED, corrected.getStatusCode(), corrected.getBody());
        assertEquals("2", text(corrected, "/version"));
        assertEquals(firstId, text(corrected, "/previousId"));
        String secondId = text(corrected, "/id");

        assertEquals(2, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CORR-P'"));
        assertEquals("acknowledged", text(send(HttpMethod.GET, RESULTS + "/" + firstId, null,
                "clinician-a", ORG_A), "/review/state"));

        ResponseEntity<String> secondDetail = send(HttpMethod.GET, RESULTS + "/" + secondId, null,
                "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, secondDetail.getStatusCode(), secondDetail.getBody());
        assertEquals("unreviewed", text(secondDetail, "/review/state"));
        assertEquals(2, json(secondDetail).at("/lineage").size());
        assertEquals(1, json(secondDetail).at("/notifications").size());
        assertEquals("verifier-a", text(secondDetail, "/notifications/0/recipient"));
        assertEquals("1", text(secondDetail, "/notifications/0/supersededVersion"));
        assertEquals(1, auditCount("XCH04_REVIEW_REOPENED", firstId));
    }

    @Test
    void correctionBySubjectWithoutImportRoleIsForbidden() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-ROLE", patientA, "SRC-CORR-ROLE", "ACC-CORR-ROLE", "verifier-a", "queue-a"))
                .getStatusCode());
        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-ROLE", patientA, "SRC-CORR-ROLE", "ACC-CORR-ROLE", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());
        String firstId = text(original, "/id");

        ResponseEntity<String> denied = importResult(ORG_A, "reception-a",
                resultBody("ORD-CORR-ROLE", patientA, "SRC-CORR-ROLE", "ACC-CORR-ROLE", "corrected", 1));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());

        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CORR-ROLE'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_notification "
                + "WHERE report_id = '" + firstId + "'"));
    }

    @Test
    void correctionFromPeerTenantCannotModifyHomeTenantData() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-TENANT", patientA, "SRC-CORR-TENANT", "ACC-CORR-TENANT",
                        "verifier-a", "queue-a")).getStatusCode());
        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-TENANT", patientA, "SRC-CORR-TENANT", "ACC-CORR-TENANT", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());
        String firstId = text(original, "/id");

        // A subject with no membership in the active tenant is denied outright.
        ResponseEntity<String> noMembership = importResult(ORG_A, "verifier-b",
                resultBody("ORD-CORR-TENANT", patientA, "SRC-CORR-TENANT", "ACC-CORR-TENANT", "corrected", 1));
        assertEquals(HttpStatus.FORBIDDEN, noMembership.getStatusCode(), noMembership.getBody());

        // Forced RLS conceals organization A's revision from organization B.
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, RESULTS + "/" + firstId, null,
                "verifier-b", ORG_B).getStatusCode());

        // Using its own tenant and patient, organization B sees no matching order
        // and diverts the would-be correction; organization A's data is untouched.
        ResponseEntity<String> diverted = importResult(ORG_B, "verifier-b",
                resultBody("ORD-CORR-TENANT", patientB, "SRC-CORR-TENANT", "ACC-CORR-TENANT", "corrected", 1));
        assertEquals(HttpStatus.ACCEPTED, diverted.getStatusCode(), diverted.getBody());
        assertEquals("unmatched", text(diverted, "/reason"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CORR-TENANT'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_notification "
                + "WHERE report_id = '" + firstId + "'"));
    }

    @Test
    void correctionWithNonTipSupersedesVersionIsQueuedNotImported() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-STALE", patientA, "SRC-CORR-STALE", "ACC-CORR-STALE",
                        "verifier-a", "queue-a")).getStatusCode());
        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-STALE", patientA, "SRC-CORR-STALE", "ACC-CORR-STALE", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());

        ResponseEntity<String> queued = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-STALE", patientA, "SRC-CORR-STALE", "ACC-CORR-STALE", "corrected", 9));
        assertEquals(HttpStatus.ACCEPTED, queued.getStatusCode(), queued.getBody());
        assertEquals("conflict", text(queued, "/reason"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CORR-STALE'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_notification "
                + "WHERE report_id = '" + text(original, "/id") + "'"));
    }

    @Test
    void correctedResultWithoutSupersedesVersionIsRefused() throws Exception {
        assertEquals(HttpStatus.CREATED, register(ORG_A, "admin-a",
                orderBody("ORD-CORR-REQ", patientA, "SRC-CORR-REQ", "ACC-CORR-REQ", "verifier-a", "queue-a"))
                .getStatusCode());
        ResponseEntity<String> original = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-REQ", patientA, "SRC-CORR-REQ", "ACC-CORR-REQ", "final", null));
        assertEquals(HttpStatus.CREATED, original.getStatusCode(), original.getBody());
        String firstId = text(original, "/id");

        ResponseEntity<String> missing = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-REQ", patientA, "SRC-CORR-REQ", "ACC-CORR-REQ", "corrected", null));
        assertEquals(422, missing.getStatusCode().value(), missing.getBody());

        ResponseEntity<String> forbidden = importResult(ORG_A, "verifier-a",
                resultBody("ORD-CORR-REQ", patientA, "SRC-CORR-REQ", "ACC-CORR-REQ", "final", 1));
        assertEquals(422, forbidden.getStatusCode().value(), forbidden.getBody());

        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.xch04_report_revision "
                + "WHERE accession = 'ACC-CORR-REQ'"));
        assertEquals(0, count(ORG_A, "SELECT count(*) FROM public.xch04_notification "
                + "WHERE report_id = '" + firstId + "'"));
    }
}
