package com.aurevia.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
 * XCH-02 clause 3 proof over the HTTP API on real PostgreSQL: a partner delivery
 * receipt is a transmission fact, not a human clinical acknowledgment, and can
 * neither advance the clinical review channel nor close a clinical task.
 *
 * <p>The proof spans all three enforcement layers. Through
 * {@code POST /api/v1/exchange-tasks/{id}/{command}} it checks that the domain
 * state machine advances only the transmission channel and copies clinical
 * evidence unchanged, that the clinical review action policy admits only
 * clinicians, and that a stale version is refused before any transition. Direct
 * {@code aurevia_app} SQL then checks the V20 {@code v_changed <> 1} guard
 * and the clinical-transition guard, so a storage writer cannot combine a
 * delivery with a clinical resolution. A clinician-only closure is exercised as
 * a positive control so a refusal of {@code resolve} cannot be confused with a
 * globally broken command.</p>
 *
 * <p>Each mutating test seeds and owns its own task id in a database dedicated
 * to this class, so JUnit method order cannot make one test observe another's
 * transitions. All fixtures are synthetic and the frozen clock makes clinical
 * review evidence deterministic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_xch02_ack_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class TransmissionAcknowledgmentCannotCloseClinicalTaskTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DELIVERY_TASK = "00000001-0000-0000-0000-000000000001";
    private static final String RESOLVE_TASK = "00000001-0000-0000-0000-000000000002";
    private static final String FORGED_TASK = "00000001-0000-0000-0000-000000000003";
    private static final String ROLE_TASK = "00000001-0000-0000-0000-000000000004";
    private static final String CLOSURE_TASK = "00000001-0000-0000-0000-000000000005";
    private static final String PEER_TASK = "00000001-0000-0000-0000-000000000006";
    private static final String DB = "aurevia_xch02_ack_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final Instant FIXED = Instant.parse("2026-11-19T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Deterministic clock for any clinical review evidence recorded in this class. */
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

    /** Create an isolated database, apply the production migrations and seed synthetic tenants and tasks. */
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
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "lab-a", "lab_collector");
            seedMember(statement, ORG_B, "clinician-b", "clinician");
            seedMember(statement, ORG_B, "lab-b", "lab_collector");
            seedTask(statement, ORG_A, DELIVERY_TASK, "LOCAL-DELIVERY");
            seedTask(statement, ORG_A, RESOLVE_TASK, "LOCAL-RESOLVE");
            seedTask(statement, ORG_A, FORGED_TASK, "LOCAL-FORGED");
            seedTask(statement, ORG_A, ROLE_TASK, "LOCAL-ROLE");
            seedTask(statement, ORG_A, CLOSURE_TASK, "LOCAL-CLOSURE");
            seedTask(statement, ORG_B, PEER_TASK, "LOCAL-PEER");
        }
    }

    /** Drop the synthetic database after the class. */
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
        statement.execute("SET app.actor = 'clinician-" + (org.equals(ORG_A) ? "a" : "b") + "'");
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
                .header("X-Active-Organization", org);
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

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private JsonNode get(String id, String subject, String org) throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET,
                "/api/v1/exchange-tasks/" + id, subject, org, null, null);
        assertEquals(200, response.getStatusCode().value());
        return json(response);
    }

    @Test
    void aDeliveryReceiptLeavesClinicalReviewUnreviewedAndUnbound() throws Exception {
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + DELIVERY_TASK + "/send", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + DELIVERY_TASK + "/deliver", "lab-a", ORG_A, null, "\"2\"")
                .getStatusCode().value());

        JsonNode view = get(DELIVERY_TASK, "clinician-a", ORG_A);
        JsonNode task = view.get("task");
        assertEquals("delivered", task.get("transmissionState").asText());
        assertEquals("requested", task.get("workflowState").asText());
        assertEquals("unreviewed", task.get("clinicalReviewState").asText());
        assertTrue(task.get("reviewedBy").isNull());
        assertTrue(task.get("reviewedAt").isNull());
        assertTrue(task.get("reviewedRevision").isNull());
        assertEquals(3, task.get("version").asInt());
        assertEquals(3, view.get("history").size());
        int transmissionEvents = 0;
        for (JsonNode event : view.get("history")) {
            // A delivery receipt writes only the transmission channel: no clinical event exists.
            assertTrue(!"clinical_review".equals(event.get("channel").asText()));
            if ("transmission".equals(event.get("channel").asText())) {
                transmissionEvents++;
            }
        }
        assertEquals(2, transmissionEvents);

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_review_state, clinical_reviewed_at, clinical_revision, "
                            + "clinical_review_actor_id FROM public.exchange_task "
                            + "WHERE id = '" + DELIVERY_TASK + "'")) {
                assertTrue(result.next());
                assertEquals("unreviewed", result.getString(1));
                assertNull(result.getObject(2));
                assertNull(result.getObject(3));
                assertNull(result.getObject(4));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.exchange_task_transition "
                            + "WHERE task_id = '" + DELIVERY_TASK + "' "
                            + "AND channel = 'clinical_review'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void resolvingAfterOnlyADeliveryReceiptIsRefusedAndWritesNothing() throws Exception {
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + RESOLVE_TASK + "/send", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + RESOLVE_TASK + "/deliver", "lab-a", ORG_A, null, "\"2\"")
                .getStatusCode().value());

        // At the correct version the illegal unreviewed -> resolved transition is refused.
        ResponseEntity<String> illegal = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + RESOLVE_TASK + "/resolve", "clinician-a", ORG_A,
                null, "\"3\"");
        assertEquals(409, illegal.getStatusCode().value());
        assertEquals("INVALID_TRANSITION", json(illegal).get("code").asText());
        assertEquals("the state channel cannot make that transition",
                json(illegal).get("message").asText());

        // The version guard runs even before transition legality, so a stale receipt version fails too.
        ResponseEntity<String> stale = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + RESOLVE_TASK + "/resolve", "clinician-a", ORG_A,
                null, "\"1\"");
        assertEquals(412, stale.getStatusCode().value());
        assertEquals("STALE_VERSION", json(stale).get("code").asText());

        JsonNode view = get(RESOLVE_TASK, "clinician-a", ORG_A);
        JsonNode task = view.get("task");
        assertEquals("delivered", task.get("transmissionState").asText());
        assertEquals("unreviewed", task.get("clinicalReviewState").asText());
        assertEquals(3, task.get("version").asInt());
        assertTrue(task.get("reviewedBy").isNull());
        assertTrue(task.get("reviewedAt").isNull());
        assertTrue(task.get("reviewedRevision").isNull());
        assertEquals(3, view.get("history").size());
        for (JsonNode event : view.get("history")) {
            assertTrue(!"clinical_review".equals(event.get("channel").asText()));
        }
    }

    @Test
    void aForgedUpdateCannotDeliverAndResolveInOneWrite() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");

            // The evidence columns are supplied so only the exactly-one-channel guard can reject
            // this write; a null revision would otherwise fail the evidence CHECK independently.
            SQLException rejected = assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.exchange_task SET version = version + 1, "
                            + "transmission_state = 'delivered', clinical_review_state = 'resolved', "
                            + "clinical_reviewed_at = now(), clinical_revision = 9 "
                            + "WHERE id = '" + FORGED_TASK + "'"));
            assertEquals("23514", rejected.getSQLState());
            assertTrue(rejected.getMessage().contains("exactly one state channel"),
                    rejected.getMessage());

            // The database alone also refuses a single-channel leap straight to resolved.
            SQLException illegal = assertThrows(SQLException.class, () -> statement.execute(
                    "UPDATE public.exchange_task SET version = version + 1, "
                            + "clinical_review_state = 'resolved' WHERE id = '" + FORGED_TASK + "'"));
            assertEquals("23514", illegal.getSQLState());
            assertTrue(illegal.getMessage().contains("illegal exchange clinical review transition"),
                    illegal.getMessage());

            try (ResultSet result = statement.executeQuery(
                    "SELECT workflow_state, transmission_state, clinical_review_state, version, "
                            + "clinical_reviewed_at, clinical_revision, clinical_review_actor_id "
                            + "FROM public.exchange_task WHERE id = '" + FORGED_TASK + "'")) {
                assertTrue(result.next());
                assertEquals("requested", result.getString(1));
                assertEquals("pending", result.getString(2));
                assertEquals("unreviewed", result.getString(3));
                assertEquals(1, result.getInt(4));
                assertNull(result.getObject(5));
                assertNull(result.getObject(6));
                assertNull(result.getObject(7));
            }
        }
    }

    @Test
    void labCollectorCannotAcknowledgeOrResolveAClinicalTask() throws Exception {
        ResponseEntity<String> acknowledge = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + ROLE_TASK + "/acknowledge", "lab-a", ORG_A, null, "\"1\"");
        assertEquals(403, acknowledge.getStatusCode().value());
        assertEquals("FORBIDDEN", json(acknowledge).get("code").asText());
        assertEquals("missing exchange permission", json(acknowledge).get("message").asText());

        ResponseEntity<String> resolve = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + ROLE_TASK + "/resolve", "lab-a", ORG_A, null, "\"1\"");
        assertEquals(403, resolve.getStatusCode().value());
        assertEquals("FORBIDDEN", json(resolve).get("code").asText());

        JsonNode view = get(ROLE_TASK, "clinician-a", ORG_A);
        JsonNode task = view.get("task");
        assertEquals("unreviewed", task.get("clinicalReviewState").asText());
        assertEquals(1, task.get("version").asInt());
        assertTrue(task.get("reviewedBy").isNull());
        assertTrue(task.get("reviewedAt").isNull());
        assertTrue(task.get("reviewedRevision").isNull());
        assertEquals(1, view.get("history").size());
        for (JsonNode event : view.get("history")) {
            assertTrue(!"clinical_review".equals(event.get("channel").asText()));
        }
    }

    @Test
    void aClinicianCanStillCloseTheClinicalTaskAfterAProperAcknowledgment() throws Exception {
        String expectedActor = actorId(ORG_A, "clinician-a");
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + CLOSURE_TASK + "/acknowledge", "clinician-a", ORG_A,
                null, "\"1\"").getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + CLOSURE_TASK + "/request-action", "clinician-a", ORG_A,
                null, "\"2\"").getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + CLOSURE_TASK + "/resolve", "clinician-a", ORG_A,
                null, "\"3\"").getStatusCode().value());

        JsonNode view = get(CLOSURE_TASK, "clinician-a", ORG_A);
        JsonNode task = view.get("task");
        assertEquals("resolved", task.get("clinicalReviewState").asText());
        assertEquals(expectedActor, task.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), task.get("reviewedAt").asText());
        assertEquals(3, task.get("reviewedRevision").asInt());
        assertEquals(4, task.get("version").asInt());
        assertEquals("requested", task.get("workflowState").asText());
        assertEquals("pending", task.get("transmissionState").asText());
        int clinicalEvents = 0;
        for (JsonNode event : view.get("history")) {
            if ("clinical_review".equals(event.get("channel").asText())) {
                clinicalEvents++;
            }
        }
        assertEquals(3, clinicalEvents);
    }

    @Test
    void aDeliveryReceiptFromAnotherTenantCannotReachThisTask() throws Exception {
        // FORCE RLS conceals the peer task: even a valid lab actor of ORG_A cannot deliver to it.
        ResponseEntity<String> foreign = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + PEER_TASK + "/deliver", "lab-a", ORG_A, null, "\"1\"");
        assertEquals(404, foreign.getStatusCode().value());
        assertEquals("NOT_FOUND", json(foreign).get("code").asText());

        JsonNode peer = get(PEER_TASK, "clinician-b", ORG_B).get("task");
        assertEquals("pending", peer.get("transmissionState").asText());
        assertEquals("unreviewed", peer.get("clinicalReviewState").asText());
        assertEquals(1, peer.get("version").asInt());
        assertTrue(peer.get("reviewedBy").isNull());
    }

    private static String actorId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
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
}
