package com.aurevia.smartclinic.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ARC-04 clause 1 API evidence on real PostgreSQL: what the implemented accept
 * entry point enforces today through the normal HTTP route.
 *
 * <p>The clause names {@code POST /api/v1/orders/{id}/accept}; the route that
 * actually exists is {@code POST /api/v1/xch03/orders/{id}/accept}. This suite
 * proves the implemented route's authenticated destination context, role
 * policy, tenant concealment, version precondition and transition guard, and
 * that a successful accept commits state, event, retained copy and audit in one
 * transaction. It deliberately does not claim an {@code Idempotency-Key} rule:
 * the controller binds no such header, so that part of the clause is recorded
 * as a gap in the companion document instead of as a test.</p>
 *
 * <p>Every assertion goes through the real Spring HTTP wiring under the
 * synthetic-auth profile. The actor and tenant come only from the synthetic
 * request context; no request-body field can supply them. Two tenants share
 * the same local order codes, so forced row-level security is the only
 * separator. Each test owns a distinct order because the ledgers are
 * append-only. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/"
                + PostApiV1OrdersIdAcceptRequiresTest.DB)
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PostApiV1OrdersIdAcceptRequiresTest {

    static final String DB = "smartclinic_arc04_accept_requires_it";

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CHECKSUM = "a".repeat(64);
    private static final String VERIFIER_A = "lab-verifier-1111";
    private static final String VERIFIER_B = "lab-verifier-2222";
    private static final String AUDITOR_A = "auditor-1111";
    private static final String ACCEPT_ACTION = "EXCHANGE_ACCEPT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // One order per test: the response history and retained-copy ledgers are
    // append-only, so sharing an order would leak advanced versions between cases.
    private static final int ORDER_COUNT = 5;
    private static final int IDX_ACCEPT = 1;
    private static final int IDX_WRONG_ROLE = 2;
    private static final int IDX_PEER = 3;
    private static final int IDX_STALE = 4;
    private static final int IDX_TERMINAL = 5;

    private RestClient rest;

    /** Build an HTTP client against the random port chosen by Spring Boot. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Recreate the synthetic database from the authoritative migrations and seed
     * two tenants that share the same local order codes.
     *
     * @throws Exception when the database, roles, migration or seed data cannot be prepared
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER smartclinic_migrate");
        }
        Flyway.configure().dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Drop the synthetic database so the fixture starts clean on the next run. */
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

    /** Bind the synthetic actor and tenant for a direct database assertion. */
    private static void bind(Connection connection, String org, String subject) throws Exception {
        String suffix = ORG_A.equals(org) ? "1111" : "2222";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject + "-" + suffix);
            statement.execute();
        }
    }

    /** Read the tenant-local id of the synthetic patient used by accept. */
    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                if (!result.next()) {
                    throw new IllegalStateException("missing synthetic patient");
                }
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
        }
    }

    /** The opaque identity id that the database trigger writes into the ledgers. */
    private static String identityId(String org, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity "
                            + "WHERE organization_id = CAST(? AS uuid) AND subject = ?")) {
                statement.setString(1, org);
                statement.setString(2, subject);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("missing synthetic identity " + subject);
                    }
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic identity", exception);
        }
    }

    /** Count audit rows for one command outcome; the audit log is append-only. */
    private static long auditCount(String org, String action, String id, String outcome) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.audit_event "
                            + "WHERE action = ? AND resource_id = ? AND outcome = ?")) {
                statement.setString(1, action);
                statement.setString(2, id);
                statement.setString(3, outcome);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "audit count query returned no row");
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot count synthetic audit event", exception);
        }
    }

    /** Send a command through the real HTTP entry point under a synthetic identity. */
    private ResponseEntity<String> post(String path, String body, String subject, String org, String ifMatch) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", java.util.UUID.randomUUID().toString())
                .header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Read the order projection through the same HTTP entry point. */
    private ResponseEntity<String> get(String path, String subject, String org) {
        return rest.get().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Parse a command response body as JSON. */
    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    /** Read the durable order projection and its append-only ledgers. */
    private JsonNode view(String id, String subject, String org) {
        return json(get("/api/v1/xch03/orders/" + id, subject, org));
    }

    /** Build the accept command body with a verified association and retention policy. */
    private static String acceptBody(String patient, String assignee, String purpose, String retention) {
        return "{\"localPatientId\":\"" + patient + "\",\"assignee\":\"" + assignee + "\","
                + "\"permittedPurpose\":\"" + purpose + "\",\"retentionPolicy\":\"" + retention + "\"}";
    }

    /**
     * Positive proof: an authenticated recipient with EXCHANGE_ACCEPT accepts a
     * released order with a valid version and the state change, event, retained
     * copy and audit record all commit together.
     */
    @Test
    void acceptWithVerifiedAssociationCommitsStateEventAndAudit() throws Exception {
        String id = orderId(ORG_A, IDX_ACCEPT);
        String patient = patientId(ORG_A);

        ResponseEntity<String> accepted = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "LEGAL_HOLD"),
                VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        JsonNode result = json(accepted);
        assertEquals(id, result.get("id").asText(), "the response carries the accepted order id");
        assertEquals("accepted", result.get("state").asText());
        assertEquals(2, result.get("version").asInt());
        assertEquals(patient, result.get("localPatientId").asText());
        assertEquals("clinician-1111", result.get("assignee").asText());
        assertEquals("RESULT_REVIEW", result.get("permittedPurpose").asText());
        assertEquals("LEGAL_HOLD", result.get("retentionPolicy").asText());

        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("accepted", detail.at("/order/state").asText());
        assertEquals(2, detail.at("/history").size(), "seeded receive event plus one accept event");
        JsonNode event = detail.at("/history/1");
        assertEquals("accept", event.get("action").asText());
        assertEquals("requested", event.get("fromState").asText());
        assertEquals("accepted", event.get("toState").asText());
        assertEquals(identityId(ORG_A, VERIFIER_A), event.get("actorId").asText());
        assertEquals(1, detail.at("/retained").size());
        assertEquals("RESULT_REVIEW", detail.at("/retained/0/permittedPurpose").asText());
        assertEquals("LEGAL_HOLD", detail.at("/retained/0/retentionPolicy").asText());
        assertTrue(detail.at("/retained/0/legalHold").asBoolean());
        assertEquals(identityId(ORG_A, VERIFIER_A), detail.at("/retained/0/actorId").asText());
        assertEquals(1, auditCount(ORG_A, ACCEPT_ACTION, id, "SUCCESS"));
    }

    /**
     * Negative proof of role policy: an active member without EXCHANGE_ACCEPT
     * cannot accept, and the denial appends no event or retained copy.
     */
    @Test
    void nonRecipientRoleCannotAccept() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);
        String patient = patientId(ORG_A);

        // The auditor has an active membership and may read the order, but the
        // privacy_auditor role is absent from the recipient set: the 403 must
        // come from the action policy, not from an unknown identity. Prove the
        // read first so an identity-resolution failure cannot explain the 403.
        assertEquals("requested", view(id, AUDITOR_A, ORG_A).at("/order/state").asText());
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                AUDITOR_A, ORG_A, "1");

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("requested", detail.at("/order/state").asText());
        assertEquals(1, detail.at("/history").size(), "a denied command appends no event");
        assertEquals(0, detail.at("/retained").size());
        assertEquals(1, auditCount(ORG_A, ACCEPT_ACTION, id, "DENIED"));
    }

    /**
     * Negative proof of tenant isolation: a peer tenant's verifier cannot accept
     * the owner's order; forced RLS conceals it as 404 and nothing is committed.
     */
    @Test
    void peerTenantCannotAcceptAnotherTenantsOrder() throws Exception {
        String id = orderId(ORG_A, IDX_PEER);
        String patient = patientId(ORG_A);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-2222", "RESULT_REVIEW", "STANDARD"),
                VERIFIER_B, ORG_B, "1");

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("requested", detail.at("/order/state").asText());
        assertEquals(1, detail.at("/history").size(), "the concealed order was not modified");
        assertEquals(0, detail.at("/retained").size());
        assertEquals(0, auditCount(ORG_A, ACCEPT_ACTION, id, "SUCCESS"));
    }

    /**
     * Negative proof of the version precondition: a verify-first accept with a
     * stale If-Match is refused 412 after a genuine command advanced the version.
     */
    @Test
    void staleIfMatchIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        ResponseEntity<String> first = post("/api/v1/xch03/orders/" + id + "/clarify",
                "{\"question\":\"Specimen?\"}", VERIFIER_A, ORG_A, "1");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());

        // The genuine clarify advanced the row to version 2, so reusing version
        // 1 is an optimistic-lock race rather than a synthetic bad header.
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A), "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.PRECONDITION_FAILED, refused.getStatusCode(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("clarification_requested", detail.at("/order/state").asText());
        assertEquals(2, detail.at("/history").size(), "the stale command appends no event after setup");
        assertEquals("clarify", detail.at("/history/1").get("action").asText());
        assertEquals(0, detail.at("/retained").size());
    }

    /**
     * Negative proof of the state machine: an accept against a rejected order is
     * refused 409 and the terminal state and ledgers stay unchanged.
     */
    @Test
    void acceptAfterRejectionIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_TERMINAL);
        String patient = patientId(ORG_A);
        ResponseEntity<String> rejected = post("/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"duplicate order\"}", VERIFIER_A, ORG_A, "1");
        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertEquals("rejected", json(rejected).get("state").asText());

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "STANDARD"),
                VERIFIER_A, ORG_A, "2");

        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("rejected", detail.at("/order/state").asText());
        assertEquals(2, detail.at("/history").size(), "the refused accept appends no event after reject");
        assertEquals("reject", detail.at("/history/1").get("action").asText());
        assertEquals(0, detail.at("/retained").size(), "a rejected order retains no recipient copy");
    }
}
