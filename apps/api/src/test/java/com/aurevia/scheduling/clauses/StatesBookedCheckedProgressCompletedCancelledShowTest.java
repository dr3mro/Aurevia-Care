package com.aurevia.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * CLN-01 clause 4 acceptance through the real HTTP entry point on PostgreSQL:
 * an appointment moves booked → checked in → in progress → completed, and the
 * cancelled and no-show branches end the same lifecycle. Negative cases prove
 * the rule refuses a skipped state, a stale precondition, a wrong role, a
 * missing required reason and a cross-tenant transition. Fixtures are synthetic
 * and the peer tenant shares the same local codes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_states_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class StatesBookedCheckedProgressCompletedCancelledShowTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "aurevia_states_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER = "reception-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Each test owns a distinct Sunday so one test's committed slot never
    // clashes with another's protected clinician or room.
    private static final String HAPPY = "2027-03-07T14:00:00Z";
    private static final String CANCEL = "2027-03-14T14:00:00Z";
    private static final String NO_SHOW = "2027-03-21T14:00:00Z";
    private static final String SKIP = "2027-03-28T14:00:00Z";
    private static final String STALE = "2027-04-04T14:00:00Z";
    private static final String WRONG_ROLE = "2027-04-11T14:00:00Z";
    private static final String NO_REASON = "2027-04-18T14:00:00Z";
    private static final String PEER_SLOT = "2027-04-25T14:00:00Z";
    private static final String DURABLE = "2027-05-02T14:00:00Z";

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
            seedOrganization(statement, ORG_B, "America/New_York");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_B, PEER, "reception");
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
                + "VALUES ('" + org + "', 'NEW', 30)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic doctor' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'room', 'ROOM-1', 'Synthetic room' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.resource_availability "
                + "(organization_id, resource_id, weekday, start_local, end_local) "
                + "SELECT '" + org + "', id, 7, '09:00', '12:00' "
                + "FROM public.scheduling_resource WHERE code IN ('DR-A', 'ROOM-1')");
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
                    "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
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

    private static String bookingBody(String patient, String start) {
        return "{\"patientId\":\"" + patient + "\",\"facilityCode\":\"MAIN\",\"visitType\":\"NEW\","
                + "\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\","
                + "\"startsAt\":\"" + start + "\",\"overrideReason\":null}";
    }

    private ResponseEntity<String> book(String start, String subject, String org) throws Exception {
        String patient = patientId(org, subject);
        ResponseEntity<String> response = post("/api/v1/appointments", bookingBody(patient, start),
                subject, org, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return response;
    }

    @Test
    void bookedMovesThroughCheckedInAndInProgressToCompleted() throws Exception {
        ResponseEntity<String> created = book(HAPPY, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();
        assertEquals("booked", json(created).get("state").asText());

        ResponseEntity<String> checkedIn = post("/api/v1/appointments/" + id + "/check-in", "{}",
                RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, checkedIn.getStatusCode().value(), checkedIn.getBody());
        assertEquals("checked_in", json(checkedIn).get("state").asText());

        ResponseEntity<String> started = post("/api/v1/appointments/" + id + "/start", "{}",
                CLINICIAN, ORG_A, "\"2\"");
        assertEquals(200, started.getStatusCode().value(), started.getBody());
        assertEquals("in_progress", json(started).get("state").asText());

        ResponseEntity<String> completed = post("/api/v1/appointments/" + id + "/complete", "{}",
                CLINICIAN, ORG_A, "\"3\"");
        assertEquals(200, completed.getStatusCode().value(), completed.getBody());
        assertEquals("completed", json(completed).get("state").asText());

        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("completed", detail.get("appointment").get("state").asText());
        JsonNode history = detail.get("history");
        assertEquals(4, history.size());
        assertEquals("booked", history.get(0).get("action").asText());
        assertEquals("checked_in", history.get(1).get("action").asText());
        assertEquals("started", history.get(2).get("action").asText());
        assertEquals("completed", history.get(3).get("action").asText());
    }

    @Test
    void cancellingRecordsAReasonAndEndsTheLifecycle() throws Exception {
        ResponseEntity<String> created = book(CANCEL, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();

        ResponseEntity<String> cancelled = post("/api/v1/appointments/" + id + "/cancel",
                "{\"reason\":\"patient request\"}", RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, cancelled.getStatusCode().value(), cancelled.getBody());
        assertEquals("cancelled", json(cancelled).get("state").asText());
        assertEquals("patient request",
                json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A))
                        .get("history").get(1).get("reason").asText());
        assertEquals(409, post("/api/v1/appointments/" + id + "/check-in", "{}",
                RECEPTION, ORG_A, "\"2\"").getStatusCode().value());
    }

    @Test
    void aNoShowRecordsAReasonAndEndsTheLifecycle() throws Exception {
        ResponseEntity<String> created = book(NO_SHOW, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();

        ResponseEntity<String> noShow = post("/api/v1/appointments/" + id + "/no-show",
                "{\"reason\":\"did not attend\"}", RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, noShow.getStatusCode().value(), noShow.getBody());
        assertEquals("no_show", json(noShow).get("state").asText());
        assertEquals("no_show",
                json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A))
                        .get("history").get(1).get("action").asText());
    }

    @Test
    void aBookedAppointmentCannotSkipStraightToCompleted() throws Exception {
        ResponseEntity<String> created = book(SKIP, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/complete", "{}",
                CLINICIAN, ORG_A, "\"1\"");
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());
        assertEquals("booked", json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A))
                .get("appointment").get("state").asText());
    }

    @Test
    void aStaleVersionCannotAdvanceTheState() throws Exception {
        ResponseEntity<String> created = book(STALE, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/check-in", "{}",
                RECEPTION, ORG_A, "\"99\"");
        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        assertEquals("booked", json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A))
                .get("appointment").get("state").asText());
    }

    @Test
    void receptionCannotStartAnEncounter() throws Exception {
        ResponseEntity<String> created = book(WRONG_ROLE, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/start", "{}",
                RECEPTION, ORG_A, "\"1\"");
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        assertEquals("booked", json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A))
                .get("appointment").get("state").asText());
    }

    @Test
    void cancellingWithoutAReasonIsRejected() throws Exception {
        ResponseEntity<String> created = book(NO_REASON, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/cancel", "{}",
                RECEPTION, ORG_A, "\"1\"");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REASON_REQUIRED", json(refused).get("code").asText());
        assertEquals("booked", json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A))
                .get("appointment").get("state").asText());
    }

    @Test
    void aPeerTenantCannotTransitionAnotherTenantsAppointment() throws Exception {
        ResponseEntity<String> created = book(PEER_SLOT, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/check-in", "{}",
                PEER, ORG_B, "\"1\"");
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        assertEquals("booked", json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A))
                .get("appointment").get("state").asText());
    }

    @Test
    void aReadBackAppointmentKeepsTheFinalStateAfterTheChain() throws Exception {
        // The chain is persisted, not just returned: read it back after the
        // committing request has completed.
        ResponseEntity<String> created = book(DURABLE, RECEPTION, ORG_A);
        String id = json(created).get("id").asText();
        post("/api/v1/appointments/" + id + "/check-in", "{}", RECEPTION, ORG_A, "\"1\"");
        post("/api/v1/appointments/" + id + "/start", "{}", CLINICIAN, ORG_A, "\"2\"");
        post("/api/v1/appointments/" + id + "/complete", "{}", CLINICIAN, ORG_A, "\"3\"");

        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("completed", detail.get("appointment").get("state").asText());
        assertEquals(4, detail.get("history").size());
        assertEquals("completed", detail.get("history").get(3).get("toState").asText());
    }
}
