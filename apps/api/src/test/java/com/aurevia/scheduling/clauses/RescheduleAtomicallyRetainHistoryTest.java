package com.aurevia.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * CLN-01 clause 3 HTTP acceptance on real PostgreSQL: one reschedule moves the
 * slot, records the prior slot and appends exactly one immutable history event
 * in the same commit. A stale version, an occupied target slot, a terminal
 * appointment, another tenant and a role without reschedule permission are
 * refused without leaving a partial slot change or a partial history event.
 *
 * <p>Every fixture is synthetic and the peer organization uses the same
 * tenant-local codes, so isolation is tested at the tenant boundary rather
 * than by giving the peer different names.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_reschedule_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RescheduleAtomicallyRetainHistoryTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "orevia_reschedule_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String PEER = "reception-2222";
    private static final String CLINICIAN = "clinician-1111";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Distinct Sundays per test: a shared database must never let one test's
    // committed appointment occupy another test's protected slot. 15:00Z is
    // 10:00 EST / 11:00 EDT, inside the seeded 09:00-12:00 local window.
    private static final String MOVE_ORIGINAL = "2027-03-07T15:00:00Z";
    private static final String MOVE_TARGET = "2027-03-14T15:00:00Z";
    private static final String STALE_ORIGINAL = "2027-03-21T15:00:00Z";
    private static final String CONFLICT_ORIGINAL = "2027-03-28T15:00:00Z";
    private static final String CONFLICT_TARGET = "2027-04-04T15:00:00Z";
    private static final String TERMINAL_ORIGINAL = "2027-04-11T15:00:00Z";
    private static final String TENANT_ORIGINAL = "2027-04-18T15:00:00Z";
    private static final String ROLE_ORIGINAL = "2027-04-25T15:00:00Z";
    private static final String HOLD_ORIGINAL = "2027-05-02T15:00:00Z";
    private static final String HOLD_TARGET = "2027-05-09T15:00:00Z";

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
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_B, PEER, "reception");
            // A clinician may start and complete a visit but may not reschedule it.
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
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
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-B', 'Second synthetic doctor' "
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
            bindScope(connection, org, subject);
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

    private static void bindScope(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement bind = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            bind.setString(1, org);
            bind.setString(2, subject);
            bind.execute();
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
        return "{\"patientId\":\"" + patient + "\",\"facilityCode\":\"MAIN\","
                + "\"visitType\":\"NEW\",\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\","
                + "\"startsAt\":\"" + start + "\",\"overrideReason\":null}";
    }

    private static String rescheduleBody(String start, String reason) {
        return "{\"startsAt\":\"" + start + "\",\"clinicianCode\":null,\"roomCode\":null,"
                + "\"overrideReason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    private String book(String patient, String start) throws Exception {
        ResponseEntity<String> response = post("/api/v1/appointments", bookingBody(patient, start),
                RECEPTION, ORG_A, null);
        assertEquals(201, response.getStatusCode().value(), response.getBody());
        return json(response).get("id").asText();
    }

    private static void seedBusySlot(String start, String end) throws Exception {
        seedBusySlotOnClinician(start, end, "DR-A");
    }

    private static void seedBusySlotOnClinician(String start, String end, String clinician) throws Exception {
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
                    + "WHERE p.mrn = 'MRN-1' AND f.code = 'MAIN' AND r.code = '" + clinician
                    + "' AND rm.code = 'ROOM-1'");
        }
    }

    /** Seed a lapsed clinician-only hold that the reschedule would release before it fails. */
    private static String seedStaleHold(String start, String end) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bindScope(connection, ORG_A, RECEPTION);
            String holdId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO public.appointment "
                            + "(organization_id, patient_id, facility_id, clinician_resource_id, "
                            + "visit_type, starts_at, ends_at, state, expires_at) "
                            + "SELECT '" + ORG_A + "', p.id, f.id, r.id, 'NEW', CAST(? AS timestamptz), CAST(? AS timestamptz), "
                            + "'held', TIMESTAMPTZ '2020-01-01T00:00:00Z' "
                            + "FROM public.patient_record p CROSS JOIN public.facility f "
                            + "CROSS JOIN public.scheduling_resource r "
                            + "WHERE p.mrn = 'MRN-1' AND f.code = 'MAIN' AND r.code = 'DR-A' "
                            + "RETURNING id::text")) {
                insert.setString(1, start);
                insert.setString(2, end);
                try (ResultSet result = insert.executeQuery()) {
                    result.next();
                    holdId = result.getString(1);
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO public.appointment_event "
                        + "(organization_id, appointment_id, appointment_version, action, from_state, to_state) "
                        + "VALUES ('" + ORG_A + "', CAST('" + holdId + "' AS uuid), 1, 'held', NULL, 'held')");
            }
            connection.commit();
            return holdId;
        }
    }

    private static boolean holdStillLapsedFree(String holdId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bindScope(connection, ORG_A, RECEPTION);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.appointment WHERE id = CAST(? AS uuid) "
                            + "AND state = 'held' AND version = 1")) {
                query.setString(1, holdId);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1) == 1;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static int expiredEventCount(String appointmentId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bindScope(connection, ORG_A, RECEPTION);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.appointment_event "
                            + "WHERE appointment_id = CAST(? AS uuid) AND action = 'expired'")) {
                query.setString(1, appointmentId);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static int eventCount(String appointmentId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bindScope(connection, ORG_A, RECEPTION);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.appointment_event WHERE appointment_id = CAST(? AS uuid)")) {
                query.setString(1, appointmentId);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static boolean storedSlotIs(String appointmentId, String start) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bindScope(connection, ORG_A, RECEPTION);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.appointment WHERE id = CAST(? AS uuid) "
                            + "AND starts_at = CAST(? AS timestamptz)")) {
                query.setString(1, appointmentId);
                query.setString(2, start);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1) == 1;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void rescheduleMovesTheSlotAndRetainsHistoryInOneCommit() throws Exception {
        String id = book(patientId(ORG_A, RECEPTION), MOVE_ORIGINAL);

        ResponseEntity<String> moved = post("/api/v1/appointments/" + id + "/reschedule",
                rescheduleBody(MOVE_TARGET, "clinic relocated"), RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, moved.getStatusCode().value(), moved.getBody());
        assertEquals(MOVE_TARGET, json(moved).get("startsAt").asText());
        assertEquals(2, json(moved).get("version").asInt());

        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals(MOVE_TARGET, detail.get("appointment").get("startsAt").asText());
        JsonNode history = detail.get("history");
        assertEquals(2, history.size());
        assertEquals("booked", history.get(0).get("action").asText());
        assertEquals("rescheduled", history.get(1).get("action").asText());
        assertEquals(2, history.get(1).get("appointmentVersion").asInt());
        assertEquals(MOVE_ORIGINAL, history.get(1).get("previousStartsAt").asText());
        assertEquals("2027-03-07T15:30:00Z", history.get(1).get("previousEndsAt").asText());
        assertEquals("clinic relocated", history.get(1).get("reason").asText());
        assertFalse(history.get(1).get("actorId").asText().isBlank());

        // The slot change and the history event are the same durable commit.
        assertTrueSlotIs(id, MOVE_TARGET);
        assertEquals(2, eventCount(id));
    }

    @Test
    void staleVersionRefusesTheMoveAndWritesNoHistory() throws Exception {
        String id = book(patientId(ORG_A, RECEPTION), STALE_ORIGINAL);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/reschedule",
                rescheduleBody(MOVE_TARGET, null), RECEPTION, ORG_A, "\"9\"");
        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());

        assertTrueSlotIs(id, STALE_ORIGINAL);
        assertEquals(1, eventCount(id));
    }

    @Test
    void occupiedTargetSlotRefusesTheMoveAndWritesNoHistory() throws Exception {
        String id = book(patientId(ORG_A, RECEPTION), CONFLICT_ORIGINAL);
        seedBusySlot(CONFLICT_TARGET, "2027-04-04T15:30:00Z");

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/reschedule",
                rescheduleBody(CONFLICT_TARGET, null), RECEPTION, ORG_A, "\"1\"");
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("SLOT_CONFLICT", json(refused).get("code").asText());

        assertTrueSlotIs(id, CONFLICT_ORIGINAL);
        assertEquals(1, eventCount(id));
    }

    @Test
    void failedRescheduleRollsBackTheStaleHoldReleaseToo() throws Exception {
        String id = book(patientId(ORG_A, RECEPTION), HOLD_ORIGINAL);
        // The target is taken on another clinician, while a lapsed clinician-only
        // hold on this appointment's clinician overlaps the same target slot. The
        // reschedule releases that hold first, then fails the slot update.
        seedBusySlotOnClinician(HOLD_TARGET, "2027-05-09T15:30:00Z", "DR-B");
        String holdId = seedStaleHold(HOLD_TARGET, "2027-05-09T15:30:00Z");

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/reschedule",
                rescheduleBody(HOLD_TARGET, null), RECEPTION, ORG_A, "\"1\"");
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("SLOT_CONFLICT", json(refused).get("code").asText());

        assertTrueSlotIs(id, HOLD_ORIGINAL);
        assertEquals(1, eventCount(id));
        assertTrue(holdStillLapsedFree(holdId), "the stale hold release must roll back");
        assertEquals(0, expiredEventCount(holdId), "the expired history event must roll back");
    }

    @Test
    void terminalAppointmentRefusesTheMoveAndKeepsItsHistory() throws Exception {
        String id = book(patientId(ORG_A, RECEPTION), TERMINAL_ORIGINAL);
        ResponseEntity<String> cancelled = post("/api/v1/appointments/" + id + "/cancel",
                "{\"reason\":\"duplicate booking\"}", RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, cancelled.getStatusCode().value(), cancelled.getBody());

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/reschedule",
                rescheduleBody(MOVE_TARGET, null), RECEPTION, ORG_A, "\"2\"");
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TRANSITION", json(refused).get("code").asText());

        assertTrueSlotIs(id, TERMINAL_ORIGINAL);
        assertEquals(2, eventCount(id));
        JsonNode history = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A)).get("history");
        assertEquals(2, history.size());
        assertEquals("cancelled", history.get(1).get("action").asText());
    }

    @Test
    void peerTenantCannotRescheduleAnotherTenantsAppointment() throws Exception {
        String id = book(patientId(ORG_A, RECEPTION), TENANT_ORIGINAL);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/reschedule",
                rescheduleBody(MOVE_TARGET, null), PEER, ORG_B, "\"1\"");
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());

        assertTrueSlotIs(id, TENANT_ORIGINAL);
        assertEquals(1, eventCount(id));
    }

    @Test
    void roleWithoutReschedulePermissionCannotMoveAnAppointment() throws Exception {
        String id = book(patientId(ORG_A, RECEPTION), ROLE_ORIGINAL);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/reschedule",
                rescheduleBody(MOVE_TARGET, null), CLINICIAN, ORG_A, "\"1\"");
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());

        assertTrueSlotIs(id, ROLE_ORIGINAL);
        assertEquals(1, eventCount(id));
    }

    private static void assertTrueSlotIs(String appointmentId, String start) throws Exception {
        assertTrue(storedSlotIs(appointmentId, start),
                "appointment " + appointmentId + " should still start at " + start);
    }
}
