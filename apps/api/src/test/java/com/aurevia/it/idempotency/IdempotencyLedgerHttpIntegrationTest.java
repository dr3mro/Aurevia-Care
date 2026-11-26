package com.aurevia.it.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

/**
 * ARC-03/ARC-04 acceptance over the real HTTP chain and V66 table.
 *
 * <p>Requests carry synthetic subjects and organizations and reach the real
 * interceptor, capture filter, JDBC ledger and forced-RLS table. A counting
 * controller makes replay-without-a-second-handler-call deterministic. Direct
 * JDBC assertions use the tenant GUC and the non-owner role, so an empty table
 * cannot make the policy probe pass falsely. No patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_idempotency_ledger_it")
@ActiveProfiles("synthetic-auth")
class IdempotencyLedgerHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT_A = "idem-a";
    private static final String SUBJECT_B = "idem-b";
    private static final String DB = "aurevia_idempotency_ledger_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @LocalServerPort
    int port;

    @Autowired
    com.aurevia.api.contract.IdempotencyResponseStore store;

    RestClient rest;

    /** Recreate a dedicated database and migrate every version, including V66. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // FORCE RLS applies to the migration owner too, so bind the tenant
            // GUC in the same session before each tenant-owned insert.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES "
                    + "('" + ORG_A + "', 'Synthetic Tenant A')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES "
                    + "('" + ORG_B + "', 'Synthetic Tenant B')");
            seedCaller(statement, ORG_A, SUBJECT_A);
            seedCaller(statement, ORG_A, SUBJECT_B);
            seedCaller(statement, ORG_B, SUBJECT_A);
            seedCaller(statement, ORG_B, SUBJECT_B);
        }
    }

    /** Seed one synthetic caller with an active membership and role in one tenant. */
    private static void seedCaller(Statement statement, String organization, String subject) throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES "
                + "('" + organization + "', '" + subject + "', 'Synthetic " + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, 'clinician', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Append a membership event and a role grant, as the staff lifecycle does. */
    private static void appendMembership(Statement statement, String organization, String subject,
                                         String status, String role) throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, '" + status + "' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' "
                + "ORDER BY m.created_at DESC LIMIT 1");
    }

    /** Drop the dedicated synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Reset the counting controller before each scenario. */
    @BeforeEach
    void setUp() {
        rest = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
        ProbeController.CALLS.set(0);
    }

    @Test
    void anIdenticalRetryReplaysTheStoredStatusAndBodyWithoutRunningTheHandlerAgain() throws Exception {
        ResponseEntity<String> first = post("idem-key-1", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        ResponseEntity<String> retry = post("idem-key-1", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);

        assertEquals(first.getStatusCode(), retry.getStatusCode());
        // JSONB canonicalisation may reorder keys on the frozen row; compare
        // the replayed values, not their printable order.
        assertEquals(1, new ObjectMapper().readTree(retry.getBody()).get("calls").asInt());
        assertEquals("a", new ObjectMapper().readTree(retry.getBody()).get("marker").asString());
        assertEquals(1, ProbeController.CALLS.get());
    }

    @Test
    void aRetryIsConcealedWhenTheCallerIsNoLongerAuthorized() throws Exception {
        post("idem-key-revoked", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // Membership is append-only and the GUC scopes both the write and the
            // authoritative fold re-read by the replay authorization check.
            appendMembership(statement, ORG_A, SUBJECT_A, "revoked", "clinician");
        }

        ResponseEntity<String> retry;
        try {
            retry = post("idem-key-revoked", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        } finally {
            // The dedicated database is shared by the suite; restore the synthetic
            // caller so a later test cannot inherit this revoked membership.
            try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
                 Statement statement = connection.createStatement()) {
                appendMembership(statement, ORG_A, SUBJECT_A, "active", "clinician");
            }
        }

        assertEquals(404, retry.getStatusCode().value());
        assertTrue(retry.getBody().contains("NOT_FOUND"));
        assertFalse(retry.getBody().contains("marker"), "a concealed replay must not disclose the cached body");
        assertEquals(1, ProbeController.CALLS.get());
    }

    @Test
    void aReplayIsConcealedWhenTheCommandRoleNoLongerAllowsTheAction() throws Exception {
        post("idem-key-role", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // Membership stays active, but the new fold grants only reception,
            // which the exchange-response policy refuses for an accept command.
            appendMembership(statement, ORG_A, SUBJECT_A, "active", "reception");
        }

        ResponseEntity<String> retry;
        try {
            retry = post("idem-key-role", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        } finally {
            try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
                 Statement statement = connection.createStatement()) {
                appendMembership(statement, ORG_A, SUBJECT_A, "active", "clinician");
            }
        }

        assertEquals(404, retry.getStatusCode().value());
        assertFalse(retry.getBody().contains("marker"), "a downgraded role must not disclose the cached body");
        assertEquals(1, ProbeController.CALLS.get());
    }

    @Test
    void theSameKeyOnTwoCommandsOwnsTwoIndependentResponses() throws Exception {
        post("idem-key-commands", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        ResponseEntity<String> second = postTo("/api/v1/idempotency/secondary",
                "idem-key-commands", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);

        assertEquals(201, second.getStatusCode().value());
        assertEquals(2, ProbeController.CALLS.get());
        assertEquals("a", new ObjectMapper().readTree(second.getBody()).get("marker").asString());
    }

    @Test
    void theSameKeyWithADifferentBodyAnswers422AndRunsNoSecondHandler() {
        post("idem-key-2", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        ResponseEntity<String> conflict = post("idem-key-2", "{\"marker\":\"b\"}", SUBJECT_A, ORG_A);

        assertEquals(422, conflict.getStatusCode().value());
        assertTrue(conflict.getBody().contains("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, ProbeController.CALLS.get());
    }

    @Test
    void keysAreScopedToTheAuthenticatedSubjectAndTheActiveOrganization() throws Exception {
        post("idem-key-3", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        ResponseEntity<String> peerSubject = post("idem-key-3", "{\"marker\":\"b\"}", SUBJECT_B, ORG_A);
        ResponseEntity<String> peerTenant = post("idem-key-3", "{\"marker\":\"c\"}", SUBJECT_A, ORG_B);

        assertEquals(201, peerSubject.getStatusCode().value());
        assertEquals(201, peerTenant.getStatusCode().value());
        assertEquals(3, ProbeController.CALLS.get());
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT subject, status_code FROM public.idempotency_record WHERE key = 'idem-key-3'")) {
                Set<String> subjects = new HashSet<>();
                int count = 0;
                while (rows.next()) {
                    subjects.add(rows.getString("subject"));
                    assertEquals(201, rows.getInt("status_code"));
                    count++;
                }
                // Forced RLS hides the third row written under ORG_B.
                assertEquals(2, count);
                assertTrue(subjects.contains(SUBJECT_A));
                assertTrue(subjects.contains(SUBJECT_B));
            }
        }
    }

    @Test
    void anExpiredRecordIsIgnoredAndTheHandlerRunsAgain() throws Exception {
        post("idem-key-4", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("UPDATE public.idempotency_record "
                    + "SET created_at = now() - interval '2 days', expires_at = now() - interval '1 second' "
                    + "WHERE key = 'idem-key-4'");
            // A second expired key is never retried directly: the next keyed
            // read in this tenant must still purge it within its own RLS scope.
            statement.execute("INSERT INTO public.idempotency_record "
                    + "(organization_id, subject, command, key, request_hash, status_code, "
                    + "response_body, created_at, expires_at) "
                    + "VALUES ('" + ORG_A + "', '" + SUBJECT_A + "', 'POST /api/v1/orders/{id}/accept', "
                    + "'idem-key-purge', '" + "a".repeat(64)
                    + "', 201, '{\"marker\":\"old\"}'::jsonb, now() - interval '2 days', now() - interval '1 second')");
        }
        ResponseEntity<String> retry = post("idem-key-4", "{\"marker\":\"a\"}", SUBJECT_A, ORG_A);

        assertEquals(201, retry.getStatusCode().value());
        assertEquals(2, ProbeController.CALLS.get());
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT expires_at FROM public.idempotency_record WHERE key = 'idem-key-4'")) {
                assertTrue(rows.next());
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT 1 FROM public.idempotency_record WHERE key = 'idem-key-purge'")) {
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void theLedgerTableHasForcedRlsAndOnlyTheRequiredRuntimeGrants() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet row = statement.executeQuery(
                    "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                            + "WHERE oid = 'public.idempotency_record'::regclass")) {
                assertTrue(row.next());
                assertTrue(row.getBoolean("relrowsecurity"));
                assertTrue(row.getBoolean("relforcerowsecurity"));
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT has_table_privilege('aurevia_app', 'public.idempotency_record', 'INSERT') AS ins, "
                            + "has_table_privilege('aurevia_app', 'public.idempotency_record', 'SELECT') AS sel, "
                            + "has_table_privilege('aurevia_app', 'public.idempotency_record', 'DELETE') AS del, "
                            + "has_table_privilege('aurevia_app', 'public.idempotency_record', 'UPDATE') AS upd")) {
                assertTrue(row.next());
                assertTrue(row.getBoolean("ins"));
                assertTrue(row.getBoolean("sel"));
                assertTrue(row.getBoolean("del"));
                assertFalse(row.getBoolean("upd"));
            }
        }
    }

    /** Post one synthetic keyed command through the real servlet filter chain. */
    private ResponseEntity<String> post(String key, String body, String subject, String organization) {
        // A catalog route so the replay's action-specific authorization check has
        // a registered command to evaluate.
        return postTo("/api/v1/orders/order-1/accept", key, body, subject, organization);
    }

    /** Post a keyed command to an explicit synthetic route. */
    private ResponseEntity<String> postTo(String uri, String key, String body, String subject, String organization) {
        return rest.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "corr-idempotency-it")
                .body(body)
                .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                        .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** Registers only in this test context; the type is not component-scanned. */
    @TestConfiguration
    static class ProbeConfiguration {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    /** Counts executions and echoes the first body so a second handler run is visible. */
    @RestController
    static class ProbeController {

        static final AtomicInteger CALLS = new AtomicInteger();
        private static final ObjectMapper JSON = new ObjectMapper();

        @PostMapping("/api/v1/orders/{id}/accept")
        ResponseEntity<String> probe(@RequestBody(required = false) String body) {
            return respond(body);
        }

        /** A second command under a distinct route for command-scoping proof. */
        @PostMapping("/api/v1/idempotency/secondary")
        ResponseEntity<String> secondary(@RequestBody(required = false) String body) {
            return respond(body);
        }

        /** Count the call and echo the marker so a second execution is visible. */
        private ResponseEntity<String> respond(String body) {
            int call = CALLS.incrementAndGet();
            try {
                String marker = JSON.readTree(body).get("marker").asString();
                return ResponseEntity.status(201)
                        .body(JSON.writeValueAsString(java.util.Map.of("marker", marker, "calls", call)));
            } catch (tools.jackson.core.JacksonException exception) {
                throw new IllegalArgumentException("malformed synthetic body", exception);
            }
        }
    }
}
