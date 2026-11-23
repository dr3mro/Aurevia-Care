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
 * XCH-03 clause 1 acceptance on real PostgreSQL: an authorized recipient can
 * accept a released order, reject it with a durable reason or request
 * clarification with a question, while a read-only actor, a peer tenant, a
 * stale or missing version, a forbidden state and a missing reason are all
 * refused. Every command goes through the real HTTP entry point and its result
 * is checked in the forced-RLS order projection and append-only response ledger.
 *
 * <p>Two synthetic tenants share identical local order codes so tenant
 * isolation is exercised, and each test owns a distinct order because the
 * ledgers are append-only. No production code, contract or migration is
 * changed by this test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_recipient_response_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RecipientAcceptsRejectsReasonRequestsClarificationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_recipient_response_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String CHECKSUM = "a".repeat(64);
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECIPIENT_A = "lab-verifier-1111";
    private static final String RECIPIENT_B = "lab-verifier-2222";
    private static final String RECEPTION_A = "reception-1111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int ORDER_COUNT = 9;
    private static final int IDX_ACCEPT = 1;
    private static final int IDX_REJECT = 2;
    private static final int IDX_CLARIFY = 3;
    private static final int IDX_WRONG_ROLE = 4;
    private static final int IDX_PEER = 5;
    private static final int IDX_STALE = 6;
    private static final int IDX_NO_IF_MATCH = 7;
    private static final int IDX_REJECT_AFTER_CLARIFY = 8;
    private static final int IDX_NO_REASON = 9;

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

    /** One tenant with the peer's order codes, its members and a synthetic patient. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        String shortName = org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-" + shortName + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + shortName + "')");
        // The grantor identity must exist before the first role assignment: V4's
        // trigger resolves granted_by from app.actor in the active tenant.
        for (String member : new String[] {"admin", "lab-verifier", "reception", "clinician"}) {
            String subject = member + "-" + shortName;
            String role = switch (member) {
                case "lab-verifier" -> "lab_verifier";
                case "reception" -> "reception";
                case "clinician" -> "clinician";
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
        // The ledgers are append-only, so a dedicated order per test prevents
        // advanced versions and states from leaking between tests.
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

    /** Both tenants use the same local order codes: RLS is the only separator. */
    private static String orderId(String org, int index) {
        String suffix = String.format("%012d", index);
        return (ORG_A.equals(org) ? "aaaaaaaa-aaaa-aaaa-aaaa-" : "bbbbbbbb-bbbb-bbbb-bbbb-") + suffix;
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "admin");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
        }
    }

    /** The opaque identity id the V21 trigger writes into the response ledger. */
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
                    assertTrue(result.next(), "missing synthetic identity " + subject);
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic identity", exception);
        }
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

    private HttpHeaders headers(String subject, String organization, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, ifMatch)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
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

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer);
        return value.isNull() ? null : value.asText();
    }

    /** Read the forced-RLS projection and its append-only ledgers through GET /{id}. */
    private JsonNode view(String org, String subject, String id) {
        return json(send(HttpMethod.GET, "/api/v1/xch03/orders/" + id, null, subject, org, null));
    }

    /** Assert the command left the requested order and its ledger untouched. */
    private void assertStillRequested(String org, String subject, String id) {
        JsonNode current = view(org, subject, id);
        assertEquals("requested", current.at("/order/state").asText());
        assertEquals(1, current.at("/order/version").asInt());
        assertEquals(1, current.at("/history").size());
        assertTrue(current.at("/order/reason").isNull());
        assertTrue(current.at("/order/question").isNull());
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String acceptBody(String patientId) {
        return "{\"localPatientId\":\"" + patientId + "\",\"assignee\":\"clinician-1111\","
                + "\"permittedPurpose\":\"RESULT_REVIEW\",\"retentionPolicy\":\"LEGAL_HOLD\"}";
    }

    @Test
    void acceptsReleasedOrderAndRetainsPurpose() throws Exception {
        String id = orderId(ORG_A, IDX_ACCEPT);
        ResponseEntity<String> accepted = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/accept",
                acceptBody(patientId(ORG_A)), RECIPIENT_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, accepted.getStatusCode(), accepted.getBody());
        assertEquals("accepted", text(accepted, "/state"));
        assertEquals("2", text(accepted, "/version"));

        JsonNode current = view(ORG_A, RECIPIENT_A, id);
        assertEquals("accepted", current.at("/order/state").asText());
        assertEquals(1, current.at("/retained").size());
        assertEquals("RESULT_REVIEW", current.at("/retained/0/permittedPurpose").asText());
        assertEquals("LEGAL_HOLD", current.at("/retained/0/retentionPolicy").asText());
        assertTrue(current.at("/retained/0/legalHold").asBoolean());
        assertEquals(2, current.at("/history").size());
        JsonNode event = current.at("/history/1");
        assertEquals("accept", event.get("action").asText());
        assertEquals("requested", event.get("fromState").asText());
        assertEquals("accepted", event.get("toState").asText());
        assertEquals(identityId(ORG_A, RECIPIENT_A), event.get("actorId").asText());
    }

    @Test
    void rejectsWithDurableReason() throws Exception {
        String id = orderId(ORG_A, IDX_REJECT);
        String reason = "duplicate order";
        ResponseEntity<String> rejected = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject",
                reasonBody(reason), RECIPIENT_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, rejected.getStatusCode(), rejected.getBody());
        assertEquals("rejected", text(rejected, "/state"));
        assertEquals("2", text(rejected, "/version"));
        assertEquals(reason, text(rejected, "/reason"));
        assertEquals(reason, view(ORG_A, RECIPIENT_A, id).at("/order/reason").asText());

        JsonNode history = json(send(HttpMethod.GET,
                "/api/v1/xch03/orders/" + id + "/history", null, RECIPIENT_A, ORG_A, null));
        assertEquals(2, history.size());
        assertEquals("reject", history.get(1).get("action").asText());
        assertEquals("requested", history.get(1).get("fromState").asText());
        assertEquals("rejected", history.get(1).get("toState").asText());
        assertEquals(reason, history.get(1).get("reason").asText());
    }

    @Test
    void requestsClarificationWithQuestion() throws Exception {
        String id = orderId(ORG_A, IDX_CLARIFY);
        String question = "Which specimen was collected?";
        ResponseEntity<String> clarified = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/clarify",
                "{\"question\":\"" + question + "\"}", RECIPIENT_A, ORG_A, "1");

        assertEquals(HttpStatus.OK, clarified.getStatusCode(), clarified.getBody());
        assertEquals("clarification_requested", text(clarified, "/state"));
        assertEquals("2", text(clarified, "/version"));
        assertEquals(question, text(clarified, "/question"));

        JsonNode current = view(ORG_A, RECIPIENT_A, id);
        assertEquals(question, current.at("/order/question").asText());
        assertEquals(2, current.at("/history").size());
        assertEquals("clarify", current.at("/history/1").get("action").asText());
        assertEquals("requested", current.at("/history/1").get("fromState").asText());
        assertEquals("clarification_requested", current.at("/history/1").get("toState").asText());
        assertEquals(question, current.at("/history/1").get("reason").asText());
    }

    @Test
    void wrongRoleCannotReject() throws Exception {
        String id = orderId(ORG_A, IDX_WRONG_ROLE);
        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject",
                reasonBody("unauthorized decision"), RECEPTION_A, ORG_A, "1");

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertStillRequested(ORG_A, RECIPIENT_A, id);
    }

    @Test
    void peerTenantCannotReject() throws Exception {
        String foreign = orderId(ORG_A, IDX_PEER);
        String own = orderId(ORG_B, IDX_PEER);
        assertEquals(HttpStatus.OK, send(HttpMethod.GET, "/api/v1/xch03/orders/" + own,
                null, RECIPIENT_B, ORG_B, null).getStatusCode(),
                "the peer recipient can read its own order with the same local code");
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET, "/api/v1/xch03/orders/" + foreign,
                null, RECIPIENT_B, ORG_B, null).getStatusCode(),
                "the peer recipient cannot see the other tenant's order");

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + foreign + "/reject",
                reasonBody("cross-tenant attempt"), RECIPIENT_B, ORG_B, "1");
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertStillRequested(ORG_A, RECIPIENT_A, foreign);
        assertEquals("requested", view(ORG_B, RECIPIENT_B, own).at("/order/state").asText());
    }

    @Test
    void staleVersionIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_STALE);
        ResponseEntity<String> clarified = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/clarify",
                "{\"question\":\"Which unit?\"}", RECIPIENT_A, ORG_A, "1");
        assertEquals(HttpStatus.OK, clarified.getStatusCode(), clarified.getBody());

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject",
                reasonBody("stale decision"), RECIPIENT_A, ORG_A, "1");
        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", text(refused, "/code"));

        JsonNode current = view(ORG_A, RECIPIENT_A, id);
        assertEquals("clarification_requested", current.at("/order/state").asText());
        assertEquals(2, current.at("/order/version").asInt());
        assertEquals(2, current.at("/history").size());
        assertEquals("Which unit?", current.at("/order/question").asText());
    }

    @Test
    void missingIfMatchIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_NO_IF_MATCH);
        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject",
                reasonBody("no precondition"), RECIPIENT_A, ORG_A, null);

        assertEquals(428, refused.getStatusCode().value(), refused.getBody());
        assertEquals("PRECONDITION_REQUIRED", text(refused, "/code"));
        assertStillRequested(ORG_A, RECIPIENT_A, id);
    }

    @Test
    void rejectAfterClarifyIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_REJECT_AFTER_CLARIFY);
        String question = "Which specimen was collected?";
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/clarify",
                "{\"question\":\"" + question + "\"}", RECIPIENT_A, ORG_A, "1").getStatusCode());

        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject",
                reasonBody("too late"), RECIPIENT_A, ORG_A, "2");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", text(refused, "/code"));

        JsonNode current = view(ORG_A, RECIPIENT_A, id);
        assertEquals("clarification_requested", current.at("/order/state").asText());
        assertEquals(2, current.at("/order/version").asInt());
        assertEquals(2, current.at("/history").size());
        assertEquals(question, current.at("/order/reason").asText(),
                "clarify records its question as the durable reason on the order");
        assertEquals(question, current.at("/order/question").asText());
    }

    @Test
    void missingReasonIsRefused() throws Exception {
        String id = orderId(ORG_A, IDX_NO_REASON);
        ResponseEntity<String> refused = send(HttpMethod.POST,
                "/api/v1/xch03/orders/" + id + "/reject",
                "{}", RECIPIENT_A, ORG_A, "1");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REQUIRED", text(refused, "/code"));
        assertStillRequested(ORG_A, RECIPIENT_A, id);
    }
}
