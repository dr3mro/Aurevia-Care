package com.aurevia.scheduling.followup.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * CLN-06 HTTP acceptance on real PostgreSQL: approved visit-type intervals and
 * prior follow-up explain a suggested civil date across a daylight-saving change;
 * an authorized override keeps provenance; only explicit confirmation books, and
 * the store and its tenant isolation survive the request boundary.
 *
 * <p>All fixtures are synthetic and use two organizations that share local
 * facility, resource, visit-type and MRN codes.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_followup_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class FollowUpHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "aurevia_followup_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER = "reception-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            seedOrganization(statement, ORG_A, "America/New_York");
            seedOrganization(statement, ORG_B, "UTC");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', 1, 'MRN-2', 'CLINIC', 'Another Synthetic Patient', "
                    + "'another synthetic patient', '1985-01-10')");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_A, "admin-1111", "tenant_admin");
            seedMember(statement, ORG_B, PEER, "reception");
            seedPolicy(statement, ORG_A, "followup.interval.new", "1");
            seedPolicy(statement, ORG_A, "followup.interval.follow", "14");
            seedPolicy(statement, ORG_B, "followup.interval.follow", "30");
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

    private static void seedOrganization(Statement statement, String org, String zone) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', '" + zone + "')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'NEW', 30), ('" + org + "', 'FOLLOW', 15)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic doctor' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'room', 'ROOM-1', 'Synthetic room' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

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
        return patientId(org, subject, "MRN-1");
    }

    private static String patientId(String org, String subject, String mrn) throws Exception {
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
                    "SELECT id::text FROM public.patient_record WHERE mrn = ?")) {
                query.setString(1, mrn);
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? result.getString(1) : UUID.randomUUID().toString();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                         String ifMatch) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                // Only confirm reads this header; a harmless value on other
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

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private static long appointmentCount(String org, String patient) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.appointment WHERE patient_id = CAST(? AS uuid)")) {
                query.setString(1, patient);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void suggestUsesConfiguredIntervalAcrossDaylightSavingAndBooksNothing() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        // 04:30Z on 8 March 2026 is 23:30 EST on 7 March in New York. Adding one
        // civil day yields 8 March; adding 24 hours would yield 9 March because
        // the intervening night has only 23 hours.
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"NEW",
                 "encounterAt":"2026-03-08T04:30:00Z"}
                """.formatted(patient);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A, null);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode suggestion = json(response);
        assertEquals("2026-03-08", suggestion.get("suggestedDate").asText());
        assertEquals(1, suggestion.get("intervalDays").asInt());
        assertEquals("CONFIGURED_INTERVAL", suggestion.get("basis").asText());
        assertTrue(suggestion.get("ruleExplanation").asText().contains("1 days"));
        assertFalse(suggestion.get("confirmed").asBoolean());
        assertTrue(suggestion.get("bookingId").isNull());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void overrideKeepsProvenanceAndOnlyExplicitConfirmationBooks() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        JsonNode suggestion = json(post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A, null));
        assertEquals("2027-03-20", suggestion.get("suggestedDate").asText());
        String id = suggestion.get("id").asText();

        ResponseEntity<String> noReason = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\" \"}", RECEPTION, ORG_A, null);
        assertEquals(422, noReason.getStatusCode().value());
        assertEquals(before, appointmentCount(ORG_A, patient));

        JsonNode overridden = json(post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"clinician directed\"}",
                RECEPTION, ORG_A, null));
        assertEquals("2027-03-25", overridden.get("suggestedDate").asText());
        assertEquals(2, overridden.get("version").asInt());
        assertEquals(RECEPTION, overridden.get("override").get("actor").asText());
        assertEquals("clinician directed", overridden.get("override").get("reason").asText());
        assertEquals("2027-03-20", overridden.get("override").get("originalDate").asText());
        assertEquals("2027-03-25", overridden.get("override").get("replacementDate").asText());
        assertTrue(overridden.get("ruleExplanation").asText().contains(RECEPTION));
        assertEquals(before, appointmentCount(ORG_A, patient));

        assertEquals(412, post("/api/v1/follow-ups/" + id + "/confirm", "{}", RECEPTION, ORG_A, "1")
                .getStatusCode().value());
        JsonNode confirmed = json(post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "\"2\""));
        assertTrue(confirmed.get("confirmed").asBoolean());
        assertNotNull(confirmed.get("bookingId").asText());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));
        assertEquals(409, post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "3").getStatusCode().value());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));
    }

    @Test
    void priorFollowUpTakesPrecedenceOverTheConfiguredInterval() throws Exception {
        String patient = patientId(ORG_A, RECEPTION, "MRN-2");
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            statement.execute("""
                    INSERT INTO public.appointment
                        (organization_id, id, version, patient_id, facility_id,
                         clinician_resource_id, visit_type, starts_at, ends_at, state)
                    SELECT '11111111-1111-1111-1111-111111111111', gen_random_uuid(), 1,
                           CAST('%s' AS uuid), f.id, r.id, 'FOLLOW',
                           TIMESTAMPTZ '2027-02-20T14:00:00Z',
                           TIMESTAMPTZ '2027-02-20T14:15:00Z', 'booked'
                      FROM public.facility f
                      JOIN public.scheduling_resource r
                        ON r.organization_id = f.organization_id AND r.code = 'DR-A'
                     WHERE f.code = 'MAIN'
                    """.formatted(patient));
        }
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        JsonNode suggestion = json(post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A, null));
        assertEquals("PRIOR_FOLLOW_UP", suggestion.get("basis").asText());
        assertEquals("2027-02-20", suggestion.get("suggestedDate").asText());
        assertTrue(suggestion.get("intervalDays").isNull());
        assertTrue(suggestion.get("ruleExplanation").asText().contains("takes precedence"));
    }

    @Test
    void careTeamTasksCarryOwnerDueDateAndOutcomeOverHttp() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        JsonNode suggestion = json(post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A, null));
        String id = suggestion.get("id").asText();

        JsonNode created = json(post("/api/v1/follow-ups/" + id + "/tasks",
                "{\"owner\":\"nurse-1\",\"dueDate\":\"2027-03-18\",\"instruction\":\"call the patient\"}",
                RECEPTION, ORG_A, null));
        assertEquals("OPEN", created.get("status").asText());
        assertEquals("nurse-1", created.get("owner").asText());
        assertEquals("2027-03-18", created.get("dueDate").asText());
        assertEquals(RECEPTION, created.get("createdBy").asText());
        String taskId = created.get("id").asText();

        JsonNode listed = json(get("/api/v1/follow-ups/" + id + "/tasks", RECEPTION, ORG_A));
        assertTrue(listed.isArray());
        assertEquals(1, listed.size());
        assertEquals(taskId, listed.get(0).get("id").asText());

        // Completion is a clinical action: reception may open the task but a
        // clinician must record its outcome.
        JsonNode completed = json(post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"patient confirmed by phone\"}", CLINICIAN, ORG_A, null));
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals("patient confirmed by phone", completed.get("outcome").asText());
        assertEquals(409, post("/api/v1/follow-ups/tasks/" + taskId + "/complete",
                "{\"outcome\":\"again\"}", CLINICIAN, ORG_A, null).getStatusCode().value());
    }

    @Test
    void peerTenantCannotReadAnotherTenantsSuggestion() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        JsonNode suggestion = json(post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A, null));
        String id = suggestion.get("id").asText();
        assertEquals(404, get("/api/v1/follow-ups/" + id + "/tasks", PEER, ORG_B).getStatusCode().value());
        assertEquals(404, post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"peer\"}", PEER, ORG_B, null)
                .getStatusCode().value());
    }

    @Test
    void clinicianCannotConfirmBecauseBookingIsNotTheirAction() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        JsonNode suggestion = json(post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A, null));
        String id = suggestion.get("id").asText();
        assertEquals(403, post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                CLINICIAN, ORG_A, "1").getStatusCode().value());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }
}
