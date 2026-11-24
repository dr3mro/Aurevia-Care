package com.aurevia.scheduling.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * CLN-01 clause 2 proof on real PostgreSQL: two commands that claim the same
 * protected clinician or room slot cannot both commit. The service takes a
 * deterministic advisory lock inside the tenant-guarded transaction and the V8
 * partial GiST exclusion constraints remain the authority for exclusivity, so a
 * losing command is answered with a {@code 409 SLOT_CONFLICT} and no row is
 * written. Fixtures are synthetic; the peer tenant shares the same local codes.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_overlap_clause_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PreventOverlapProtectedResourcesTransactionallyTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "orevia_overlap_clause_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String PEER = "reception-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Distinct Sundays so one test's committed slot never overlaps another's
    // protected resources in the shared database. NY is UTC-4 in August.
    private static final String COMPETE_START = "2027-08-01T13:00:00Z";
    private static final String CLINICIAN_START = "2027-08-15T14:00:00Z";
    private static final String CLINICIAN_OVERLAP = "2027-08-15T14:15:00Z";
    private static final String ROOM_START = "2027-08-22T14:00:00Z";
    private static final String ROOM_OVERLAP = "2027-08-22T14:15:00Z";

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create an isolated database, apply the production migrations and seed two synthetic tenants. */
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
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_B, PEER, "reception");
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

    private static void seedOrganization(Statement statement, String org) throws Exception {
        String prefix = "bootstrap-" + org.substring(0, 4);
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + prefix + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + prefix + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', 'America/New_York')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'NEW', 30)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic doctor A' "
                + "FROM public.facility WHERE code = 'MAIN'");
        // A second clinician lets the room constraint be isolated from the clinician one.
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-B', 'Synthetic doctor B' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'room', 'ROOM-1', 'Synthetic room' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.resource_availability "
                + "(organization_id, resource_id, weekday, start_local, end_local) "
                + "SELECT '" + org + "', id, 7, '09:00', '12:00' "
                + "FROM public.scheduling_resource WHERE code IN ('DR-A', 'DR-B', 'ROOM-1')");
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

    private ResponseEntity<String> post(String path, String body, String subject, String org) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private static String bookingBody(String patient, String start, String clinician, String room) {
        return "{\"patientId\":\"" + patient + "\",\"facilityCode\":\"MAIN\",\"visitType\":\"NEW\","
                + "\"clinicianCode\":\"" + clinician + "\",\"roomCode\":\"" + room + "\","
                + "\"startsAt\":\"" + start + "\",\"overrideReason\":null}";
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

    private static long appointmentCountAtSlot(String org, String patient, String start) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.appointment WHERE patient_id = CAST(? AS uuid) "
                            + "AND starts_at = TIMESTAMPTZ '" + start + "'")) {
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

    /** Seed one already committed appointment that occupies both protected resources. */
    private static void seedBusySlot(String start, String end, String clinicianCode, String roomCode)
            throws Exception {
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
                    + "WHERE p.mrn = 'MRN-1' AND f.code = 'MAIN' AND r.code = '" + clinicianCode
                    + "' AND rm.code = '" + roomCode + "'");
        }
    }

    @Test
    void twoConcurrentBookingsOfOneProtectedSlotLeaveExactlyOneCommittedAppointment() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String body = bookingBody(patient, COMPETE_START, "DR-A", "ROOM-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startTogether = new CountDownLatch(1);
        try {
            Callable<Integer> attempt = () -> {
                startTogether.await();
                return post("/api/v1/appointments", body, RECEPTION, ORG_A).getStatusCode().value();
            };
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            startTogether.countDown();
            List<Integer> statuses = List.of(first.get(90, TimeUnit.SECONDS),
                    second.get(90, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(status -> status == 201).count(),
                    "exactly one concurrent booking must commit, statuses=" + statuses);
            assertEquals(1, statuses.stream().filter(status -> status == 409).count(),
                    "the other concurrent booking must be refused, statuses=" + statuses);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(before + 1, appointmentCount(ORG_A, patient));
        assertEquals(1, appointmentCountAtSlot(ORG_A, patient, COMPETE_START));
    }

    @Test
    void anOverlappingBookingOfTheSameProtectedClinicianIsRefusedAndWritesNothing() throws Exception {
        seedBusySlot(CLINICIAN_START, "2027-08-15T14:30:00Z", "DR-A", "ROOM-1");
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);

        // No application-level pre-check runs for a single booking: the 409 must
        // come from the database exclusion constraint inside the transaction.
        ResponseEntity<String> response = post("/api/v1/appointments",
                bookingBody(patient, CLINICIAN_OVERLAP, "DR-A", "ROOM-1"), RECEPTION, ORG_A);

        assertEquals(409, response.getStatusCode().value(), response.getBody());
        assertEquals("SLOT_CONFLICT", json(response).get("code").asText());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void anOverlappingBookingOfTheSameProtectedRoomIsRefusedEvenForADifferentClinician() throws Exception {
        // Only the room is shared, so the clinician exclusion constraint cannot
        // explain the refusal; the room constraint must resolve it transactionally.
        seedBusySlot(ROOM_START, "2027-08-22T14:30:00Z", "DR-B", "ROOM-1");
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);

        ResponseEntity<String> response = post("/api/v1/appointments",
                bookingBody(patient, ROOM_OVERLAP, "DR-A", "ROOM-1"), RECEPTION, ORG_A);

        assertEquals(409, response.getStatusCode().value(), response.getBody());
        assertEquals("SLOT_CONFLICT", json(response).get("code").asText());
        assertEquals(before, appointmentCount(ORG_A, patient));
        assertNotNull(json(response).get("message"));
    }
}
