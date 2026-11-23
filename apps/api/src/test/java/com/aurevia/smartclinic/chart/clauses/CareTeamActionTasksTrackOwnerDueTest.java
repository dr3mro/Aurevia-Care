package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * CLN-06 clause 4 acceptance: a care-team action task keeps its accountable
 * owner and facility-local due date, and records an outcome only when an
 * authorized clinician closes it.
 *
 * <p>The behavior is exercised through the real HTTP entry points of the
 * follow-up domain: a task is opened by reception and listed for its suggestion,
 * and only a clinician may record its outcome. Wrong roles, another tenant,
 * missing or blank fields and a repeated completion must all be refused without
 * changing the stored task. The clause is implemented in {@code scheduling.followup};
 * this chart clause package is only the mandated test home. Each test seeds its
 * own follow-up suggestion with a fresh identifier so tenant-scoped assertions
 * never depend on JUnit execution order. All fixtures are synthetic: no patient
 * data or credential is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln_care_team_task_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CareTeamActionTasksTrackOwnerDueTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_cln_care_team_task_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER = "clinician-2222";
    private static final String MRN_A = "MRN-CLN-TASK-A";
    private static final String MRN_B = "MRN-CLN-TASK-B";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Suggestion identifier seeded for this test in the owning tenant. */
    private String followUpId;

    /** Suggestion identifier seeded for this test in the peer tenant. */
    private String peerFollowUpId;

    @BeforeEach
    void setUp(@LocalServerPort int port) throws Exception {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        followUpId = seedSuggestion(ORG_A, RECEPTION, MRN_A);
        peerFollowUpId = seedSuggestion(ORG_B, PEER, MRN_B);
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
            seedOrganization(statement, ORG_A, "UTC", MRN_A);
            seedOrganization(statement, ORG_B, "UTC", MRN_B);
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
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

    /** Create one tenant with its minimal facility, visit-type and synthetic patient fixtures. */
    private static void seedOrganization(Statement statement, String org, String zone, String mrn)
            throws Exception {
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
                + "VALUES ('" + org + "', 'NEW', 30), ('" + org + "', 'FOLLOW', 15)");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, '" + mrn + "', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    /** Assign one active role to a synthetic member of the given tenant. */
    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /**
     * Seed one durable follow-up suggestion directly from the V74 schema for one
     * test. The rule explanation and interval columns are filled with inert
     * synthetic values because clauses 1-3 are out of scope here.
     *
     * @return the freshly generated suggestion identifier
     */
    private static String seedSuggestion(String org, String actor, String mrn) throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, actor);
                bind.execute();
            }
            try (PreparedStatement lookup = connection.prepareStatement(
                    "SELECT id::text FROM public.patient_record "
                            + "WHERE organization_id = CAST(? AS uuid) AND mrn = ?")) {
                lookup.setString(1, org);
                lookup.setString(2, mrn);
                try (ResultSet result = lookup.executeQuery()) {
                    assertTrue(result.next(), "synthetic patient fixture is required");
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO public.follow_up_suggestion
                                (organization_id, id, patient_id, facility_code, visit_type,
                                 encounter_at, time_zone, suggested_date, basis, rule_explanation, version)
                            VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'MAIN', 'FOLLOW',
                                    TIMESTAMPTZ '2027-03-06T15:00:00Z', 'UTC', DATE '2027-03-20',
                                    'CONFIGURED_INTERVAL', 'synthetic follow-up for task acceptance', 1)
                            """)) {
                        insert.setString(1, org);
                        insert.setString(2, id);
                        insert.setString(3, result.getString(1));
                        insert.executeUpdate();
                    }
                }
            }
            connection.commit();
        }
        return id;
    }

    /** Count only the tasks of one seeded suggestion, under that suggestion's tenant. */
    private static int taskCount(String org, String suggestionId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.care_team_task "
                            + "WHERE organization_id = CAST(? AS uuid) AND follow_up_id = CAST(? AS uuid)")) {
                query.setString(1, org);
                query.setString(2, suggestionId);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** Open a task through the real HTTP entry point and return its identifier. */
    private String openTask() {
        ResponseEntity<String> created = post("/api/v1/follow-ups/" + followUpId + "/tasks",
                "{\"owner\":\"nurse-1\",\"dueDate\":\"2027-03-18\","
                        + "\"instruction\":\"call the patient before the visit\"}",
                RECEPTION, ORG_A, null);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        return json(created).get("id").asText();
    }

    /** Read one specific task back through its HTTP list entry point. */
    private JsonNode listedTask(String suggestionId, String taskId, String subject, String org) {
        ResponseEntity<String> response = get("/api/v1/follow-ups/" + suggestionId + "/tasks", subject, org);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        for (JsonNode task : json(response)) {
            if (taskId.equals(task.get("id").asText())) {
                return task;
            }
        }
        throw new AssertionError("task " + taskId + " was not listed for suggestion " + suggestionId);
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                         String ifMatch) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                // Only confirmation reads this header; a harmless value on other
                // commands keeps the request builder simple.
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

    private static JsonNode json(ResponseEntity<String> response) {
        return MAPPER.readTree(response.getBody());
    }

    @Test
    void createTaskKeepsOwnerAndDueDateAndCompletionRecordsOutcome() throws Exception {
        ResponseEntity<String> createdResponse = post("/api/v1/follow-ups/" + followUpId + "/tasks",
                "{\"owner\":\"nurse-1\",\"dueDate\":\"2027-03-18\","
                        + "\"instruction\":\"call the patient before the visit\"}",
                RECEPTION, ORG_A, null);
        assertEquals(200, createdResponse.getStatusCode().value(), createdResponse.getBody());
        JsonNode created = json(createdResponse);
        assertEquals("OPEN", created.get("status").asText());
        assertEquals("nurse-1", created.get("owner").asText());
        assertEquals("2027-03-18", created.get("dueDate").asText());
        assertEquals("call the patient before the visit", created.get("instruction").asText());
        assertEquals(RECEPTION, created.get("createdBy").asText());
        assertEquals(followUpId, created.get("followUpId").asText());
        String taskId = created.get("id").asText();
        assertNotNull(taskId);

        ResponseEntity<String> listedResponse = get("/api/v1/follow-ups/" + followUpId + "/tasks",
                RECEPTION, ORG_A);
        assertEquals(200, listedResponse.getStatusCode().value(), listedResponse.getBody());
        JsonNode listed = json(listedResponse);
        assertTrue(listed.isArray());
        assertEquals(1, listed.size());
        assertEquals(taskId, listed.get(0).get("id").asText());

        ResponseEntity<String> completedResponse = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"patient confirmed by phone\"}", CLINICIAN, ORG_A, null);
        assertEquals(200, completedResponse.getStatusCode().value(), completedResponse.getBody());
        JsonNode completed = json(completedResponse);
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals("patient confirmed by phone", completed.get("outcome").asText());
        assertEquals("nurse-1", completed.get("owner").asText());
        assertEquals("2027-03-18", completed.get("dueDate").asText());
    }

    @Test
    void clinicianCannotCreateATask() throws Exception {
        ResponseEntity<String> denied = post("/api/v1/follow-ups/" + followUpId + "/tasks",
                "{\"owner\":\"nurse-1\",\"dueDate\":\"2027-03-18\",\"instruction\":\"call\"}",
                CLINICIAN, ORG_A, null);
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("FORBIDDEN", json(denied).get("code").asText());
        assertEquals(0, taskCount(ORG_A, followUpId));
    }

    @Test
    void receptionCannotRecordAnOutcome() throws Exception {
        String taskId = openTask();
        ResponseEntity<String> denied = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"done\"}", RECEPTION, ORG_A, null);
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("FORBIDDEN", json(denied).get("code").asText());

        JsonNode task = listedTask(followUpId, taskId, RECEPTION, ORG_A);
        assertEquals("OPEN", task.get("status").asText());
        assertTrue(task.get("outcome").isNull());
    }

    @Test
    void peerTenantCannotReadOrCompleteAnotherTenantsTask() throws Exception {
        String taskId = openTask();
        assertEquals(1, taskCount(ORG_A, followUpId));

        // The peer has its own suggestion in its own tenant and can read it, so
        // the 404 below is tenant concealment rather than a broken request.
        ResponseEntity<String> own = get("/api/v1/follow-ups/" + peerFollowUpId + "/tasks", PEER, ORG_B);
        assertEquals(200, own.getStatusCode().value(), own.getBody());
        assertTrue(json(own).isEmpty());

        ResponseEntity<String> read = get("/api/v1/follow-ups/" + followUpId + "/tasks", PEER, ORG_B);
        assertEquals(404, read.getStatusCode().value());
        assertEquals("NOT_FOUND", json(read).get("code").asText());

        ResponseEntity<String> completed = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"peer interference\"}", PEER, ORG_B, null);
        assertEquals(404, completed.getStatusCode().value());
        assertEquals("NOT_FOUND", json(completed).get("code").asText());

        JsonNode task = listedTask(followUpId, taskId, RECEPTION, ORG_A);
        assertEquals("OPEN", task.get("status").asText());
        assertEquals("nurse-1", task.get("owner").asText());
        assertEquals("2027-03-18", task.get("dueDate").asText());
        assertTrue(task.get("outcome").isNull());
        assertEquals(1, taskCount(ORG_A, followUpId));
    }

    @Test
    void createTaskRejectsABlankOwnerWithoutWritingARow() throws Exception {
        ResponseEntity<String> rejected = post("/api/v1/follow-ups/" + followUpId + "/tasks",
                "{\"owner\":\" \",\"dueDate\":\"2027-03-18\",\"instruction\":\"call\"}",
                RECEPTION, ORG_A, null);
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals("INVALID_OWNER", json(rejected).get("code").asText());
        assertEquals(0, taskCount(ORG_A, followUpId));
    }

    @Test
    void createTaskRejectsAMissingDueDateWithoutWritingARow() throws Exception {
        ResponseEntity<String> rejected = post("/api/v1/follow-ups/" + followUpId + "/tasks",
                "{\"owner\":\"nurse-1\",\"instruction\":\"call\"}", RECEPTION, ORG_A, null);
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals("INVALID_DUE_DATE", json(rejected).get("code").asText());
        assertEquals(0, taskCount(ORG_A, followUpId));
    }

    @Test
    void completeTaskRejectsABlankOutcomeWithoutClosingTheTask() throws Exception {
        String taskId = openTask();
        ResponseEntity<String> rejected = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\" \"}", CLINICIAN, ORG_A, null);
        assertEquals(422, rejected.getStatusCode().value());
        assertEquals("INVALID_OUTCOME", json(rejected).get("code").asText());

        JsonNode task = listedTask(followUpId, taskId, RECEPTION, ORG_A);
        assertEquals("OPEN", task.get("status").asText());
        assertTrue(task.get("outcome").isNull());
    }

    @Test
    void secondCompletionIsRejectedAndKeepsTheFirstOutcome() throws Exception {
        String taskId = openTask();
        ResponseEntity<String> first = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"patient confirmed by phone\"}", CLINICIAN, ORG_A, null);
        assertEquals(200, first.getStatusCode().value(), first.getBody());

        ResponseEntity<String> second = post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"a different outcome\"}", CLINICIAN, ORG_A, null);
        assertEquals(409, second.getStatusCode().value());
        assertEquals("ALREADY_COMPLETED", json(second).get("code").asText());

        JsonNode task = listedTask(followUpId, taskId, RECEPTION, ORG_A);
        assertEquals("COMPLETED", task.get("status").asText());
        assertEquals("patient confirmed by phone", task.get("outcome").asText());
        assertEquals("nurse-1", task.get("owner").asText());
        assertEquals("2027-03-18", task.get("dueDate").asText());
    }
}
