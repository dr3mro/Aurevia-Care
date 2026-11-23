package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * XCH-02 clause 1 acceptance through the real REST entry point on PostgreSQL:
 * one exchange task keeps three independent internal state channels, each with
 * its own legal chain, and a delivery receipt can never close a clinical task.
 *
 * <p>Every mutating test owns a seeded task with a unique identifier and tenant
 * local order reference, so JUnit method order cannot couple two tests. The peer
 * tenant deliberately reuses one local order reference to make RLS concealment
 * observable. All fixtures are synthetic and the frozen clock makes review
 * evidence deterministic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch02_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class UseSeparateInternalStatesWorkflowRequestedAcceptedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String WORKFLOW = "00000001-0000-0000-0000-000000000001";
    private static final String REJECT = "00000001-0000-0000-0000-000000000002";
    private static final String CANCEL = "00000001-0000-0000-0000-000000000003";
    private static final String TRANSMIT = "00000001-0000-0000-0000-000000000004";
    private static final String FAIL = "00000001-0000-0000-0000-000000000005";
    private static final String REVIEW = "00000001-0000-0000-0000-000000000006";
    private static final String GUARD = "00000001-0000-0000-0000-000000000007";
    private static final String ROLE = "00000001-0000-0000-0000-000000000008";
    private static final String STALE = "00000001-0000-0000-0000-000000000009";
    private static final String CHANNELS = "00000001-0000-0000-0000-00000000000a";
    private static final String PEER = "00000001-0000-0000-0000-00000000000b";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch02_clause_it";
    private static final String DB = "smartclinic_xch02_clause_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final Instant FIXED = Instant.parse("2026-11-19T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Deterministic clock for the clinical review evidence. */
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
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "lab-a", "lab_collector");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "peer-b", "clinician");
            seedTask(statement, ORG_A, WORKFLOW, "LOCAL-CLAUSE-1");
            seedTask(statement, ORG_A, REJECT, "LOCAL-CLAUSE-2");
            seedTask(statement, ORG_A, CANCEL, "LOCAL-CLAUSE-3");
            seedTask(statement, ORG_A, TRANSMIT, "LOCAL-CLAUSE-4");
            seedTask(statement, ORG_A, FAIL, "LOCAL-CLAUSE-5");
            seedTask(statement, ORG_A, REVIEW, "LOCAL-CLAUSE-6");
            seedTask(statement, ORG_A, GUARD, "LOCAL-CLAUSE-7");
            seedTask(statement, ORG_A, ROLE, "LOCAL-CLAUSE-8");
            seedTask(statement, ORG_A, STALE, "LOCAL-CLAUSE-9");
            seedTask(statement, ORG_A, CHANNELS, "LOCAL-CLAUSE-10");
            // Same tenant-local order reference as CHANNELS, so an ORG_A caller
            // must not be able to discover the peer row through that value.
            seedTask(statement, ORG_B, PEER, "LOCAL-CLAUSE-10");
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

    private static void seedTask(Statement statement, String org, String id, String orderRef)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + (org.equals(ORG_A) ? "clinician-a" : "peer-b") + "'");
        statement.execute("INSERT INTO public.exchange_task (organization_id, id, order_ref) "
                + "VALUES ('" + org + "', '" + id + "', '" + orderRef + "')");
        statement.execute("INSERT INTO public.exchange_task_transition "
                + "(organization_id, task_id, to_version, channel, action, from_state, to_state) "
                + "VALUES ('" + org + "', '" + id + "', 1, 'workflow', 'EXCHANGE_CREATE', "
                + "NULL, 'requested')");
    }

    private ResponseEntity<String> call(HttpMethod method, String path, String subject, String org,
                                        String body, String ifMatch) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString());
        if (ifMatch != null) {
            spec.header("If-Match", ifMatch);
        }
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                        String ifMatch) {
        return call(HttpMethod.POST, path, subject, org, body, ifMatch);
    }

    private ResponseEntity<String> get(String id, String subject, String org) {
        return call(HttpMethod.GET, "/api/v1/exchange-tasks/" + id, subject, org, null, null);
    }

    private JsonNode read(String id, String subject, String org) throws Exception {
        ResponseEntity<String> response = get(id, subject, org);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private static String actorId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = '" + subject + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    /**
     * Assert the three channels of one response and whether human review evidence
     * is present. This is the channel-independence assertion shared by every
     * transition in this class: a command may write only its own channel.
     */
    private static void assertChannels(JsonNode state, String workflow, String transmission,
                                       String review, boolean hasEvidence) {
        assertEquals(workflow, state.get("workflowState").asText());
        assertEquals(transmission, state.get("transmissionState").asText());
        assertEquals(review, state.get("clinicalReviewState").asText());
        if (hasEvidence) {
            assertFalse(state.get("reviewedBy").isNull());
            assertFalse(state.get("reviewedAt").isNull());
            assertFalse(state.get("reviewedRevision").isNull());
        } else {
            assertTrue(state.get("reviewedBy").isNull());
            assertTrue(state.get("reviewedAt").isNull());
            assertTrue(state.get("reviewedRevision").isNull());
        }
    }

    @Test
    void createOpensThreeInitialChannelsAtTheRestEntryPoint() throws Exception {
        ResponseEntity<String> response = post("/api/v1/exchange-tasks",
                "{\"orderRef\":\"LOCAL-CLAUSE-CREATE\"}", "clinician-a", ORG_A, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        String id = json(response).get("id").asText();
        assertEquals("/api/v1/exchange-tasks/" + id, response.getHeaders().getLocation().toString());
        assertChannels(json(response), "requested", "pending", "unreviewed", false);
        assertEquals(1, json(response).get("version").asInt());

        JsonNode view = read(id, "clinician-a", ORG_A);
        assertChannels(view.get("task"), "requested", "pending", "unreviewed", false);
        assertEquals(1, view.get("task").get("version").asInt());
        assertEquals(1, view.get("history").size());
        assertEquals("workflow", view.get("history").get(0).get("channel").asText());
        assertEquals("EXCHANGE_CREATE", view.get("history").get(0).get("action").asText());
    }

    @Test
    void oneTaskKeepsWorkflowTransmissionAndReviewChannelsIndependent() throws Exception {
        String forged = UUID.randomUUID().toString();
        String path = "/api/v1/exchange-tasks/" + CHANNELS;

        assertEquals(200, post(path + "/accept", null, "lab-a", ORG_A, "\"1\"")
                .getStatusCode().value());
        assertChannels(read(CHANNELS, "clinician-a", ORG_A).get("task"),
                "accepted", "pending", "unreviewed", false);

        assertEquals(200, post(path + "/send", null, "lab-a", ORG_A, "\"2\"")
                .getStatusCode().value());
        assertChannels(read(CHANNELS, "clinician-a", ORG_A).get("task"),
                "accepted", "sent", "unreviewed", false);

        // The body carries a forged reviewer; the service must still bind the
        // authenticated clinician, the frozen clock and the reviewed version.
        assertEquals(200, post(path + "/acknowledge",
                "{\"reason\":null,\"reviewedBy\":\"" + forged + "\"}", "clinician-a", ORG_A, "\"3\"")
                .getStatusCode().value());
        JsonNode acknowledged = read(CHANNELS, "clinician-a", ORG_A).get("task");
        assertChannels(acknowledged, "accepted", "sent", "acknowledged", true);
        assertEquals(actorId(ORG_A, "clinician-a"), acknowledged.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), acknowledged.get("reviewedAt").asText());
        assertEquals(3, acknowledged.get("reviewedRevision").asInt());

        assertEquals(200, post(path + "/process", null, "lab-a", ORG_A, "\"4\"")
                .getStatusCode().value());
        assertChannels(read(CHANNELS, "clinician-a", ORG_A).get("task"),
                "processing", "sent", "acknowledged", true);

        // A delivery receipt changes only transmission: review evidence and the
        // acknowledged clinical state must remain exactly as they were.
        assertEquals(200, post(path + "/deliver", null, "lab-a", ORG_A, "\"5\"")
                .getStatusCode().value());
        JsonNode delivered = read(CHANNELS, "clinician-a", ORG_A).get("task");
        assertChannels(delivered, "processing", "delivered", "acknowledged", true);
        assertEquals(actorId(ORG_A, "clinician-a"), delivered.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), delivered.get("reviewedAt").asText());
        assertEquals(3, delivered.get("reviewedRevision").asInt());

        assertEquals(200, post(path + "/request-action", null, "clinician-a", ORG_A, "\"6\"")
                .getStatusCode().value());
        assertChannels(read(CHANNELS, "clinician-a", ORG_A).get("task"),
                "processing", "delivered", "action_pending", true);

        assertEquals(200, post(path + "/complete", null, "lab-a", ORG_A, "\"7\"")
                .getStatusCode().value());
        assertChannels(read(CHANNELS, "clinician-a", ORG_A).get("task"),
                "completed", "delivered", "action_pending", true);

        assertEquals(200, post(path + "/resolve", null, "clinician-a", ORG_A, "\"8\"")
                .getStatusCode().value());
        JsonNode view = read(CHANNELS, "clinician-a", ORG_A);
        assertChannels(view.get("task"), "completed", "delivered", "resolved", true);
        assertEquals(9, view.get("task").get("version").asInt());
        assertEquals(8, view.get("task").get("reviewedRevision").asInt());
        JsonNode history = view.get("history");
        assertEquals(9, history.size());
        String[] channels = {"workflow", "workflow", "transmission", "clinical_review", "workflow",
                "transmission", "clinical_review", "workflow", "clinical_review"};
        for (int index = 0; index < channels.length; index++) {
            assertEquals(channels[index], history.get(index).get("channel").asText());
        }
        assertEquals(3, history.get(3).get("reviewedRevision").asInt());
        assertEquals(6, history.get(6).get("reviewedRevision").asInt());
        assertEquals(8, history.get(8).get("reviewedRevision").asInt());
    }

    @Test
    void workflowChainMovesRequestedToAcceptedToProcessingToCompleted() throws Exception {
        String path = "/api/v1/exchange-tasks/" + WORKFLOW;

        ResponseEntity<String> accepted = post(path + "/accept", null, "lab-a", ORG_A, "\"1\"");
        assertEquals(200, accepted.getStatusCode().value(), accepted.getBody());
        assertChannels(json(accepted), "accepted", "pending", "unreviewed", false);

        ResponseEntity<String> processing = post(path + "/process", null, "lab-a", ORG_A, "\"2\"");
        assertEquals(200, processing.getStatusCode().value(), processing.getBody());
        assertChannels(json(processing), "processing", "pending", "unreviewed", false);

        ResponseEntity<String> completed = post(path + "/complete", null, "lab-a", ORG_A, "\"3\"");
        assertEquals(200, completed.getStatusCode().value(), completed.getBody());
        assertChannels(json(completed), "completed", "pending", "unreviewed", false);
        assertEquals(4, json(completed).get("version").asInt());

        JsonNode view = read(WORKFLOW, "clinician-a", ORG_A);
        assertChannels(view.get("task"), "completed", "pending", "unreviewed", false);
        JsonNode history = view.get("history");
        assertEquals(4, history.size());
        String[] actions = {"EXCHANGE_CREATE", "EXCHANGE_ACCEPT", "EXCHANGE_PROCESS",
                "EXCHANGE_COMPLETE"};
        String[] toStates = {"requested", "accepted", "processing", "completed"};
        for (int index = 0; index < actions.length; index++) {
            assertEquals("workflow", history.get(index).get("channel").asText());
            assertEquals(actions[index], history.get(index).get("action").asText());
            assertEquals(toStates[index], history.get(index).get("toState").asText());
        }
        assertEquals("requested", history.get(1).get("fromState").asText());
        assertEquals("accepted", history.get(2).get("fromState").asText());
        assertEquals("processing", history.get(3).get("fromState").asText());
    }

    @Test
    void rejectedAndCancelledBranchesAreTerminal() throws Exception {
        ResponseEntity<String> noReason = post("/api/v1/exchange-tasks/" + REJECT + "/reject",
                "{}", "lab-a", ORG_A, "\"1\"");
        assertEquals(422, noReason.getStatusCode().value(), noReason.getBody());
        assertEquals("REASON_REQUIRED", json(noReason).get("code").asText());
        JsonNode unchanged = read(REJECT, "clinician-a", ORG_A);
        assertChannels(unchanged.get("task"), "requested", "pending", "unreviewed", false);
        assertEquals(1, unchanged.get("task").get("version").asInt());
        assertEquals(1, unchanged.get("history").size());

        ResponseEntity<String> rejected = post("/api/v1/exchange-tasks/" + REJECT + "/reject",
                "{\"reason\":\"duplicate order\"}", "lab-a", ORG_A, "\"1\"");
        assertEquals(200, rejected.getStatusCode().value(), rejected.getBody());
        assertEquals("rejected", json(rejected).get("workflowState").asText());
        assertEquals("duplicate order", json(rejected).get("reason").asText());
        JsonNode rejection = read(REJECT, "clinician-a", ORG_A);
        assertEquals("duplicate order", rejection.get("history").get(1).get("reason").asText());
        ResponseEntity<String> afterReject = post("/api/v1/exchange-tasks/" + REJECT + "/accept",
                null, "lab-a", ORG_A, "\"2\"");
        assertEquals(409, afterReject.getStatusCode().value(), afterReject.getBody());
        assertEquals("INVALID_TRANSITION", json(afterReject).get("code").asText());
        assertEquals("rejected", read(REJECT, "clinician-a", ORG_A).get("task")
                .get("workflowState").asText());

        ResponseEntity<String> noCancelReason = post("/api/v1/exchange-tasks/" + CANCEL + "/cancel",
                "{}", "lab-a", ORG_A, "\"1\"");
        assertEquals(422, noCancelReason.getStatusCode().value(), noCancelReason.getBody());
        assertEquals("REASON_REQUIRED", json(noCancelReason).get("code").asText());

        ResponseEntity<String> cancelled = post("/api/v1/exchange-tasks/" + CANCEL + "/cancel",
                "{\"reason\":\"sender withdrawal\"}", "lab-a", ORG_A, "\"1\"");
        assertEquals(200, cancelled.getStatusCode().value(), cancelled.getBody());
        assertEquals("cancelled", json(cancelled).get("workflowState").asText());
        assertEquals("sender withdrawal", read(CANCEL, "clinician-a", ORG_A)
                .get("history").get(1).get("reason").asText());
        ResponseEntity<String> afterCancel = post("/api/v1/exchange-tasks/" + CANCEL + "/process",
                null, "lab-a", ORG_A, "\"2\"");
        assertEquals(409, afterCancel.getStatusCode().value(), afterCancel.getBody());
        assertEquals("cancelled", read(CANCEL, "clinician-a", ORG_A).get("task")
                .get("workflowState").asText());
    }

    @Test
    void transmissionChainLeavesClinicalReviewUntouched() throws Exception {
        String deliveredPath = "/api/v1/exchange-tasks/" + TRANSMIT;
        assertEquals(200, post(deliveredPath + "/send", null, "lab-a", ORG_A, "\"1\"")
                .getStatusCode().value());
        assertChannels(read(TRANSMIT, "clinician-a", ORG_A).get("task"),
                "requested", "sent", "unreviewed", false);
        assertEquals(200, post(deliveredPath + "/deliver", null, "lab-a", ORG_A, "\"2\"")
                .getStatusCode().value());
        JsonNode delivered = read(TRANSMIT, "clinician-a", ORG_A);
        assertChannels(delivered.get("task"), "requested", "delivered", "unreviewed", false);
        for (JsonNode event : delivered.get("history")) {
            assertFalse("clinical_review".equals(event.get("channel").asText()));
            assertTrue(event.get("reviewedRevision").isNull());
        }

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_review_state, clinical_reviewed_at, clinical_revision, "
                            + "clinical_review_actor_id FROM public.exchange_task "
                            + "WHERE id = '" + TRANSMIT + "'")) {
                assertTrue(result.next());
                assertEquals("unreviewed", result.getString(1));
                assertNull(result.getObject(2));
                assertNull(result.getObject(3));
                assertNull(result.getObject(4));
            }
        }

        String failedPath = "/api/v1/exchange-tasks/" + FAIL;
        assertEquals(200, post(failedPath + "/send", null, "lab-a", ORG_A, "\"1\"")
                .getStatusCode().value());
        assertEquals(200, post(failedPath + "/fail", null, "lab-a", ORG_A, "\"2\"")
                .getStatusCode().value());
        JsonNode failed = read(FAIL, "clinician-a", ORG_A);
        assertChannels(failed.get("task"), "requested", "failed", "unreviewed", false);
        for (JsonNode event : failed.get("history")) {
            assertFalse("clinical_review".equals(event.get("channel").asText()));
        }
    }

    @Test
    void clinicalReviewNeedsHumanAcknowledgment() throws Exception {
        String path = "/api/v1/exchange-tasks/" + REVIEW;
        ResponseEntity<String> tooEarly = post(path + "/resolve", null, "clinician-a", ORG_A, "\"1\"");
        assertEquals(409, tooEarly.getStatusCode().value(), tooEarly.getBody());
        assertEquals("INVALID_TRANSITION", json(tooEarly).get("code").asText());
        assertChannels(read(REVIEW, "clinician-a", ORG_A).get("task"),
                "requested", "pending", "unreviewed", false);

        String expectedActor = actorId(ORG_A, "clinician-a");
        assertEquals(200, post(path + "/acknowledge",
                "{\"reason\":null,\"reviewedBy\":\"" + UUID.randomUUID() + "\"}",
                "clinician-a", ORG_A, "\"1\"").getStatusCode().value());
        JsonNode acknowledged = json(post(path + "/request-action", null, "clinician-a", ORG_A, "\"2\""));
        assertEquals(2, acknowledged.get("reviewedRevision").asInt());
        JsonNode resolvedResponse = json(post(path + "/resolve", null, "clinician-a", ORG_A, "\"3\""));
        assertEquals(3, resolvedResponse.get("reviewedRevision").asInt());
        assertEquals("resolved", resolvedResponse.get("clinicalReviewState").asText());

        JsonNode view = read(REVIEW, "clinician-a", ORG_A);
        JsonNode task = view.get("task");
        assertChannels(task, "requested", "pending", "resolved", true);
        assertEquals(expectedActor, task.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), task.get("reviewedAt").asText());
        assertEquals(3, task.get("reviewedRevision").asInt());
        assertEquals(4, task.get("version").asInt());
        JsonNode history = view.get("history");
        assertEquals(4, history.size());
        int[] revisions = {1, 2, 3};
        String[] actions = {"EXCHANGE_ACKNOWLEDGE", "EXCHANGE_REQUEST_ACTION", "EXCHANGE_RESOLVE"};
        for (int index = 0; index < revisions.length; index++) {
            assertEquals("clinical_review", history.get(index + 1).get("channel").asText());
            assertEquals(actions[index], history.get(index + 1).get("action").asText());
            assertEquals(revisions[index], history.get(index + 1).get("reviewedRevision").asInt());
            assertEquals(expectedActor, history.get(index + 1).get("actorId").asText());
        }
        assertEquals(409, post(path + "/acknowledge", null, "clinician-a", ORG_A, "\"4\"")
                .getStatusCode().value());
    }

    @Test
    void forbiddenTransitionIsRefused() throws Exception {
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + GUARD + "/complete",
                null, "lab-a", ORG_A, "\"1\"");
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        JsonNode view = read(GUARD, "clinician-a", ORG_A);
        assertChannels(view.get("task"), "requested", "pending", "unreviewed", false);
        assertEquals(1, view.get("task").get("version").asInt());
        assertEquals(1, view.get("history").size());
    }

    @Test
    void wrongRoleCannotAcknowledge() throws Exception {
        String path = "/api/v1/exchange-tasks/" + ROLE + "/acknowledge";
        ResponseEntity<String> lab = post(path, null, "lab-a", ORG_A, "\"1\"");
        assertEquals(403, lab.getStatusCode().value(), lab.getBody());
        assertEquals("FORBIDDEN", json(lab).get("code").asText());
        ResponseEntity<String> reception = post(path, null, "reception-a", ORG_A, "\"1\"");
        assertEquals(403, reception.getStatusCode().value(), reception.getBody());
        assertEquals("FORBIDDEN", json(reception).get("code").asText());
        JsonNode view = read(ROLE, "clinician-a", ORG_A);
        assertChannels(view.get("task"), "requested", "pending", "unreviewed", false);
        assertEquals(1, view.get("task").get("version").asInt());
    }

    @Test
    void peerTenantCannotSeeOrMoveTask() throws Exception {
        assertEquals(200, get(PEER, "peer-b", ORG_B).getStatusCode().value());
        ResponseEntity<String> concealed = get(PEER, "clinician-a", ORG_A);
        assertEquals(404, concealed.getStatusCode().value(), concealed.getBody());
        assertEquals("NOT_FOUND", json(concealed).get("code").asText());
        ResponseEntity<String> refused = post("/api/v1/exchange-tasks/" + PEER + "/acknowledge",
                null, "clinician-a", ORG_A, "\"1\"");
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        JsonNode view = read(PEER, "peer-b", ORG_B);
        assertChannels(view.get("task"), "requested", "pending", "unreviewed", false);
        assertEquals(1, view.get("task").get("version").asInt());
    }

    @Test
    void staleVersionCannotAdvance() throws Exception {
        String path = "/api/v1/exchange-tasks/" + STALE;
        ResponseEntity<String> tooHigh = post(path + "/accept", null, "lab-a", ORG_A, "\"7\"");
        assertEquals(412, tooHigh.getStatusCode().value(), tooHigh.getBody());
        assertEquals("STALE_VERSION", json(tooHigh).get("code").asText());
        assertEquals(1, read(STALE, "clinician-a", ORG_A).get("task").get("version").asInt());

        assertEquals(200, post(path + "/accept", null, "lab-a", ORG_A, "\"1\"")
                .getStatusCode().value());
        // Replaying the version that was just consumed is the real stale case.
        ResponseEntity<String> replay = post(path + "/process", null, "lab-a", ORG_A, "\"1\"");
        assertEquals(412, replay.getStatusCode().value(), replay.getBody());
        assertEquals("STALE_VERSION", json(replay).get("code").asText());
        JsonNode view = read(STALE, "clinician-a", ORG_A);
        assertChannels(view.get("task"), "accepted", "pending", "unreviewed", false);
        assertEquals(2, view.get("task").get("version").asInt());
        assertEquals(2, view.get("history").size());
    }
}
