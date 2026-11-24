package com.aurevia.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CLN-06 clause 4 (API evidence): a care-team action task keeps an accountable
 * owner, a facility-local civil due date and a recorded outcome.
 *
 * <p>Every request goes through the real follow-up HTTP entry point against a
 * fresh PostgreSQL database created from the production Flyway migrations. The
 * positive proof opens a task and closes it with an outcome while the owner and
 * due date stay unchanged, in the response and in the persisted row. The negative
 * proofs show the rule refusing a completion by the wrong role, a task owned by
 * another tenant, a second completion of a closed task and a blank outcome. All
 * fixtures are synthetic; no production code is added by this suite.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_care_team_task_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CareTeamActionTasksTrackOwnerDueTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "aurevia_care_team_task_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER_CLINICIAN = "clinician-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic members, facilities and patients for two tenants.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            seedOrganization(statement, ORG_A, "UTC");
            seedOrganization(statement, ORG_B, "UTC");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_B, PEER_CLINICIAN, "clinician");
            seedPolicy(statement, ORG_A, "followup.interval.follow", "14");
        }
    }

    /**
     * Drop the fixture database after the class.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     *
     * @param port the running test server port
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedOrganization(Statement statement, String org, String zone) throws Exception {
        String bootstrap = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', '" + zone + "')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'FOLLOW', 15)");
        // V7 requires a civil date or an explicit unknown reason; a synthetic
        // date keeps the append-only patient invariant satisfied for the fixture.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-CARE-TEAM', 'CLINIC', 'Synthetic Care Team Patient', "
                + "'synthetic care team patient', '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        String bootstrap = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPolicy(Statement statement, String org, String key, String value)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    private static String patientId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, subject);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-CARE-TEAM'")) {
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next(), "the synthetic patient row must be seeded");
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .header("If-Match", "1")
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

    /** Open a follow-up suggestion so care-team tasks can be attached to it. */
    private JsonNode suggest(String patient) throws Exception {
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    /** Open a care-team task with a valid owner, due date and instruction. */
    private JsonNode openTask(String suggestionId, String subject, String org) throws Exception {
        ResponseEntity<String> response = post("/api/v1/follow-ups/" + suggestionId + "/tasks",
                "{\"owner\":\"nurse-1\",\"dueDate\":\"2027-03-18\","
                        + "\"instruction\":\"call the patient\"}", subject, org);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    /** Read the persisted task inside the owning tenant, where forced RLS applies. */
    private static CareTeamTaskRow taskRow(String taskId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG_A);
                bind.setString(2, RECEPTION);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT owner, due_date, status, outcome, created_by "
                            + "FROM public.care_team_task "
                            + "WHERE organization_id = CAST(? AS uuid) AND id = CAST(? AS uuid)")) {
                query.setString(1, ORG_A);
                query.setString(2, taskId);
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next(), "the task row must be visible inside the owning tenant");
                    return new CareTeamTaskRow(result.getString("owner"), result.getString("due_date"),
                            result.getString("status"), result.getString("outcome"),
                            result.getString("created_by"));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private record CareTeamTaskRow(String owner, String dueDate, String status, String outcome,
                                   String createdBy) {
    }

    @Test
    void careTeamTaskTracksOwnerDueDateAndOutcome() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String suggestionId = suggest(patient).get("id").asText();

        JsonNode created = openTask(suggestionId, RECEPTION, ORG_A);
        assertEquals(suggestionId, created.get("followUpId").asText());
        assertEquals("nurse-1", created.get("owner").asText());
        assertEquals("2027-03-18", created.get("dueDate").asText());
        assertEquals("call the patient", created.get("instruction").asText());
        assertEquals(RECEPTION, created.get("createdBy").asText());
        assertEquals("OPEN", created.get("status").asText());
        JsonNode openOutcome = created.get("outcome");
        assertTrue(openOutcome == null || openOutcome.isNull(), "an open task has no outcome");
        String taskId = created.get("id").asText();

        JsonNode listed = json(get("/api/v1/follow-ups/" + suggestionId + "/tasks", RECEPTION, ORG_A));
        assertTrue(listed.isArray());
        assertEquals(1, listed.size());
        assertEquals(taskId, listed.get(0).get("id").asText());
        assertEquals("nurse-1", listed.get(0).get("owner").asText());
        assertEquals("2027-03-18", listed.get(0).get("dueDate").asText());

        // A clinician closes the task; the original owner and due date are kept.
        ResponseEntity<String> completion = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"patient confirmed by phone\"}", CLINICIAN, ORG_A);
        assertEquals(200, completion.getStatusCode().value(), completion.getBody());
        JsonNode completed = json(completion);
        assertEquals(taskId, completed.get("id").asText());
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals("patient confirmed by phone", completed.get("outcome").asText());
        assertEquals("nurse-1", completed.get("owner").asText());
        assertEquals("2027-03-18", completed.get("dueDate").asText());
        assertEquals(RECEPTION, completed.get("createdBy").asText());

        JsonNode after = json(get("/api/v1/follow-ups/" + suggestionId + "/tasks", RECEPTION, ORG_A));
        assertEquals(1, after.size());
        assertEquals("COMPLETED", after.get(0).get("status").asText());
        assertEquals("patient confirmed by phone", after.get(0).get("outcome").asText());

        CareTeamTaskRow row = taskRow(taskId);
        assertEquals("nurse-1", row.owner());
        assertEquals("2027-03-18", row.dueDate());
        assertEquals("COMPLETED", row.status());
        assertEquals("patient confirmed by phone", row.outcome());
        assertEquals(RECEPTION, row.createdBy());
    }

    @Test
    void receptionCannotRecordTaskOutcome() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String suggestionId = suggest(patient).get("id").asText();
        String taskId = openTask(suggestionId, RECEPTION, ORG_A).get("id").asText();

        // The outcome is well formed; only the role is wrong, so the refusal
        // comes from the completion policy and not from input validation.
        ResponseEntity<String> denied = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"clerical note\"}", RECEPTION, ORG_A);
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());

        CareTeamTaskRow row = taskRow(taskId);
        assertEquals("OPEN", row.status());
        assertNull(row.outcome());
    }

    @Test
    void clinicianFromPeerTenantCannotReadOrCompleteTask() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String suggestionId = suggest(patient).get("id").asText();
        String taskId = openTask(suggestionId, RECEPTION, ORG_A).get("id").asText();

        // A clinician has the read and complete actions, so authorization passes;
        // the tenant-scoped lookup must still conceal another tenant's task.
        assertEquals(404, get("/api/v1/follow-ups/" + suggestionId + "/tasks",
                PEER_CLINICIAN, ORG_B).getStatusCode().value());
        ResponseEntity<String> denied = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"peer note\"}", PEER_CLINICIAN, ORG_B);
        assertEquals(404, denied.getStatusCode().value(), denied.getBody());

        CareTeamTaskRow row = taskRow(taskId);
        assertEquals("OPEN", row.status());
        assertNull(row.outcome());
    }

    @Test
    void completedTaskCannotBeCompletedAgain() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String suggestionId = suggest(patient).get("id").asText();
        String taskId = openTask(suggestionId, RECEPTION, ORG_A).get("id").asText();

        ResponseEntity<String> first = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"patient confirmed by phone\"}", CLINICIAN, ORG_A);
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        // The second outcome is well formed; only the closed state is forbidden.
        ResponseEntity<String> replay = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"second note\"}", CLINICIAN, ORG_A);
        assertEquals(409, replay.getStatusCode().value(), replay.getBody());

        CareTeamTaskRow row = taskRow(taskId);
        assertEquals("COMPLETED", row.status());
        assertEquals("patient confirmed by phone", row.outcome());
    }

    @Test
    void blankOutcomeIsRefused() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String suggestionId = suggest(patient).get("id").asText();
        String taskId = openTask(suggestionId, RECEPTION, ORG_A).get("id").asText();

        ResponseEntity<String> denied = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"   \"}", CLINICIAN, ORG_A);
        assertEquals(422, denied.getStatusCode().value(), denied.getBody());
        assertNotNull(denied.getBody());
        assertTrue(denied.getBody().contains("INVALID_OUTCOME"), denied.getBody());

        JsonNode stillOpen = json(get("/api/v1/follow-ups/" + suggestionId + "/tasks", RECEPTION, ORG_A));
        assertEquals(1, stillOpen.size());
        assertEquals("OPEN", stillOpen.get(0).get("status").asText());
        JsonNode outcome = stillOpen.get(0).get("outcome");
        assertTrue(outcome == null || outcome.isNull(), "a refused outcome must not close the task");

        CareTeamTaskRow row = taskRow(taskId);
        assertEquals("OPEN", row.status());
        assertNull(row.outcome());
    }
}
