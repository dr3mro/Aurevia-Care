package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * XCH-02 clause acceptance through the real HTTP entry point on PostgreSQL.
 *
 * <p>The three internal channels are independent: a workflow decision writes
 * only the workflow channel, a delivery receipt writes only the transmission
 * channel, and only an authorized clinician writes the clinical review channel
 * with a bound actor, timestamp and reviewed revision. The chains run requested
 * → accepted → processing → completed (with rejected and cancelled branches),
 * transmission pending → sent → delivered or failed, and clinical review
 * unreviewed → acknowledged → action pending → resolved. Negative cases prove
 * the rule refuses a skipped state, a stale precondition, a wrong role, a
 * missing reason and a cross-tenant transition. The delivery and acknowledgment
 * cases assert channel independence, which is this clause's rule; the deeper
 * human-binding and delivery-versus-clinical rules are owned by separate clause
 * stories and are cited there as corroborating evidence. Fixtures are synthetic;
 * the peer tenant shares the same local code space so concealment is observable.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_xch02_workflow_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class UseSeparateInternalStatesWorkflowRequestedAcceptedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "orevia_xch02_workflow_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String CLINICIAN = "clinician-a";
    private static final String LAB = "lab-a";
    private static final String TENANT_ADMIN = "admin-a";
    private static final String PEER = "clinician-b";
    private static final Instant FIXED = Instant.parse("2026-11-19T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Deterministic clock so the human-review timestamp is an exact, stable value. */
    @TestConfiguration
    static class FixedClock {
        /**
         * Supply the frozen application clock.
         *
         * @return the fixed clock
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

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
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_A, LAB, "lab_collector");
            seedMember(statement, ORG_A, TENANT_ADMIN, "tenant_admin");
            seedMember(statement, ORG_B, PEER, "clinician");
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

    private static void seedTenant(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = ''");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = ''");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "WHERE m.user_id = (SELECT id FROM public.user_identity "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "') "
                + "AND u.subject = '" + subject + "'");
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                        String ifMatch) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .header("If-Match", ifMatch == null ? "1" : ifMatch)
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

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private String create(String orderRef) throws Exception {
        ResponseEntity<String> created = post("/api/v1/exchange-tasks",
                "{\"orderRef\":\"" + orderRef + "\"}", CLINICIAN, ORG_A, null);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        return json(created).get("id").asText();
    }

    private JsonNode task(String id) throws Exception {
        ResponseEntity<String> response = get("/api/v1/exchange-tasks/" + id, CLINICIAN, ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response).get("task");
    }

    private JsonNode history(String id) throws Exception {
        ResponseEntity<String> response = get("/api/v1/exchange-tasks/" + id, CLINICIAN, ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response).get("history");
    }

    private static void assertChannels(JsonNode state, String workflow, String transmission,
                                       String review) {
        assertEquals(workflow, state.get("workflowState").asText());
        assertEquals(transmission, state.get("transmissionState").asText());
        assertEquals(review, state.get("clinicalReviewState").asText());
    }

    @Test
    void workflowChainRunsRequestedToAcceptedToProcessingToCompleted() throws Exception {
        String id = create("WF-CHAIN");
        assertChannels(task(id), "requested", "pending", "unreviewed");

        ResponseEntity<String> accepted = post("/api/v1/exchange-tasks/" + id + "/accept",
                "{}", LAB, ORG_A, "\"1\"");
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        assertChannels(json(accepted), "accepted", "pending", "unreviewed");
        assertEquals(2, json(accepted).get("version").asInt());

        ResponseEntity<String> processing = post("/api/v1/exchange-tasks/" + id + "/process",
                "{}", LAB, ORG_A, "\"2\"");
        assertEquals(200, processing.getStatusCode().value(), processing.getBody());
        assertChannels(json(processing), "processing", "pending", "unreviewed");
        assertEquals(3, json(processing).get("version").asInt());

        ResponseEntity<String> completed = post("/api/v1/exchange-tasks/" + id + "/complete",
                "{}", LAB, ORG_A, "\"3\"");
        assertEquals(200, completed.getStatusCode().value(), completed.getBody());
        assertChannels(json(completed), "completed", "pending", "unreviewed");
        assertEquals(4, json(completed).get("version").asInt());

        JsonNode view = history(id);
        assertEquals(4, view.size());
        assertEquals("workflow", view.get(0).get("channel").asText());
        assertEquals("EXCHANGE_CREATE", view.get(0).get("action").asText());
        assertEquals("requested", view.get(0).get("toState").asText());
        assertEquals("accepted", view.get(1).get("toState").asText());
        assertEquals("processing", view.get(2).get("toState").asText());
        assertEquals("completed", view.get(3).get("toState").asText());
        assertChannels(task(id), "completed", "pending", "unreviewed");
    }

    @Test
    void rejectAndCancelBranchesRecordReasonAndEndTheWorkflow() throws Exception {
        String rejectedId = create("WF-REJECT");
        ResponseEntity<String> rejected = post("/api/v1/exchange-tasks/" + rejectedId + "/reject",
                "{\"reason\":\"duplicate order\"}", LAB, ORG_A, "\"1\"");
        assertEquals(200, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("rejected", json(rejected).get("workflowState").asText());
        assertEquals("duplicate order", task(rejectedId).get("reason").asText());
        assertEquals("duplicate order", history(rejectedId).get(1).get("reason").asText());
        ResponseEntity<String> afterReject = post("/api/v1/exchange-tasks/" + rejectedId + "/accept",
                "{}", LAB, ORG_A, "\"2\"");
        assertEquals(409, afterReject.getStatusCode().value(), afterReject.getBody());
        assertEquals("INVALID_TRANSITION", json(afterReject).get("code").asText());
        assertEquals("rejected", task(rejectedId).get("workflowState").asText());

        String cancelledId = create("WF-CANCEL");
        ResponseEntity<String> cancelled = post("/api/v1/exchange-tasks/" + cancelledId + "/cancel",
                "{\"reason\":\"sender withdrawal\"}", LAB, ORG_A, "\"1\"");
        assertEquals(200, cancelled.getStatusCode().value(), cancelled.getBody());
        assertEquals("cancelled", json(cancelled).get("workflowState").asText());
        assertEquals("sender withdrawal", task(cancelledId).get("reason").asText());
        assertEquals("sender withdrawal", history(cancelledId).get(1).get("reason").asText());
        ResponseEntity<String> afterCancel = post("/api/v1/exchange-tasks/" + cancelledId + "/accept",
                "{}", LAB, ORG_A, "\"2\"");
        assertEquals(409, afterCancel.getStatusCode().value(), afterCancel.getBody());
        assertEquals("INVALID_TRANSITION", json(afterCancel).get("code").asText());
        assertEquals("cancelled", task(cancelledId).get("workflowState").asText());
    }

    @Test
    void aDeliveryReceiptCannotCloseTheClinicalTask() throws Exception {
        String id = create("WF-DELIVERY");
        ResponseEntity<String> sent = post("/api/v1/exchange-tasks/" + id + "/send",
                "{}", LAB, ORG_A, "\"1\"");
        assertEquals(200, sent.getStatusCode().value(), sent.getBody());
        assertChannels(json(sent), "requested", "sent", "unreviewed");

        ResponseEntity<String> delivered = post("/api/v1/exchange-tasks/" + id + "/deliver",
                "{}", LAB, ORG_A, "\"2\"");
        assertEquals(200, delivered.getStatusCode().value(), delivered.getBody());
        assertChannels(json(delivered), "requested", "delivered", "unreviewed");

        JsonNode deliveryView = history(id);
        for (JsonNode event : deliveryView) {
            assertFalse("clinical_review".equals(event.get("channel").asText()),
                    "a delivery receipt must not write the clinical channel");
        }
        assertTrue(task(id).get("reviewedBy").isNull());
        assertTrue(task(id).get("reviewedAt").isNull());
        assertTrue(task(id).get("reviewedRevision").isNull());

        ResponseEntity<String> acknowledged = post("/api/v1/exchange-tasks/" + id + "/acknowledge",
                "{}", CLINICIAN, ORG_A, "\"3\"");
        assertEquals(200, acknowledged.getStatusCode().value(), acknowledged.getBody());
        JsonNode reviewed = json(acknowledged);
        assertEquals("acknowledged", reviewed.get("clinicalReviewState").asText());
        assertEquals("delivered", reviewed.get("transmissionState").asText());
        assertEquals("requested", reviewed.get("workflowState").asText());
        assertEquals(actorId(ORG_A, CLINICIAN), reviewed.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), reviewed.get("reviewedAt").asText());
        assertEquals(3, reviewed.get("reviewedRevision").asInt());

        JsonNode finalView = history(id);
        assertEquals(4, finalView.size());
        assertEquals("clinical_review", finalView.get(3).get("channel").asText());
        assertEquals(3, finalView.get(3).get("reviewedRevision").asInt());
        assertChannels(task(id), "requested", "delivered", "acknowledged");
    }

    @Test
    void aRequestedTaskCannotSkipToCompleted() throws Exception {
        String id = create("WF-SKIP");
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + id + "/complete",
                "{}", LAB, ORG_A, "\"1\"");
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        assertChannels(task(id), "requested", "pending", "unreviewed");
    }

    @Test
    void aStaleVersionCannotAdvanceTheWorkflow() throws Exception {
        String id = create("WF-STALE");
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + id + "/accept",
                "{}", LAB, ORG_A, "\"99\"");
        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        assertChannels(task(id), "requested", "pending", "unreviewed");
    }

    @Test
    void aTenantAdminCannotAdvanceTheWorkflow() throws Exception {
        String id = create("WF-ADMIN");
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + id + "/accept",
                "{}", TENANT_ADMIN, ORG_A, "\"1\"");
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        assertChannels(task(id), "requested", "pending", "unreviewed");
    }

    @Test
    void aLabCollectorCannotAcknowledgeClinicalReview() throws Exception {
        String id = create("WF-LAB-ACK");
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + id + "/acknowledge",
                "{}", LAB, ORG_A, "\"1\"");
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        assertChannels(task(id), "requested", "pending", "unreviewed");
    }

    @Test
    void aPeerTenantCannotTransitionAnotherTenantsTask() throws Exception {
        String id = create("WF-PEER");
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + id + "/accept",
                "{}", PEER, ORG_B, "\"1\"");
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        assertChannels(task(id), "requested", "pending", "unreviewed");
    }

    @Test
    void rejectingWithoutAReasonIsRejected() throws Exception {
        String id = create("WF-NO-REASON");
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + id + "/reject",
                "{}", LAB, ORG_A, "\"1\"");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REASON_REQUIRED", json(refused).get("code").asText());
        assertChannels(task(id), "requested", "pending", "unreviewed");
        assertEquals(1, history(id).size());
    }

    @Test
    void aFailedTransmissionLeavesTheClinicalChannelUntouched() throws Exception {
        String id = create("WF-TRANSMIT-FAIL");
        ResponseEntity<String> failed = post("/api/v1/exchange-tasks/" + id + "/fail",
                "{}", LAB, ORG_A, "\"1\"");
        assertEquals(200, failed.getStatusCode().value(), failed.getBody());
        assertChannels(json(failed), "requested", "failed", "unreviewed");

        JsonNode view = history(id);
        assertEquals(2, view.size());
        assertEquals("transmission", view.get(1).get("channel").asText());
        assertEquals("pending", view.get(1).get("fromState").asText());
        assertEquals("failed", view.get(1).get("toState").asText());
        assertChannels(task(id), "requested", "failed", "unreviewed");

        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + id + "/deliver",
                "{}", LAB, ORG_A, "\"2\"");
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        assertChannels(task(id), "requested", "failed", "unreviewed");
    }

    @Test
    void clinicalReviewChainRunsAcknowledgedActionPendingToResolved() throws Exception {
        String id = create("WF-REVIEW-CHAIN");
        ResponseEntity<String> premature = post("/api/v1/exchange-tasks/" + id + "/resolve",
                "{}", CLINICIAN, ORG_A, "\"1\"");
        assertEquals(409, premature.getStatusCode().value(), premature.getBody());
        assertEquals("INVALID_TRANSITION", json(premature).get("code").asText());
        assertChannels(task(id), "requested", "pending", "unreviewed");

        ResponseEntity<String> acknowledged = post("/api/v1/exchange-tasks/" + id + "/acknowledge",
                "{}", CLINICIAN, ORG_A, "\"1\"");
        assertEquals(200, acknowledged.getStatusCode().value(), acknowledged.getBody());
        assertEquals("acknowledged", json(acknowledged).get("clinicalReviewState").asText());
        assertEquals(FIXED.toString(), json(acknowledged).get("reviewedAt").asText());
        assertEquals(1, json(acknowledged).get("reviewedRevision").asInt());

        ResponseEntity<String> actionPending = post("/api/v1/exchange-tasks/" + id + "/request-action",
                "{}", CLINICIAN, ORG_A, "\"2\"");
        assertEquals(200, actionPending.getStatusCode().value(), actionPending.getBody());
        assertChannels(json(actionPending), "requested", "pending", "action_pending");

        ResponseEntity<String> resolved = post("/api/v1/exchange-tasks/" + id + "/resolve",
                "{}", CLINICIAN, ORG_A, "\"3\"");
        assertEquals(200, resolved.getStatusCode().value(), resolved.getBody());
        assertChannels(json(resolved), "requested", "pending", "resolved");

        JsonNode view = history(id);
        assertEquals(4, view.size());
        assertEquals("workflow", view.get(0).get("channel").asText());
        assertEquals("clinical_review", view.get(1).get("channel").asText());
        assertEquals("acknowledged", view.get(1).get("toState").asText());
        assertEquals("clinical_review", view.get(2).get("channel").asText());
        assertEquals("action_pending", view.get(2).get("toState").asText());
        assertEquals("clinical_review", view.get(3).get("channel").asText());
        assertEquals("resolved", view.get(3).get("toState").asText());
        assertChannels(task(id), "requested", "pending", "resolved");

        ResponseEntity<String> terminal = post("/api/v1/exchange-tasks/" + id + "/acknowledge",
                "{}", CLINICIAN, ORG_A, "\"4\"");
        assertEquals(409, terminal.getStatusCode().value(), terminal.getBody());
        assertEquals("INVALID_TRANSITION", json(terminal).get("code").asText());
        assertChannels(task(id), "requested", "pending", "resolved");
    }

    private static String actorId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = '" + subject + "'");
            try (java.sql.ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }
}
