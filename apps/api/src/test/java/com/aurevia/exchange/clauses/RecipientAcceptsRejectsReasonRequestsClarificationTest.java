package com.aurevia.exchange.clauses;

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
 * XCH-03 clause 1 acceptance on real PostgreSQL: a recipient accepts, rejects
 * with a recorded reason or asks for clarification, and each decision is
 * committed with its state change, append-only event and audit record. A
 * non-recipient role, a peer tenant, a stale version, a blank reason or
 * question, and a decision against a terminal order are all refused without
 * changing durable state.
 *
 * <p>Every assertion goes through the real HTTP controller and reads the
 * persisted projection back through the same entry point. The actor and tenant
 * come only from the synthetic request context; no request-body field can
 * supply them. Two tenants share the same local order codes so forced RLS is
 * the only separator, and each test owns a distinct order because the ledgers
 * are append-only. All fixtures are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/"
                + RecipientAcceptsRejectsReasonRequestsClarificationTest.DB)
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RecipientAcceptsRejectsReasonRequestsClarificationTest {

    static final String DB = "orevia_xch03_recipient_response_it";

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CHECKSUM = "a".repeat(64);
    private static final String VERIFIER_A = "lab-verifier-1111";
    private static final String VERIFIER_B = "lab-verifier-2222";
    private static final String AUDITOR_A = "auditor-1111";
    private static final String RECEPTION_A = "reception-1111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // One order per test: the response history and retained-copy ledgers are
    // append-only, so sharing an order would leak advanced versions between cases.
    private static final int ORDER_COUNT = 9;
    private static final int IDX_ACCEPT = 1;
    private static final int IDX_REJECT = 2;
    private static final int IDX_CLARIFY = 3;
    private static final int IDX_REJECT_NO_REASON = 4;
    private static final int IDX_CLARIFY_NO_QUESTION = 5;
    private static final int IDX_WRONG_ROLE = 6;
    private static final int IDX_PEER = 7;
    private static final int IDX_STALE = 8;
    private static final int IDX_TERMINAL = 9;

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER orevia_migrate");
        }
        Flyway.configure().dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
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

    /** Seed one tenant, its members, one synthetic patient and one order per test. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        // V4's role-assignment trigger resolves granted_by from app.actor in the
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    private JsonNode view(String id, String subject, String org) {
        return json(get("/api/v1/xch03/orders/" + id, subject, org));
    }

    private static String acceptBody(String patient, String assignee, String purpose, String retention) {
        return "{\"localPatientId\":\"" + patient + "\",\"assignee\":\"" + assignee + "\","
                + "\"permittedPurpose\":\"" + purpose + "\",\"retentionPolicy\":\"" + retention + "\"}";
    }

    @Test
    void recipientAcceptsAndRetainsVerifiedAssociation() throws Exception {
        String id = orderId(ORG_A, IDX_ACCEPT);
        String patient = patientId(ORG_A);

        ResponseEntity<String> accepted = post("/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patient, "clinician-1111", "RESULT_REVIEW", "LEGAL_HOLD"),
                VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        JsonNode result = json(accepted);
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
    }

    @Test
    void recipientRejectsWithRecordedReason() throws Exception {
        String id = orderId(ORG_A, IDX_REJECT);
        String reason = "duplicate order";

        ResponseEntity<String> rejected = post("/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"" + reason + "\"}", VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        JsonNode result = json(rejected);
        assertEquals("rejected", result.get("state").asText());
        assertEquals(2, result.get("version").asInt());
        assertEquals(reason, result.get("reason").asText());

        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("rejected", detail.at("/order/state").asText());
        assertEquals(reason, detail.at("/order/reason").asText());
        assertEquals(2, detail.at("/history").size(), "seeded receive event plus one reject event");
        JsonNode event = detail.at("/history/1");
        assertEquals("reject", event.get("action").asText());
        assertEquals("requested", event.get("fromState").asText());
        assertEquals("rejected", event.get("toState").asText());
        assertEquals(reason, event.get("reason").asText());
        assertEquals(0, detail.at("/retained").size(), "only acceptance retains a recipient copy");
    }

    @Test
    void recipientRequestsClarificationWithRecordedQuestion() throws Exception {
        String id = orderId(ORG_A, IDX_CLARIFY);
        String question = "Which specimen was collected?";

        ResponseEntity<String> clarified = post("/api/v1/xch03/orders/" + id + "/clarify",
                "{\"question\":\"" + question + "\"}", VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, clarified.getStatusCode(), clarified.getBody());
        JsonNode result = json(clarified);
        assertEquals("clarification_requested", result.get("state").asText());
        assertEquals(2, result.get("version").asInt());
        assertEquals(question, result.get("question").asText());

        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("clarification_requested", detail.at("/order/state").asText());
        assertEquals(question, detail.at("/order/question").asText());
        assertEquals(2, detail.at("/history").size(), "seeded receive event plus one clarify event");
        JsonNode event = detail.at("/history/1");
        assertEquals("clarify", event.get("action").asText());
        assertEquals("requested", event.get("fromState").asText());
        assertEquals("clarification_requested", event.get("toState").asText());
        assertEquals(question, event.get("reason").asText(),
                "the service records the clarification question in the event reason");
        assertEquals(0, detail.at("/retained").size());
    }

    @Test
    void rejectWithoutReasonIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_REJECT_NO_REASON);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/reject",
                "{}", VERIFIER_A, ORG_A, "1");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REQUIRED", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("requested", detail.at("/order/state").asText());
        assertEquals(1, detail.at("/history").size(), "a refused command appends no event");
        assertEquals(0, detail.at("/retained").size());
    }

    @Test
    void clarifyWithoutQuestionIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_CLARIFY_NO_QUESTION);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/clarify",
                "{}", VERIFIER_A, ORG_A, "1");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REQUIRED", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("requested", detail.at("/order/state").asText());
        assertEquals(1, detail.at("/history").size(), "a refused command appends no event");
    }

    @Test
    void nonRecipientRoleCannotRespond() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);

        // This subject has an active membership and may read the order, but its
        // privacy_auditor role is absent from RECIPIENT_ROLES: the 403 must come
        // from the action policy, not from an unknown identity.
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"role probe\"}", AUDITOR_A, ORG_A, "1");

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("requested", detail.at("/order/state").asText());
        assertEquals(1, detail.at("/history").size(), "a denied command appends no event");
    }

    @Test
    void peerTenantCannotRespondToAnotherTenantsOrder() throws Exception {
        String id = orderId(ORG_A, IDX_PEER);

        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"peer probe\"}", VERIFIER_B, ORG_B, "1");

        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("requested", detail.at("/order/state").asText());
        assertEquals(1, detail.at("/history").size(), "the concealed order was not modified");
    }

    @Test
    void staleIfMatchIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        ResponseEntity<String> first = post("/api/v1/xch03/orders/" + id + "/clarify",
                "{\"question\":\"Specimen?\"}", VERIFIER_A, ORG_A, "1");
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());

        // The genuine clarify advanced the row to version 2, so reusing version
        // 1 is an optimistic-lock race rather than a synthetic bad header.
        ResponseEntity<String> refused = post("/api/v1/xch03/orders/" + id + "/reject",
                "{\"reason\":\"stale reason\"}", VERIFIER_A, ORG_A, "1");

        assertEquals(HttpStatus.PRECONDITION_FAILED, refused.getStatusCode(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        JsonNode detail = view(id, VERIFIER_A, ORG_A);
        assertEquals("clarification_requested", detail.at("/order/state").asText());
        assertEquals(2, detail.at("/history").size(), "the stale command appends no event after setup");
        assertEquals("clarify", detail.at("/history/1").get("action").asText());
    }

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
