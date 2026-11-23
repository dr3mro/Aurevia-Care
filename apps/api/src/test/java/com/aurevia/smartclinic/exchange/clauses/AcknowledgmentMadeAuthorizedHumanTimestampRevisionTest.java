package com.aurevia.smartclinic.exchange.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * XCH-02 clause 2 API evidence: a clinical acknowledgment is made by an
 * authorized human and bound to that human, the clock timestamp and the exact
 * reviewed revision.
 *
 * <p>Every case enters through {@code POST /api/v1/exchange-tasks/{id}/acknowledge}
 * against a fresh real-PostgreSQL database created from the production Flyway
 * migrations. The positive case asserts that the response, the durable task row,
 * the append-only transition and the audit event all carry the same actor id,
 * frozen timestamp and reviewed version. Each refusal case re-reads the row and
 * proves that no clinical-review evidence was written. All fixtures are
 * synthetic and the frozen clock makes the recorded instant deterministic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_xch_ack_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String ACK = "00000001-0000-0000-0000-000000000000";
    private static final String RECEPTION_DENIED = "00000002-0000-0000-0000-000000000000";
    private static final String LAB_DENIED = "00000003-0000-0000-0000-000000000000";
    private static final String OTHER_TENANT = "00000004-0000-0000-0000-000000000000";
    private static final String STALE = "00000005-0000-0000-0000-000000000000";
    private static final String NO_PRECONDITION = "00000006-0000-0000-0000-000000000000";
    private static final String SPOOF = "00000007-0000-0000-0000-000000000000";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_xch_ack_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final Instant FIXED = Instant.parse("2026-11-19T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Deterministic clock so the recorded acknowledgment instant is exact. */
    @TestConfiguration
    static class FixedClock {
        /**
         * Supply the frozen application clock used for review evidence.
         *
         * @return the fixed clock
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    /** Bind the HTTP client to the randomly assigned server port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic tenants, members and one task per case.
     *
     * @throws Exception when the database cannot be created or seeded
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch_ack_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_xch_ack_it OWNER smartclinic_migrate");
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
            seedMember(statement, ORG_B, "clinician-b", "clinician");

            String[] tasks = {ACK, RECEPTION_DENIED, LAB_DENIED, STALE, NO_PRECONDITION, SPOOF};
            for (int index = 0; index < tasks.length; index++) {
                seedTask(statement, ORG_A, tasks[index], "ACK-LOCAL-" + (index + 1));
            }
            // The cross-tenant case targets a task that lives in ORG_A: an ORG_B
            // clinician must not even learn that the row exists.
            seedTask(statement, ORG_A, OTHER_TENANT, "ACK-LOCAL-CROSS");
        }
    }

    /** Drop the fixture database after the class. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_xch_ack_it WITH (FORCE)");
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
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    /**
     * Assert that an acknowledgment attempt was refused and left every clinical
     * review column and the clinical ledger untouched.
     */
    private void assertAcknowledgmentRefused(String taskId, String subject, String org,
                                             String ifMatch, String body, int expectedStatus)
            throws Exception {
        ResponseEntity<String> denied = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + taskId + "/acknowledge", subject, org, body, ifMatch);
        assertEquals(expectedStatus, denied.getStatusCode().value(), denied.getBody());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_review_state, clinical_review_actor_id, clinical_reviewed_at, "
                            + "clinical_revision, version FROM public.exchange_task "
                            + "WHERE id = '" + taskId + "'")) {
                assertTrue(result.next());
                assertEquals("unreviewed", result.getString("clinical_review_state"));
                assertNull(result.getString("clinical_review_actor_id"));
                assertNull(result.getObject("clinical_reviewed_at"));
                assertNull(result.getObject("clinical_revision"));
                assertEquals(1, result.getInt("version"));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.exchange_task_transition "
                            + "WHERE task_id = '" + taskId + "' AND channel = 'clinical_review'")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void acknowledgeByClinicianBindsHumanClockAndReviewedRevision() throws Exception {
        String expectedActor = actorId(ORG_A, "clinician-a");
        ResponseEntity<String> response = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + ACK + "/acknowledge", "clinician-a", ORG_A,
                "{\"reason\":null}", "\"1\"");
        assertEquals(200, response.getStatusCode().value(), response.getBody());

        JsonNode task = get(ACK, "clinician-a", ORG_A).get("task");
        assertEquals("acknowledged", task.get("clinicalReviewState").asText());
        assertEquals(expectedActor, task.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), task.get("reviewedAt").asText());
        assertEquals(1, task.get("reviewedRevision").asInt());
        assertEquals(2, task.get("version").asInt());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT workflow_state, transmission_state, clinical_review_state, "
                            + "clinical_review_actor_id::text, clinical_reviewed_at, "
                            + "clinical_revision, version FROM public.exchange_task "
                            + "WHERE id = '" + ACK + "'")) {
                assertTrue(result.next());
                // The clinical write leaves the other two channels unchanged.
                assertEquals("requested", result.getString("workflow_state"));
                assertEquals("pending", result.getString("transmission_state"));
                assertEquals("acknowledged", result.getString("clinical_review_state"));
                assertEquals(expectedActor, result.getString("clinical_review_actor_id"));
                assertEquals(FIXED, result.getObject("clinical_reviewed_at", OffsetDateTime.class).toInstant());
                assertEquals(1, result.getInt("clinical_revision"));
                assertEquals(2, result.getInt("version"));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT channel, action, from_state, to_state, reviewed_revision, "
                            + "actor_id::text, to_version FROM public.exchange_task_transition "
                            + "WHERE task_id = '" + ACK + "' AND channel = 'clinical_review'")) {
                assertTrue(result.next());
                assertEquals("clinical_review", result.getString("channel"));
                assertEquals("EXCHANGE_ACKNOWLEDGE", result.getString("action"));
                assertEquals("unreviewed", result.getString("from_state"));
                assertEquals("acknowledged", result.getString("to_state"));
                assertEquals(1, result.getInt("reviewed_revision"));
                assertEquals(expectedActor, result.getString("actor_id"));
                assertEquals(2, result.getInt("to_version"));
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE action = 'EXCHANGE_ACKNOWLEDGE' "
                            + "AND outcome = 'SUCCESS' AND resource_id = '" + ACK + "'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void receptionCannotAcknowledge() throws Exception {
        assertAcknowledgmentRefused(RECEPTION_DENIED, "reception-a", ORG_A, "\"1\"", null, 403);
    }

    @Test
    void labCollectorCannotAcknowledge() throws Exception {
        assertAcknowledgmentRefused(LAB_DENIED, "lab-a", ORG_A, "\"1\"", null, 403);
    }

    @Test
    void otherTenantClinicianCannotAcknowledge() throws Exception {
        assertAcknowledgmentRefused(OTHER_TENANT, "clinician-b", ORG_B, "\"1\"", null, 404);
    }

    @Test
    void staleRevisionCannotAcknowledge() throws Exception {
        assertAcknowledgmentRefused(STALE, "clinician-a", ORG_A, "\"2\"", null, 412);
    }

    @Test
    void missingIfMatchCannotAcknowledge() throws Exception {
        assertAcknowledgmentRefused(NO_PRECONDITION, "clinician-a", ORG_A, null, null, 428);
    }

    @Test
    void requestBodyCannotSpoofReviewEvidence() throws Exception {
        String expectedActor = actorId(ORG_A, "clinician-a");
        String spoofed = "{\"reason\":null"
                + ",\"reviewedBy\":\"00000000-0000-0000-0000-999999999999\""
                + ",\"actorId\":\"00000000-0000-0000-0000-999999999999\""
                + ",\"reviewedAt\":\"2000-01-01T00:00:00Z\""
                + ",\"reviewedRevision\":99"
                + ",\"clinicalReviewState\":\"resolved\"}";
        ResponseEntity<String> response = call(HttpMethod.POST,
                "/api/v1/exchange-tasks/" + SPOOF + "/acknowledge", "clinician-a", ORG_A,
                spoofed, "\"1\"");
        assertEquals(200, response.getStatusCode().value(), response.getBody());

        JsonNode task = get(SPOOF, "clinician-a", ORG_A).get("task");
        assertEquals(expectedActor, task.get("reviewedBy").asText());
        assertEquals(FIXED.toString(), task.get("reviewedAt").asText());
        assertEquals(1, task.get("reviewedRevision").asInt());
        assertEquals("acknowledged", task.get("clinicalReviewState").asText());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT clinical_review_actor_id::text, clinical_reviewed_at, clinical_revision "
                            + "FROM public.exchange_task WHERE id = '" + SPOOF + "'")) {
                assertTrue(result.next());
                assertEquals(expectedActor, result.getString("clinical_review_actor_id"));
                assertEquals(FIXED, result.getObject("clinical_reviewed_at", OffsetDateTime.class).toInstant());
                assertEquals(1, result.getInt("clinical_revision"));
            }
        }
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
}
