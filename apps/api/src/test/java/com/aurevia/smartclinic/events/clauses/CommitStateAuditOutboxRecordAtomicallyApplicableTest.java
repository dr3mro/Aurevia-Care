package com.aurevia.smartclinic.events.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.events.DeliveryStore;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-05 clause 1 HTTP acceptance on real PostgreSQL.
 *
 * <p>One publish must commit the outbox row, its append-only audit fact and its
 * tenant, sender and type scoped idempotency key in the same transaction, and a
 * refused publish must leave no partial state. Every command goes through the
 * real HTTP entry point so the tenant-guarded transaction binds
 * {@code app.current_org} and {@code app.actor}; direct JDBC checks bind the
 * same tenant explicitly under forced row-level security. Identifiers and
 * digests are synthetic and the peer organization uses the same local event
 * vocabulary, so isolation is tested at the tenant boundary.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_delivery_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CommitStateAuditOutboxRecordAtomicallyApplicableTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String AGGREGATE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String INVALID_AGGREGATE = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String SENDER = "lab-partner";
    private static final String EVENT_TYPE = "observation.result";
    private static final String NAMESPACE = "partner-lab";
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);
    private static final String DB = "smartclinic_delivery_clause_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DeliveryStore deliveryStore;

    @LocalServerPort
    int port;

    RestClient rest;

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
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "worker-a", "worker");
            seedUser(statement, ORG_A, "operator-a", "operator");
            seedUser(statement, ORG_A, "admin-a", "integration_admin");
            seedUser(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedUser(statement, ORG_B, "worker-b", "worker");
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

    /* Seed a synthetic member whose role is active only in its own organization. */
    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private ResponseEntity<String> request(String method, String uri, String body,
                                            String subject, String org) {
        return request(method, uri, body, subject, org, null, UUID.randomUUID().toString());
    }

    private ResponseEntity<String> request(String method, String uri, String body,
                                            String subject, String org, String ifMatch) {
        return request(method, uri, body, subject, org, ifMatch, UUID.randomUUID().toString());
    }

    /* The correlation marker is carried into the audit fact, so a test can fail the audit insert deterministically. */
    private ResponseEntity<String> request(String method, String uri, String body,
                                            String subject, String org, String ifMatch, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("X-Correlation-Id", correlationId);
        if (ifMatch != null) {
            headers.set("If-Match", "\"" + ifMatch + "\"");
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(HttpMethod.valueOf(method))
                .uri(uri).headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { }).toEntity(String.class);
    }

    private static String publishBody(String aggregate, String key, String digest) {
        return "{\"aggregateId\":\"" + aggregate + "\",\"aggregateVersion\":1"
                + ",\"sender\":\"" + SENDER + "\",\"eventType\":\"" + EVENT_TYPE + "\""
                + ",\"namespace\":\"" + NAMESPACE + "\",\"payloadDigest\":\"" + digest + "\""
                + ",\"idempotencyKey\":\"" + key + "\"}";
    }

    private ResponseEntity<String> publish(String key, String digest) {
        return request("POST", "/api/v1/delivery/events", publishBody(AGGREGATE, key, digest),
                "worker-a", ORG_A);
    }

    private String publishAndExpectCreated(String key) {
        ResponseEntity<String> response = publish(key, DIGEST);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), response.getBody());
        return field(response.getBody(), "eventId");
    }

    private static String newKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    /* Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "DELIVERY", "delivery-clause-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static int countRows(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /* A regular trigger scoped to this dedicated database; it is dropped by the caller and by cleanUp. */
    private static void installRollbackTrigger(String marker) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION public.fail_delivery_audit_force_rollback() "
                    + "RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$ "
                    + "BEGIN IF NEW.correlation_id = '" + marker + "' THEN "
                    + "RAISE EXCEPTION 'synthetic audit failure' USING ERRCODE = '23514'; "
                    + "END IF; RETURN NEW; END $fn$");
            statement.execute("GRANT EXECUTE ON FUNCTION public.fail_delivery_audit_force_rollback() "
                    + "TO smartclinic_app");
            statement.execute("CREATE TRIGGER delivery_audit_force_rollback "
                    + "BEFORE INSERT ON public.delivery_audit "
                    + "FOR EACH ROW EXECUTE FUNCTION public.fail_delivery_audit_force_rollback()");
        }
    }

    private static void removeRollbackTrigger() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS delivery_audit_force_rollback ON public.delivery_audit");
            statement.execute("DROP FUNCTION IF EXISTS public.fail_delivery_audit_force_rollback()");
        }
    }

    @Test
    void publishCommitsOutboxAuditAndDedupKeyTogether() throws Exception {
        String key = newKey("commit");
        String eventId = publishAndExpectCreated(key);

        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE id = CAST('" + eventId + "' AS uuid) AND state = 'pending' "
                + "AND idempotency_key = '" + key + "'"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE idempotency_key = '" + key + "'"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_audit "
                + "WHERE resource_id = '" + eventId + "' AND purpose = 'DELIVERY' "
                + "AND action = 'publish' AND outcome = 'success'"));
        assertTrue(as(ORG_A, "worker-a", () -> deliveryStore
                .byIdempotencyKey(ORG_A, SENDER, EVENT_TYPE, key)
                .map(event -> eventId.equals(event.eventId())).orElse(false)));
    }

    @Test
    void workerWithoutPublishRoleCannotWriteOutboxOrAudit() throws Exception {
        String key = newKey("wrong-role");
        ResponseEntity<String> refused = request("POST", "/api/v1/delivery/events",
                publishBody(AGGREGATE, key, DIGEST), "operator-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", field(refused.getBody(), "code"));
        assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE idempotency_key = '" + key + "'"));
        assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.delivery_audit "
                + "WHERE resource_id = '" + AGGREGATE + "' AND action = 'publish' AND outcome = 'success'"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_audit "
                + "WHERE resource_id = '" + AGGREGATE + "' AND action = 'publish' AND outcome = 'denied'"));
    }

    @Test
    void peerTenantCannotSeeOrTransitionThePublishedEvent() throws Exception {
        String eventId = publishAndExpectCreated(newKey("peer-tenant"));
        assertEquals(0, countRows(ORG_B, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE id = CAST('" + eventId + "' AS uuid)"));
        assertEquals(0, countRows(ORG_B, "SELECT count(*) FROM public.delivery_audit "
                + "WHERE resource_id = '" + eventId + "'"));

        ResponseEntity<String> refused = request("POST", "/api/v1/delivery/events/" + eventId + "/sent",
                null, "worker-b", ORG_B, "1");
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE id = CAST('" + eventId + "' AS uuid) AND state = 'pending'"));
    }

    @Test
    void missingIdempotencyKeyIsRejectedWithoutWriting() throws Exception {
        ResponseEntity<String> blank = request("POST", "/api/v1/delivery/events",
                publishBody(INVALID_AGGREGATE, "   ", DIGEST), "worker-a", ORG_A);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, blank.getStatusCode(), blank.getBody());
        assertEquals("INVALID_IDEMPOTENCYKEY", field(blank.getBody(), "code"));

        ResponseEntity<String> missing = request("POST", "/api/v1/delivery/events",
                "{\"aggregateId\":\"" + INVALID_AGGREGATE + "\",\"aggregateVersion\":1"
                        + ",\"sender\":\"" + SENDER + "\",\"eventType\":\"" + EVENT_TYPE + "\""
                        + ",\"namespace\":\"" + NAMESPACE + "\",\"payloadDigest\":\"" + DIGEST + "\"}",
                "worker-a", ORG_A);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, missing.getStatusCode(), missing.getBody());
        assertEquals("INVALID_IDEMPOTENCYKEY", field(missing.getBody(), "code"));

        ResponseEntity<String> malformed = request("POST", "/api/v1/delivery/events",
                publishBody(INVALID_AGGREGATE, newKey("invalid-digest"), "not-a-digest"), "worker-a", ORG_A);
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, malformed.getStatusCode(), malformed.getBody());
        assertEquals("INVALID_DIGEST", field(malformed.getBody(), "code"));

        assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE aggregate_id = CAST('" + INVALID_AGGREGATE + "' AS uuid)"));
        assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.delivery_audit "
                + "WHERE resource_id = '" + INVALID_AGGREGATE + "'"));
    }

    @Test
    void sameKeyWithDifferentDigestConflictsWithoutASecondWrite() throws Exception {
        String key = newKey("conflict");
        String eventId = publishAndExpectCreated(key);
        ResponseEntity<String> conflict = publish(key, OTHER_DIGEST);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals("IDEMPOTENCY_CONFLICT", field(conflict.getBody(), "code"));

        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE idempotency_key = '" + key + "'"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                + "WHERE id = CAST('" + eventId + "' AS uuid) AND idempotency_key = '" + key + "'"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_audit "
                + "WHERE resource_id = '" + eventId + "' AND action = 'publish' AND outcome = 'success'"));
        assertEquals(1, countRows(ORG_A, "SELECT count(*) FROM public.delivery_audit "
                + "WHERE resource_id = '" + eventId + "' AND action = 'publish' AND outcome = 'conflict'"));
    }

    @Test
    void publishRollsBackOutboxWhenAuditWriteFails() throws Exception {
        String marker = UUID.randomUUID().toString();
        String key = newKey("rollback");
        installRollbackTrigger(marker);
        try {
            ResponseEntity<String> refused = request("POST", "/api/v1/delivery/events",
                    publishBody(AGGREGATE, key, DIGEST), "worker-a", ORG_A, null, marker);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, refused.getStatusCode(), refused.getBody());
            assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.delivery_outbox "
                    + "WHERE idempotency_key = '" + key + "'"));
            assertEquals(0, countRows(ORG_A, "SELECT count(*) FROM public.delivery_audit "
                    + "WHERE correlation_id = '" + marker + "'"));
            assertTrue(as(ORG_A, "worker-a", () -> deliveryStore
                    .byIdempotencyKey(ORG_A, SENDER, EVENT_TYPE, key).isEmpty()));
        } finally {
            removeRollbackTrigger();
        }
    }
}
