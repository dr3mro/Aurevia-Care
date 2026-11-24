package com.aurevia.scheduling.it;

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
 * CLN-01 HTTP acceptance on real PostgreSQL: a weekly series keeps the facility
 * local clock time across a daylight-saving change, a clash on the third
 * occurrence books nothing, two competing series share one slot, and editing an
 * occurrence keeps its original plan and history. Fixtures are synthetic and
 * the peer tenant shares the same local codes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_series_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SeriesHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "orevia_series_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String PEER = "reception-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Each test owns a distinct set of Sundays so a shared database never makes
    // one test's committed series clash with another's protected resources.
    private static final String FIRST = "2027-03-07T14:00:00Z";
    private static final String SECOND = "2027-03-14T13:00:00Z";
    private static final String THIRD = "2027-03-21T13:00:00Z";
    private static final String CLASH_FIRST = "2027-04-04T13:00:00Z";
    private static final String CLASH_THIRD = "2027-04-18T13:00:00Z";
    private static final String COMPETE_FIRST = "2027-05-02T13:00:00Z";
    private static final String END_FIRST = "2027-06-06T13:00:00Z";
    private static final String END_MOVED = "2027-06-20T13:00:00Z";
    private static final String PEER_FIRST = "2027-07-04T13:00:00Z";

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
            seedOrganization(statement, ORG_A, "America/New_York");
            seedOrganization(statement, ORG_B, "America/New_York");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            seedMember(statement, ORG_A, RECEPTION, "reception");
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
        String prefix = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + prefix + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + prefix + "', 'Bootstrap')");
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
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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

    private static String seriesBody(String patient, String start, int weeks, Integer count,
                                     String endDate) {
        StringBuilder body = new StringBuilder("{\"patientId\":\"").append(patient)
                .append("\",\"facilityCode\":\"MAIN\",\"visitType\":\"NEW\",")
                .append("\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\",")
                .append("\"startsAt\":\"").append(start).append("\",\"overrideReason\":null,")
                .append("\"repeatWeeks\":").append(weeks).append(",\"occurrences\":")
                .append(count == null ? "null" : count.toString());
        if (endDate != null) {
            body.append(",\"endDate\":\"").append(endDate).append("\"");
        }
        return body.append("}").toString();
    }

    private static long appointmentCount(String org, String patient) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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

    private static void seedBusySlot(String start, String end) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = '" + RECEPTION + "'");
            statement.execute("INSERT INTO public.appointment "
                    + "(organization_id, patient_id, facility_id, clinician_resource_id, "
                    + "room_resource_id, visit_type, starts_at, ends_at, state) "
                    + "SELECT '" + ORG_A + "', p.id, f.id, r.id, rm.id, 'NEW', "
                    + "TIMESTAMPTZ '" + start + "', TIMESTAMPTZ '" + end + "', 'booked' "
                    + "FROM public.patient_record p CROSS JOIN public.facility f "
                    + "CROSS JOIN public.scheduling_resource r "
                    + "CROSS JOIN public.scheduling_resource rm "
                    + "WHERE p.mrn = 'MRN-1' AND f.code = 'MAIN' AND r.code = 'DR-A' AND rm.code = 'ROOM-1'");
        }
    }

    @Test
    void weeklySeriesKeepsTheFacilityLocalClockTimeAcrossDaylightSaving() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        ResponseEntity<String> response = post("/api/v1/appointments/series",
                seriesBody(patient, FIRST, 1, 3, null), RECEPTION, ORG_A, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        JsonNode series = json(response);
        assertEquals(3, series.size());
        assertEquals(FIRST, series.get(0).get("startsAt").asText());
        assertEquals(SECOND, series.get(1).get("startsAt").asText());
        assertEquals(THIRD, series.get(2).get("startsAt").asText());
        String firstId = series.get(0).get("id").asText();
        assertNotNull(series.get(0).get("seriesId").asText());
        assertEquals(series.get(0).get("seriesId").asText(), series.get(2).get("seriesId").asText());

        JsonNode detail = json(get("/api/v1/appointments/" + firstId, RECEPTION, ORG_A));
        assertEquals(1, detail.get("history").size());
        assertEquals("booked", detail.get("history").get(0).get("action").asText());
    }

    @Test
    void aConflictOnTheThirdOccurrenceBooksNothing() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        seedBusySlot(CLASH_THIRD, "2027-04-18T13:30:00Z");
        long before = appointmentCount(ORG_A, patient);
        ResponseEntity<String> response = post("/api/v1/appointments/series",
                seriesBody(patient, CLASH_FIRST, 1, 3, null), RECEPTION, ORG_A, null);
        assertEquals(409, response.getStatusCode().value(), response.getBody());
        JsonNode failure = json(response);
        assertEquals("SERIES_CONFLICT", failure.get("code").asText());
        assertTrue(failure.get("message").asText().contains("occurrence 3"));
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void twoCompetingSeriesShareOneSlot() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        ResponseEntity<String> winner = post("/api/v1/appointments/series",
                seriesBody(patient, COMPETE_FIRST, 1, 3, null), RECEPTION, ORG_A, null);
        assertEquals(201, winner.getStatusCode().value(), winner.getBody());
        assertEquals(3, json(winner).size());

        // 13:15Z is 09:15 in New York in May, so the half-hour slots overlap.
        ResponseEntity<String> competitor = post("/api/v1/appointments/series",
                seriesBody(patient, "2027-05-02T13:15:00Z", 1, 1, null), RECEPTION, ORG_A, null);
        assertEquals(409, competitor.getStatusCode().value(), competitor.getBody());
        assertEquals("SERIES_CONFLICT", json(competitor).get("code").asText());
        assertEquals(before + 3, appointmentCount(ORG_A, patient));
    }

    @Test
    void anEndDatedSeriesIsDurableAndEditingOneOccurrenceKeepsItsHistory() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        ResponseEntity<String> response = post("/api/v1/appointments/series",
                seriesBody(patient, END_FIRST, 1, null, "2027-06-13"), RECEPTION, ORG_A, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        assertEquals(2, json(response).size());
        assertEquals(before + 2, appointmentCount(ORG_A, patient));
        String firstId = json(response).get(0).get("id").asText();

        ResponseEntity<String> moved = post("/api/v1/appointments/" + firstId + "/reschedule",
                "{\"startsAt\":\"" + END_MOVED + "\",\"clinicianCode\":null,"
                        + "\"roomCode\":null,\"overrideReason\":null}",
                RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, moved.getStatusCode().value(), moved.getBody());
        assertEquals(END_MOVED, json(moved).get("startsAt").asText());

        JsonNode detail = json(get("/api/v1/appointments/" + firstId, RECEPTION, ORG_A));
        JsonNode history = detail.get("history");
        assertEquals(2, history.size());
        assertEquals("rescheduled", history.get(1).get("action").asText());
        assertEquals(END_FIRST, history.get(1).get("previousStartsAt").asText());

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, ORG_A);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.series_occurrence "
                            + "WHERE appointment_id = CAST(? AS uuid) AND planned_starts_at = TIMESTAMPTZ '"
                            + END_FIRST + "'")) {
                query.setString(1, firstId);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    assertEquals(1, result.getInt(1));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void peerTenantCannotSeeAnotherTenantsSeries() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        ResponseEntity<String> response = post("/api/v1/appointments/series",
                seriesBody(patient, PEER_FIRST, 1, 1, null), RECEPTION, ORG_A, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        String firstId = json(response).get(0).get("id").asText();
        assertEquals(404, get("/api/v1/appointments/" + firstId, PEER, ORG_B).getStatusCode().value());
        String peerPatient = patientId(ORG_B, PEER);
        ResponseEntity<String> peerBook = post("/api/v1/appointments/series",
                seriesBody(peerPatient, PEER_FIRST, 1, 1, null), PEER, ORG_B, null);
        assertEquals(201, peerBook.getStatusCode().value(), peerBook.getBody());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));
    }
}
