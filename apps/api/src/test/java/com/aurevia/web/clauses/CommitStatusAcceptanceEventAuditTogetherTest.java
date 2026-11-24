package com.aurevia.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-04 clause 3 HTTP acceptance on real PostgreSQL: accepting an exchanged
 * order commits its status change, its acceptance event and its audit fact in
 * one transaction, and a command that is refused or whose audit write fails
 * commits none of them.
 *
 * <p>Every assertion reaches the real controller through HTTP under the
 * synthetic-auth profile and then reads the durable projection back with
 * tenant-bound JDBC under forced row-level security. The actor, tenant and
 * correlation id come only from request headers, never from the body. The
 * two organizations seed the same local order codes, so isolation is proven by
 * RLS rather than by distinct names. One order is reserved per test because the
 * response and audit ledgers are append-only. All identifiers and narratives
 * are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/"
                + CommitStatusAcceptanceEventAuditTogetherTest.DB)
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CommitStatusAcceptanceEventAuditTogetherTest {

    static final String DB = "aurevia_xch03_atomicity_it";

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CHECKSUM = "a".repeat(64);
    private static final String VERIFIER_A = "lab-verifier-1111";
    private static final String VERIFIER_B = "lab-verifier-2222";
    private static final String AUDITOR_A = "auditor-1111";
    private static final String ACCEPT_ACTION = "EXCHANGE_ACCEPT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int ORDER_COUNT = 8;
    private static final int IDX_ACCEPT = 1;
    private static final int IDX_AUDIT_FAILURE = 2;
    private static final int IDX_WRONG_ROLE = 3;
    private static final int IDX_PEER = 4;
    private static final int IDX_STALE = 5;
    private static final int IDX_MISSING_PATIENT = 6;
    private static final int IDX_TERMINAL = 7;
    private static final int IDX_DEFERRED_FAILURE = 8;

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate the dedicated database, apply Flyway and seed two synthetic tenants. */
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
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Drop the dedicated synthetic database so later runs start from a clean ledger. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Seed one tenant, its members, one synthetic patient and one order per test. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        // The V4 role-assignment trigger resolves granted_by from app.actor in the
        // active tenant, so the grantor identity must exist before any assignment.
        for (String member : new String[] {"admin", "lab-verifier", "clinician", "reception", "auditor"}) {
            String subject = member + "-" + shortName;
            String role = switch (member) {
                case "lab-verifier" -> "lab_verifier";
                case "clinician" -> "clinician";
                case "reception" -> "reception";
                case "auditor" -> "privacy_auditor";
                default -> "tenant_admin";
            };
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + org + "', '" + subject + "', 'Synthetic " + member + "')");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                    + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
            statement.execute("INSERT INTO public.role_assignment "
                    + "(organization_id, membership_id, role_key, granted_by) "
                    + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                    + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                    + "WHERE u.subject = '" + subject + "'");
        }
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
        for (int index = 1; index <= ORDER_COUNT; index++) {
            statement.execute("INSERT INTO public.xch03_order "
                    + "(organization_id, id, order_code, state, revision, content_checksum, created_by) "
                    + "VALUES ('" + org + "', '" + orderId(org, index) + "', 'LAB-REQ-" + index
                    + "', 'requested', 1, '" + CHECKSUM
                    + "', (SELECT id FROM public.user_identity WHERE subject = 'admin-" + shortName + "'))");
            statement.execute("SET app.actor = 'admin-" + shortName + "'");
            statement.execute("INSERT INTO public.xch03_revision "
                    + "(organization_id, id, order_id, revision, previous_revision, content_checksum) "
                    + "VALUES ('" + org + "', gen_random_uuid(), '" + orderId(org, index)
                    + "', 1, NULL, '" + CHECKSUM + "')");
            statement.execute("INSERT INTO public.xch03_event "
                    + "(organization_id, id, order_id, order_version, action, from_state, to_state) "
                    + "VALUES ('" + org + "', gen_random_uuid(), '" + orderId(org, index)
                    + "', 1, 'received', NULL, 'requested')");
        }
    }

    /** Both tenants use the same local order codes: only forced RLS separates them. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    /** Read the tenant-local id of the synthetic patient used by accept. */
    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin-" + org.substring(0, 4));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next(), "the synthetic patient must exist");
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
        }
    }

    /** Bind a JDBC session to the caller's tenant and actor so forced RLS applies to direct reads. */
    private static void bind(Connection connection, String org, String actor) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, actor);
            statement.execute();
        }
    }

    /** Open a read-only JDBC session bound to the caller's tenant and actor. */
    private static Connection scoped(String org, String actor) throws Exception {
        Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
        connection.setAutoCommit(false);
        bind(connection, org, actor);
        return connection;
    }

    /** Issue one synthetic command through the real HTTP controller. */
    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                        String ifMatch, String correlation) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", correlation)
                .header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse response body", exception);
        }
    }

    private JsonNode view(String id, String subject, String org) {
        return json(get("/api/v1/xch03/orders/" + id, subject, org));
    }

    private static String acceptBody(String patient, String assignee, String purpose, String retention) {
        return "{\"localPatientId\":\"" + patient + "\",\"assignee\":\"" + assignee + "\","
                + "\"permittedPurpose\":\"" + purpose + "\",\"retentionPolicy\":\"" + retention + "\"}";
    }

    /** Read the durable order projection with the tenant's RLS policy enforced. */
    private static Projection projection(String org, String actor, String id) throws Exception {
        Connection connection = scoped(org, actor);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT state, version FROM public.xch03_order WHERE id = CAST(? AS uuid)")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next(), "the order must remain visible to its own tenant");
                return new Projection(rows.getString("state"), rows.getInt("version"));
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count durable response events of one action for an order. */
    private static long eventCount(String org, String actor, String id, String action) throws Exception {
        Connection connection = scoped(org, actor);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.xch03_event "
                        + "WHERE order_id = CAST(? AS uuid) AND action = ?")) {
            query.setString(1, id);
            query.setString(2, action);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count durable response events of one action across a specific state transition. */
    private static long transitionCount(String org, String actor, String id, String action,
                                        String fromState, String toState) throws Exception {
        Connection connection = scoped(org, actor);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.xch03_event WHERE order_id = CAST(? AS uuid) "
                        + "AND action = ? AND from_state = ? AND to_state = ?")) {
            query.setString(1, id);
            query.setString(2, action);
            query.setString(3, fromState);
            query.setString(4, toState);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /** Count successful accept audit facts carrying one request's correlation id. */
    private static long auditSuccessCount(String org, String actor, String id, String correlation)
            throws Exception {
        Connection connection = scoped(org, actor);
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT count(*) FROM public.audit_event WHERE resource_id = ? "
                        + "AND action = ? AND outcome = 'SUCCESS' AND correlation_id = ?")) {
            query.setString(1, id);
            query.setString(2, ACCEPT_ACTION);
            query.setString(3, correlation);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } finally {
            connection.rollback();
            connection.close();
        }
    }

    /**
     * Install a temporary trigger that rejects only the audit fact of this
     * request. The failure lands after the state update and event append, so a
     * rollback of those earlier writes proves they share the audit insert's
     * transaction. The predicate is this test's own unique correlation id.
     */
    private static void installAuditFailure(String marker) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION public.clause_test_fail_accept_audit() "
                    + "RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$ "
                    + "BEGIN IF NEW.correlation_id = '" + marker + "' THEN "
                    + "RAISE EXCEPTION 'synthetic accept audit failure'; "
                    + "END IF; RETURN NEW; END $fn$");
            // New functions grant EXECUTE to PUBLIC by default; keep this test-only
            // trigger callable by the application role alone.
            statement.execute("REVOKE EXECUTE ON FUNCTION public.clause_test_fail_accept_audit() FROM PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION public.clause_test_fail_accept_audit() "
                    + "TO aurevia_app");
            statement.execute("CREATE TRIGGER clause_test_fail_accept_audit BEFORE INSERT "
                    + "ON public.audit_event FOR EACH ROW "
                    + "EXECUTE FUNCTION public.clause_test_fail_accept_audit()");
        }
    }

    /** Remove the temporary audit fault-injection trigger. */
    private static void removeAuditFailure() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS clause_test_fail_accept_audit ON public.audit_event");
            statement.execute("DROP FUNCTION IF EXISTS public.clause_test_fail_accept_audit()");
        }
    }

    /**
     * Install a deferred constraint trigger that fails this order's commit,
     * after the audit fact has already been appended inside the command's
     * transaction. If the audit fact used a transaction of its own, it would
     * survive and the audit assertion in the test would detect that.
     */
    private static void installDeferredCommitFailure(String orderId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE OR REPLACE FUNCTION public.clause_test_fail_order_commit() "
                    + "RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $fn$ "
                    + "BEGIN IF NEW.id = CAST('" + orderId + "' AS uuid) THEN "
                    + "RAISE EXCEPTION 'synthetic order commit failure'; "
                    + "END IF; RETURN NULL; END $fn$");
            statement.execute("REVOKE EXECUTE ON FUNCTION public.clause_test_fail_order_commit() FROM PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION public.clause_test_fail_order_commit() TO aurevia_app");
            statement.execute("CREATE CONSTRAINT TRIGGER clause_test_fail_order_commit AFTER UPDATE "
                    + "ON public.xch03_order DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
                    + "EXECUTE FUNCTION public.clause_test_fail_order_commit()");
        }
    }

    /** Remove the temporary deferred commit-failure trigger. */
    private static void removeDeferredCommitFailure() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS clause_test_fail_order_commit ON public.xch03_order");
            statement.execute("DROP FUNCTION IF EXISTS public.clause_test_fail_order_commit()");
        }
    }

    /** Durable state and version that the HTTP response also reports. */
    private record Projection(String state, int version) {
    }

    /**
     * Positive proof: one accept commits the status change, the acceptance
     * event and the correlated audit fact.
     */
    @Test
    void acceptCommitsStatusEventAndAuditTogether() throws Exception {
        String id = orderId(ORG_A, IDX_ACCEPT);
        String patient = patientId(ORG_A);
        String correlation = UUID.randomUUID().toString();

        ResponseEntity<String> accepted = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "LEGAL_HOLD"),
                VERIFIER_A, ORG_A, "1", correlation);

        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals(correlation, accepted.getHeaders().getFirst("X-Correlation-Id"));
        JsonNode result = json(accepted);
        assertEquals("accepted", result.get("state").asText());
        assertEquals(2, result.get("version").asInt());

        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("accepted", detail.at("/order/state").asText());
        assertEquals(2, detail.at("/history").size(), "seeded receive event plus one accept event");
        JsonNode event = detail.at("/history/1");
        assertEquals("accept", event.get("action").asText());
        assertEquals("requested", event.get("fromState").asText());
        assertEquals("accepted", event.get("toState").asText());

        Projection committed = projection(ORG_A, VERIFIER_A, id);
        assertEquals("accepted", committed.state());
        assertEquals(2, committed.version());
        assertEquals(1L, eventCount(ORG_A, VERIFIER_A, id, "accept"));
        assertEquals(1L, transitionCount(ORG_A, VERIFIER_A, id, "accept", "requested", "accepted"));
        assertEquals(1L, auditSuccessCount(ORG_A, VERIFIER_A, id, correlation));
    }

    /**
     * All-or-nothing proof: when the audit insert fails, the status change and
     * the acceptance event written earlier in the same command are rolled back.
     */
    @Test
    void auditFailureRollsBackStatusAndAcceptanceEvent() throws Exception {
        String id = orderId(ORG_A, IDX_AUDIT_FAILURE);
        String patient = patientId(ORG_A);
        String marker = UUID.randomUUID().toString();
        installAuditFailure(marker);
        try {
            ResponseEntity<String> failed = post("/api/v1/xch03/orders/" + id + "/accept",
                    acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                    VERIFIER_A, ORG_A, "1", marker);

            assertEquals(500, failed.getStatusCode().value(), failed.getBody());
            // Without the trigger the same command returns 200 (positive test), so
            // the failure plus the rollback below tie the 500 to this marker's fact.
            assertEquals(marker, failed.getHeaders().getFirst("X-Correlation-Id"));
            Projection unchanged = projection(ORG_A, VERIFIER_A, id);
            assertEquals("requested", unchanged.state());
            assertEquals(1, unchanged.version());
            assertEquals(0L, eventCount(ORG_A, VERIFIER_A, id, "accept"));
            assertEquals(0L, auditSuccessCount(ORG_A, VERIFIER_A, id, marker));
        } finally {
            removeAuditFailure();
        }
    }

    /**
     * Reverse-direction proof: the audit fact is appended before the outer
     * commit, so a failure at commit must remove it together with the status
     * change and the event. An audit fact committed on its own would survive.
     */
    @Test
    void auditFactRollsBackWhenOrderCommitFails() throws Exception {
        String id = orderId(ORG_A, IDX_DEFERRED_FAILURE);
        String correlation = UUID.randomUUID().toString();
        installDeferredCommitFailure(id);
        try {
            ResponseEntity<String> failed = post("/api/v1/xch03/orders/" + id + "/accept",
                    acceptBody(patientId(ORG_A), "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                    VERIFIER_A, ORG_A, "1", correlation);

            assertEquals(500, failed.getStatusCode().value(), failed.getBody());
            assertEquals(correlation, failed.getHeaders().getFirst("X-Correlation-Id"));
            Projection unchanged = projection(ORG_A, VERIFIER_A, id);
            assertEquals("requested", unchanged.state());
            assertEquals(1, unchanged.version());
            assertEquals(0L, eventCount(ORG_A, VERIFIER_A, id, "accept"));
            assertEquals(0L, auditSuccessCount(ORG_A, VERIFIER_A, id, correlation));
        } finally {
            removeDeferredCommitFailure();
        }
    }

    /** A same-tenant role without exchange permission cannot accept. */
    @Test
    void wrongRoleCannotAccept() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);
        String correlation = UUID.randomUUID().toString();

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                AUDITOR_A, ORG_A, "1", correlation);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        assertEquals(0L, eventCount(ORG_A, VERIFIER_A, id, "accept"));
        assertEquals(0L, auditSuccessCount(ORG_A, VERIFIER_A, id, correlation));
        Projection unchanged = projection(ORG_A, VERIFIER_A, id);
        assertEquals("requested", unchanged.state());
        assertEquals(1, unchanged.version());
    }

    /** A peer tenant cannot see or accept another tenant's order. */
    @Test
    void peerTenantCannotAccept() throws Exception {
        String id = orderId(ORG_A, IDX_PEER);
        String correlation = UUID.randomUUID().toString();

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                VERIFIER_B, ORG_B, "1", correlation);

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        assertEquals(0L, eventCount(ORG_A, VERIFIER_A, id, "accept"));
        assertEquals(0L, auditSuccessCount(ORG_A, VERIFIER_A, id, correlation));
        // The peer's own tenant must not hold a fact for the concealed order either.
        assertEquals(0L, auditSuccessCount(ORG_B, VERIFIER_B, id, correlation));
        Projection unchanged = projection(ORG_A, VERIFIER_A, id);
        assertEquals("requested", unchanged.state());
        assertEquals(1, unchanged.version());
    }

    /** Reusing a consumed version is refused without a second commit. */
    @Test
    void staleIfMatchIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        String patient = patientId(ORG_A);
        ResponseEntity<String> first = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                VERIFIER_A, ORG_A, "1", UUID.randomUUID().toString());
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());

        String staleCorrelation = UUID.randomUUID().toString();
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                VERIFIER_A, ORG_A, "1", staleCorrelation);

        assertEquals(HttpStatus.PRECONDITION_FAILED, refused.getStatusCode(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        assertEquals(1L, eventCount(ORG_A, VERIFIER_A, id, "accept"), "the stale command appends no second event");
        assertEquals(0L, auditSuccessCount(ORG_A, VERIFIER_A, id, staleCorrelation));
        Projection unchanged = projection(ORG_A, VERIFIER_A, id);
        assertEquals("accepted", unchanged.state());
        assertEquals(2, unchanged.version());
    }

    /** A command without the verified local association never reaches the transaction. */
    @Test
    void missingLocalPatientIdIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_MISSING_PATIENT);
        String correlation = UUID.randomUUID().toString();

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                "{\"assignee\":\"clinician-1111\",\"permittedPurpose\":\"RESULT_REVIEW\","
                        + "\"retentionPolicy\":\"STANDARD\"}",
                VERIFIER_A, ORG_A, "1", correlation);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_INPUT", json(refused).get("code").asText());
        assertEquals(0L, eventCount(ORG_A, VERIFIER_A, id, "accept"));
        assertEquals(0L, auditSuccessCount(ORG_A, VERIFIER_A, id, correlation));
        Projection unchanged = projection(ORG_A, VERIFIER_A, id);
        assertEquals("requested", unchanged.state());
        assertEquals(1, unchanged.version());
    }

    /** A terminal order cannot be accepted, so none of the three writes happen. */
    @Test
    void acceptAfterRejectionIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_TERMINAL);
        String patient = patientId(ORG_A);
        ResponseEntity<String> rejected = post("/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"duplicate order\"}", VERIFIER_A, ORG_A, "1", UUID.randomUUID().toString());
        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertEquals("rejected", json(rejected).get("state").asText());

        String acceptCorrelation = UUID.randomUUID().toString();
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                VERIFIER_A, ORG_A, "2", acceptCorrelation);

        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        assertEquals(0L, eventCount(ORG_A, VERIFIER_A, id, "accept"));
        assertEquals(0L, auditSuccessCount(ORG_A, VERIFIER_A, id, acceptCorrelation));
        Projection unchanged = projection(ORG_A, VERIFIER_A, id);
        assertEquals("rejected", unchanged.state());
        assertEquals(2, unchanged.version());
    }
}
