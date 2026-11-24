package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
 * S-G2-02 acceptance (XCH-02) on a real PostgreSQL: three independent state
 * channels, human acknowledgment binding, append-only history, forced RLS and
 * two tenants that share the same tenant-local order reference.
 *
 * <p>Each mutating test owns its own seeded task, so JUnit method order cannot
 * make one test observe another's transitions. All fixtures are synthetic and
 * the frozen clock makes the recorded acknowledgment instant deterministic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_xch02_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ExchangeTaskIsolationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String READ = "00000001-0000-0000-0000-000000000000";
    private static final String WORKFLOW = "00000002-0000-0000-0000-000000000000";
    private static final String SENT = "00000003-0000-0000-0000-000000000000";
    private static final String FAILED = "00000004-0000-0000-0000-000000000000";
    private static final String ACK = "00000005-0000-0000-0000-000000000000";
    private static final String REVIEW = "00000006-0000-0000-0000-000000000000";
    private static final String GUARD = "00000007-0000-0000-0000-000000000000";
    private static final String FORGED = "00000008-0000-0000-0000-000000000000";
    private static final String REVOKED_TASK = "00000009-0000-0000-0000-000000000000";
    private static final String MUTATE = "0000000a-0000-0000-0000-000000000000";
    private static final String PEER = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_xch02_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final Instant FIXED = Instant.parse("2026-11-19T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Deterministic clock for the acknowledgment timestamp. */
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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch02_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_xch02_it OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
            for (String org : new String[] {ORG_A, ORG_B}) {
                String suffix = org.equals(ORG_A) ? "a" : "b";
                seedMember(statement, org, "clinician-" + suffix, "clinician");
                seedMember(statement, org, "lab-" + suffix, "lab_collector");
                seedMember(statement, org, "reception-" + suffix, "reception");
                seedMember(statement, org, "admin-" + suffix, "tenant_admin");
                seedMember(statement, org, "revoke-" + suffix, "clinician");
            }
            // FORGED is deliberately not seeded here: the storage-forgery test
            // inserts that row itself to prove the INSERT/V20 guard normalises
            // poisoned state and resets its workflow/transmission/review channels.
            String[] ids = {READ, WORKFLOW, SENT, FAILED, ACK, REVIEW, GUARD,
                    REVOKED_TASK, MUTATE};
            for (int index = 0; index < ids.length; index++) {
                seedTask(statement, ORG_A, ids[index], "LOCAL-" + (index + 1));
            }
            // Both tenants use the same local reference so RLS concealment is observable.
            seedTask(statement, ORG_B, PEER, "LOCAL-1");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_xch02_it WITH (FORCE)");
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
    void createOpensTheThreeInitialChannelsWithOneHistoryEvent() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/v1/exchange-tasks",
                "clinician-a", ORG_A, "{\"orderRef\":\"LOCAL-NEW\"}", null);
        assertEquals(201, response.getStatusCode().value());
        JsonNode created = json(response);
        String id = created.get("id").asText();
        assertEquals("requested", created.get("workflowState").asText());
        assertEquals("pending", created.get("transmissionState").asText());
        assertEquals("unreviewed", created.get("clinicalReviewState").asText());

        JsonNode view = get(id, "clinician-a", ORG_A);
        assertEquals(1, view.get("task").get("version").asInt());
        assertEquals(1, view.get("history").size());
        assertEquals("EXCHANGE_CREATE", view.get("history").get(0).get("action").asText());
        assertEquals("workflow", view.get("history").get(0).get("channel").asText());

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE action = 'EXCHANGE_CREATE' "
                            + "AND outcome = 'SUCCESS' AND resource_id = '" + id + "'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void readsIndependentDimensionsAndAppendOnlyHistory() throws Exception {
        JsonNode view = get(READ, "clinician-a", ORG_A);
        JsonNode task = view.get("task");
        assertEquals("requested", task.get("workflowState").asText());
        assertEquals("pending", task.get("transmissionState").asText());
        assertEquals("unreviewed", task.get("clinicalReviewState").asText());
        assertEquals(1, task.get("version").asInt());
        assertEquals(1, view.get("history").size());

        // The owner keeps UPDATE privilege but FORCE RLS still binds it to the
        // active tenant, so this exercises reject_immutable_mutation() itself
        // rather than the runtime role's missing privilege.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(java.sql.SQLException.class, () -> statement.execute(
                    "UPDATE public.exchange_task_transition SET to_state = 'requested' "
                            + "WHERE task_id = '" + READ + "'"));
        }
    }

    @Test
    void workflowPathRejectsReasonsAndIllegalTransitions() throws Exception {
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + WORKFLOW + "/accept", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals("accepted", get(WORKFLOW, "clinician-a", ORG_A).get("task")
                .get("workflowState").asText());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + WORKFLOW + "/process", "lab-a", ORG_A, null, "\"2\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + WORKFLOW + "/complete", "lab-a", ORG_A, null, "\"3\"")
                .getStatusCode().value());
        JsonNode completed = get(WORKFLOW, "clinician-a", ORG_A);
        assertEquals("completed", completed.get("task").get("workflowState").asText());
        assertEquals(4, completed.get("task").get("version").asInt());
        assertEquals(4, completed.get("history").size());

        assertEquals(409, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/complete", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(422, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/reject", "lab-a", ORG_A,
                "{}", "\"1\"").getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/reject", "lab-a", ORG_A,
                "{\"reason\":\"duplicate order\"}", "\"1\"").getStatusCode().value());
        assertEquals("rejected", get(GUARD, "clinician-a", ORG_A)
                .get("task").get("workflowState").asText());
    }

    @Test
    void transmissionAcknowledgmentNeverClosesAClinicalTask() throws Exception {
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + SENT + "/send", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + SENT + "/deliver", "lab-a", ORG_A, null, "\"2\"")
                .getStatusCode().value());
        JsonNode view = get(SENT, "clinician-a", ORG_A);
        JsonNode task = view.get("task");
        assertEquals("delivered", task.get("transmissionState").asText());
        assertEquals("unreviewed", task.get("clinicalReviewState").asText());
        assertTrue(task.get("reviewedBy").isNull());
        assertTrue(task.get("reviewedAt").isNull());
        assertTrue(task.get("reviewedRevision").isNull());
        assertEquals("requested", task.get("workflowState").asText());
        for (JsonNode event : view.get("history")) {
            assertTrue(!"clinical_review".equals(event.get("channel").asText()));
        }

        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + FAILED + "/send", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + FAILED + "/fail", "lab-a", ORG_A, null, "\"2\"")
                .getStatusCode().value());
        assertEquals("unreviewed", get(FAILED, "clinician-a", ORG_A)
                .get("task").get("clinicalReviewState").asText());

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_review_state, clinical_review_actor_id FROM public.exchange_task "
                            + "WHERE id = '" + SENT + "'")) {
                assertTrue(result.next());
                assertEquals("unreviewed", result.getString(1));
                assertEquals(null, result.getString(2));
            }
        }
    }

    @Test
    void humanAcknowledgmentBindsContextActorClockAndRevision() throws Exception {
        String expectedActor = actorId(ORG_A, "clinician-a");
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + ACK + "/acknowledge", "clinician-a", ORG_A,
                "{\"reason\":null}", "\"1\"").getStatusCode().value());

        JsonNode task = get(ACK, "clinician-a", ORG_A).get("task");
        assertEquals("acknowledged", task.get("clinicalReviewState").asText());
        assertEquals(expectedActor, task.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), task.get("reviewedAt").asText());
        assertEquals(1, task.get("reviewedRevision").asInt());

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE action = 'EXCHANGE_ACKNOWLEDGE' "
                            + "AND outcome = 'SUCCESS' AND resource_id = '" + ACK + "'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void reviewChainRequiresAcknowledgmentAndIsAppendOnly() throws Exception {
        assertEquals(409, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + REVIEW + "/resolve", "clinician-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + REVIEW + "/acknowledge", "clinician-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + REVIEW + "/request-action", "clinician-a", ORG_A, null, "\"2\"")
                .getStatusCode().value());
        assertEquals(200, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + REVIEW + "/resolve", "clinician-a", ORG_A, null, "\"3\"")
                .getStatusCode().value());

        JsonNode view = get(REVIEW, "clinician-a", ORG_A);
        assertEquals("resolved", view.get("task").get("clinicalReviewState").asText());
        assertEquals(4, view.get("task").get("version").asInt());
        assertEquals(4, view.get("history").size());
        assertEquals(409, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + REVIEW + "/acknowledge", "clinician-a", ORG_A, null, "\"4\"")
                .getStatusCode().value());
        assertEquals("requested", view.get("task").get("workflowState").asText());
    }

    @Test
    void authorizationPreconditionsAndTenantConcealment() throws Exception {
        assertEquals(403, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/acknowledge", "reception-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(403, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/acknowledge", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(401, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/acknowledge", "", ORG_A, null, "\"1\"")
                .getStatusCode().value());
        assertEquals(428, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/accept", "lab-a", ORG_A, null, null)
                .getStatusCode().value());
        assertEquals(422, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/accept", "lab-a", ORG_A, null, "\"abc\"")
                .getStatusCode().value());
        assertEquals(412, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/accept", "lab-a", ORG_A, null, "\"7\"")
                .getStatusCode().value());
        assertEquals("unreviewed", get(GUARD, "clinician-a", ORG_A)
                .get("task").get("clinicalReviewState").asText());

        // The peer task belongs to ORG_B; an ORG_A caller must not even learn it exists.
        assertEquals(404, call(HttpMethod.GET,
                "/api/v1/exchange-tasks/" + PEER, "clinician-a", ORG_A, null, null)
                .getStatusCode().value());
        assertEquals(404, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + PEER + "/acknowledge", "clinician-a", ORG_A,
                null, "\"1\"").getStatusCode().value());
        assertEquals(200, call(HttpMethod.GET,
                "/api/v1/exchange-tasks/" + READ, "clinician-a", ORG_A, null, null)
                .getStatusCode().value());
        assertEquals(422, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + GUARD + "/unknown", "lab-a", ORG_A, null, "\"1\"")
                .getStatusCode().value());
    }

    @Test
    void storageCannotForgeAReviewedTaskOrMoveTwoChannels() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            String actor = actorId(ORG_A, "clinician-a");

            // A raw SQL writer must not be able to create an already-resolved
            // clinical task with a chosen actor, revision and timestamp.
            statement.execute("INSERT INTO public.exchange_task "
                    + "(organization_id, id, order_ref, workflow_state, transmission_state, "
                    + "clinical_review_state, clinical_reviewed_at, clinical_revision, "
                    + "clinical_review_actor_id) VALUES ('" + ORG_A + "', '" + FORGED
                    + "', 'FORGED', 'completed', 'delivered', 'resolved', now(), 9, '"
                    + actor + "')");
            try (ResultSet result = statement.executeQuery(
                    "SELECT workflow_state, transmission_state, clinical_review_state, "
                            + "clinical_reviewed_at, clinical_revision, clinical_review_actor_id "
                            + "FROM public.exchange_task WHERE id = '" + FORGED + "'")) {
                assertTrue(result.next());
                assertEquals("requested", result.getString(1));
                assertEquals("pending", result.getString(2));
                assertEquals("unreviewed", result.getString(3));
                assertNull(result.getObject(4));
                assertNull(result.getObject(5));
                assertNull(result.getObject(6));
            }

            // The V20 guard rejects a write that moves two channels or skips a version.
            assertThrows(java.sql.SQLException.class, () -> statement.execute(
                    "UPDATE public.exchange_task SET workflow_state = 'accepted', "
                            + "transmission_state = 'sent', version = version + 1 "
                            + "WHERE id = '" + MUTATE + "'"));
            assertThrows(java.sql.SQLException.class, () -> statement.execute(
                    "UPDATE public.exchange_task SET workflow_state = 'accepted', "
                            + "version = version + 2 WHERE id = '" + MUTATE + "'"));
        }
    }

    @Test
    void revokedMembershipDeniesTheNextProtectedRequest() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                    + "SELECT '" + ORG_A + "', id, 'revoked' FROM public.user_identity "
                    + "WHERE organization_id = '" + ORG_A + "' AND subject = 'revoke-a'");
        }

        assertEquals(403, call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + REVOKED_TASK + "/acknowledge", "revoke-a", ORG_A,
                null, "\"1\"").getStatusCode().value());
        assertEquals(403, call(HttpMethod.GET,
                "/api/v1/exchange-tasks/" + REVOKED_TASK, "revoke-a", ORG_A,
                null, null).getStatusCode().value());

        // The database authority also fails closed for a deactivated actor: it
        // cannot attribute a ledger or review event to a revoked clinician.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'revoke-a'");
            assertThrows(java.sql.SQLException.class, () -> statement.execute(
                    "INSERT INTO public.exchange_task_transition "
                            + "(organization_id, task_id, to_version, channel, action, "
                            + "from_state, to_state) VALUES ('" + ORG_A + "', '"
                            + REVOKED_TASK + "', 2, 'workflow', 'EXCHANGE_WORKFLOW', "
                            + "'requested', 'accepted')"));
        }
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
