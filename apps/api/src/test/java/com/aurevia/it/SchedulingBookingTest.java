package com.aurevia.it;

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
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.flywaydb.core.Flyway;

/**
 * S-G1-01 acceptance (CLN-01): availability, protected-slot booking, holds,
 * reschedule, the booked → checked in → in progress → completed state machine,
 * recurrence and append-only history on a real PostgreSQL instance.
 *
 * <p>Fixtures are synthetic: three organizations, two of which share the same
 * local facility, resource and visit-type codes. Time zone behaviour is exercised
 * through a {@code Asia/Riyadh} facility (UTC+3, no daylight saving) and a UTC
 * facility so a local wall-clock window is not the same as the UTC instant.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_scheduling_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class SchedulingBookingTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_scheduling_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final ZoneId RIYADH = ZoneId.of("Asia/Riyadh");

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_scheduling_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_scheduling_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, "Asia/Riyadh");
            seedOrganization(statement, ORG_B, "UTC");
            seedOrganization(statement, ORG_C, "UTC");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedMember(statement, ORG_B, "reception-b", "reception");
            seedMember(statement, ORG_C, "reception-c", "reception");
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_C, "admin-c", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_scheduling_it WITH (FORCE)");
        }
    }

    /**
     * Create one synthetic organization with a facility, visit types, protected
     * clinician and room resources, weekly windows and one patient. Two tenants
     * share the same local codes so isolation is observable.
     */
    private static void seedOrganization(Statement statement, String org, String timeZone) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'admin-a'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', '" + timeZone + "')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'NEW', 30), ('" + org + "', 'FOLLOW', 15)");
        for (String clinician : List.of("DR-A", "DR-B", "DR-MON")) {
            statement.execute("INSERT INTO public.scheduling_resource "
                    + "(organization_id, facility_id, kind, code, display_name) "
                    + "SELECT '" + org + "', id, 'clinician', '" + clinician + "', 'Synthetic " + clinician + "' "
                    + "FROM public.facility WHERE code = 'MAIN'");
        }
        for (String room : List.of("ROOM-1", "ROOM-2")) {
            statement.execute("INSERT INTO public.scheduling_resource "
                    + "(organization_id, facility_id, kind, code, display_name) "
                    + "SELECT '" + org + "', id, 'room', '" + room + "', 'Synthetic " + room + "' "
                    + "FROM public.facility WHERE code = 'MAIN'");
        }
        // Every resource is open 09:00-17:00 local time each day except DR-MON,
        // which is open on Monday only so the weekday window is observable.
        statement.execute("INSERT INTO public.resource_availability "
                + "(organization_id, resource_id, weekday, start_local, end_local) "
                + "SELECT '" + org + "', r.id, d, TIME '09:00', TIME '17:00' "
                + "FROM public.scheduling_resource r CROSS JOIN generate_series(1, 7) AS d "
                + "WHERE r.code <> 'DR-MON'");
        statement.execute("INSERT INTO public.resource_availability "
                + "(organization_id, resource_id, weekday, start_local, end_local) "
                + "SELECT '" + org + "', r.id, 1, TIME '09:00', TIME '17:00' "
                + "FROM public.scheduling_resource r WHERE r.code = 'DR-MON'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** The Monday of the given week offset at a local wall-clock time in Riyadh. */
    private static Instant riyadhMonday(int weeksAhead, int hour, int minute) {
        return LocalDate.now(RIYADH).plusWeeks(weeksAhead).with(DayOfWeek.MONDAY)
                .atTime(hour, minute).atZone(RIYADH).toInstant();
    }

    /** A 10:00 UTC slot the given number of whole days from today. */
    private static Instant utcInDays(int days) {
        return LocalDate.now(ZoneId.of("UTC")).atTime(10, 0).atZone(ZoneId.of("UTC")).toInstant()
                .plusSeconds(days * 24L * 3600);
    }

    private static Instant utcDay(int weeksAhead, int hour, int minute) {
        return LocalDate.now(ZoneId.of("UTC")).plusWeeks(weeksAhead).with(DayOfWeek.MONDAY)
                .atTime(hour, minute).atZone(ZoneId.of("UTC")).toInstant();
    }

    private static String iso(Instant instant) {
        return instant.toString();
    }

    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extraHeaders) {
        HttpHeaders headers = headers(subject, organization);
        extraHeaders.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private static String bookingBody(String facilityCode, String patientId, String visitType,
                                      String clinicianCode, String roomCode, Instant start,
                                      String overrideReason) {
        return "{\"facilityCode\":\"" + facilityCode + "\",\"patientId\":\"" + patientId + "\","
                + "\"visitType\":\"" + visitType + "\",\"clinicianCode\":\"" + clinicianCode + "\","
                + (roomCode == null ? "" : "\"roomCode\":\"" + roomCode + "\",")
                + "\"startsAt\":\"" + iso(start) + "\""
                + (overrideReason == null ? "" : ",\"overrideReason\":\"" + overrideReason + "\"")
                + "}";
    }

    private ResponseEntity<String> book(String clinician, String room, Instant start, String subject,
                                        String organization) {
        return book(clinician, room, start, subject, organization, null);
    }

    private ResponseEntity<String> book(String clinician, String room, Instant start, String subject,
                                        String organization, String overrideReason) {
        return send(HttpMethod.POST, "/api/v1/appointments",
                bookingBody("MAIN", patientId(organization), "NEW", clinician, room, start, overrideReason),
                subject, organization);
    }

    private static String patientId(String organization) {
        return switch (organization) {
            case ORG_A -> patientIdOf(ORG_A);
            case ORG_B -> patientIdOf(ORG_B);
            default -> patientIdOf(ORG_C);
        };
    }

    /** Read the synthetic patient identifier of one organization. */
    private static String patientIdOf(String organization) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next(), "seeded patient must exist in " + organization);
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private static void bind(Connection connection, String organization, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static String actorId(String organization, String subject) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, subject);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity WHERE subject = ?")) {
                statement.setString(1, subject);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), "seeded user must exist");
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic actor", exception);
        }
    }

    private static long scalarAsLong(String organization, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void execute(String organization, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
        }
    }

    private static String text(ResponseEntity<String> response, String pointer) {
        try {
            JsonNode node = MAPPER.readTree(response.getBody());
            JsonNode value = node.at(pointer);
            assertFalse(value.isMissingNode(), "missing field " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read " + pointer + " from " + response.getBody(), exception);
        }
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    @Test
    void booksWithinAvailabilityAndRecordsHistoryAndAudit() {
        Instant start = riyadhMonday(1, 10, 0);
        ResponseEntity<String> created = book("DR-A", "ROOM-1", start, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("booked", text(created, "/state"));
        assertEquals("1", text(created, "/version"));
        assertEquals(start, Instant.parse(text(created, "/startsAt")));
        // The 30-minute visit type owns the end of the slot.
        assertEquals(start.plusSeconds(1800), Instant.parse(text(created, "/endsAt")));
        assertEquals("DR-A", text(created, "/clinicianCode"));
        assertEquals("ROOM-1", text(created, "/roomCode"));
        assertEquals("MAIN", text(created, "/facilityCode"));

        String id = text(created, "/id");
        ResponseEntity<String> view = send(HttpMethod.GET, "/api/v1/appointments/" + id, null,
                "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode(), view.getBody());
        JsonNode history = json(view).at("/history");
        assertEquals(1, history.size());
        assertEquals("booked", history.at("/0/action").asText());
        assertEquals(actorId(ORG_A, "reception-a"), history.at("/0/actorId").asText());
        assertEquals(id, text(view, "/appointment/id"));

        assertEquals(1, auditCount(ORG_A, id));
    }

    @Test
    void rejectsSlotConflictAndKeepsSingleWinner() {
        Instant start = riyadhMonday(2, 10, 0);
        assertEquals(HttpStatus.CREATED, book("DR-A", "ROOM-1", start, "reception-a", ORG_A).getStatusCode());
        ResponseEntity<String> conflict = book("DR-A", "ROOM-1", start, "reception-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode(), conflict.getBody());
        assertEquals("SLOT_CONFLICT", text(conflict, "/code"));
        // Adjacent slots are allowed: the interval is half-open.
        ResponseEntity<String> adjacent = book("DR-A", "ROOM-1", start.plusSeconds(1800),
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, adjacent.getStatusCode(), adjacent.getBody());
    }

    @Test
    void concurrentBookingsLeaveExactlyOneWinner() throws Exception {
        Instant start = riyadhMonday(3, 11, 0);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return book("DR-A", "ROOM-1", start, "reception-a", ORG_A);
                }));
            }
            int created = 0;
            int conflicted = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                HttpStatus status = HttpStatus.valueOf(future.get(60, TimeUnit.SECONDS)
                        .getStatusCode().value());
                if (status == HttpStatus.CREATED) {
                    created++;
                } else if (status == HttpStatus.CONFLICT) {
                    conflicted++;
                }
            }
            assertEquals(1, created, "exactly one concurrent booking may win the protected slot");
            assertEquals(1, conflicted, "the competing booking must be refused with 409");
        } finally {
            executor.shutdownNow();
        }
        long occupied = scalarAsLong(ORG_A, "SELECT count(*) FROM public.appointment "
                + "WHERE starts_at = '" + iso(start) + "' AND state IN ('held','booked','checked_in','in_progress')");
        assertEquals(1, occupied, "the database must hold exactly one live appointment for the slot");
    }

    @Test
    void blocksBothClinicianAndRoomOverlapAndAllowsIndependentResources() {
        Instant start = riyadhMonday(2, 13, 0);
        assertEquals(HttpStatus.CREATED, book("DR-A", "ROOM-1", start, "reception-a", ORG_A).getStatusCode());
        // Same room, different clinician: refused.
        assertEquals(HttpStatus.CONFLICT,
                book("DR-B", "ROOM-1", start, "reception-a", ORG_A).getStatusCode());
        // Different room, different clinician: allowed.
        assertEquals(HttpStatus.CREATED,
                book("DR-B", "ROOM-2", start, "reception-a", ORG_A).getStatusCode());
        // Same clinician, no room: still protected by the clinician constraint.
        assertEquals(HttpStatus.CONFLICT,
                book("DR-A", null, start, "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void holdOccupiesSlotAndConvertsAtomically() {
        Instant start = riyadhMonday(4, 10, 0);
        ResponseEntity<String> held = send(HttpMethod.POST, "/api/v1/appointments/holds",
                bookingBody("MAIN", patientId(ORG_A), "NEW", "DR-A", "ROOM-1", start, null),
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, held.getStatusCode(), held.getBody());
        assertEquals("held", text(held, "/state"));
        assertNotNull(text(held, "/expiresAt"));

        // A hold protects the slot against a booking by anyone.
        assertEquals(HttpStatus.CONFLICT,
                book("DR-A", "ROOM-1", start, "reception-a", ORG_A).getStatusCode());

        String id = text(held, "/id");
        ResponseEntity<String> confirmed = send(HttpMethod.POST, "/api/v1/appointments/" + id + "/confirm",
                null, "reception-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, confirmed.getStatusCode(), confirmed.getBody());
        assertEquals("booked", text(confirmed, "/state"));
        assertEquals(id, text(confirmed, "/id"), "confirmation converts the same appointment");
        assertEquals("2", text(confirmed, "/version"));

        // Confirming again is an illegal transition.
        assertEquals(HttpStatus.CONFLICT,
                send(HttpMethod.POST, "/api/v1/appointments/" + id + "/confirm", null,
                        "reception-a", ORG_A, Map.of("If-Match", "2")).getStatusCode());
    }

    @Test
    void expiredHoldReleasesSlotAndCannotBeConfirmed() throws Exception {
        Instant start = riyadhMonday(4, 12, 0);
        ResponseEntity<String> held = send(HttpMethod.POST, "/api/v1/appointments/holds",
                bookingBody("MAIN", patientId(ORG_A), "NEW", "DR-A", "ROOM-1", start, null),
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, held.getStatusCode(), held.getBody());
        String id = held.getBody() == null ? "" : text(held, "/id");

        // Age the hold: the synthetic clock moves it into the past.
        execute(ORG_A, "reception-a",
                "UPDATE public.appointment SET expires_at = now() - interval '1 minute', "
                        + "version = version + 1 WHERE id = '" + id + "'");

        ResponseEntity<String> stale = send(HttpMethod.GET, "/api/v1/appointments/" + id, null,
                "reception-a", ORG_A);
        assertEquals("expired", text(stale, "/appointment/state"), "a stale hold must read as expired");
        assertEquals(HttpStatus.CONFLICT,
                send(HttpMethod.POST, "/api/v1/appointments/" + id + "/confirm", null,
                        "reception-a", ORG_A, Map.of("If-Match", "2")).getStatusCode());

        // The next booking releases the expired hold and takes the slot.
        assertEquals(HttpStatus.CREATED,
                book("DR-A", "ROOM-1", start, "reception-a", ORG_A).getStatusCode());
        long live = scalarAsLong(ORG_A, "SELECT count(*) FROM public.appointment "
                + "WHERE starts_at = '" + iso(start) + "' AND state IN ('held','booked','checked_in','in_progress')");
        assertEquals(1, live);
    }

    @Test
    void rescheduleIsAtomicKeepsHistoryAndRejectsStaleVersion() {
        Instant first = riyadhMonday(5, 10, 0);
        Instant second = riyadhMonday(5, 15, 0);
        ResponseEntity<String> created = book("DR-A", "ROOM-1", first, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");

        String body = "{\"startsAt\":\"" + iso(second) + "\"}";
        ResponseEntity<String> stale = send(HttpMethod.POST, "/api/v1/appointments/" + id + "/reschedule",
                body, "reception-a", ORG_A, Map.of("If-Match", "7"));
        assertEquals(412, stale.getStatusCode().value(), stale.getBody());

        assertEquals(428, send(HttpMethod.POST, "/api/v1/appointments/" + id + "/reschedule",
                body, "reception-a", ORG_A).getStatusCode().value(), "a version precondition is required");

        ResponseEntity<String> moved = send(HttpMethod.POST, "/api/v1/appointments/" + id + "/reschedule",
                body, "reception-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, moved.getStatusCode(), moved.getBody());
        assertEquals("2", text(moved, "/version"));
        assertEquals(second, Instant.parse(text(moved, "/startsAt")));

        JsonNode history = json(send(HttpMethod.GET, "/api/v1/appointments/" + id, null,
                "reception-a", ORG_A)).at("/history");
        assertEquals(2, history.size());
        assertEquals("rescheduled", history.at("/1/action").asText());
        assertEquals(first, Instant.parse(history.at("/1/previousStartsAt").asText()),
                "history must keep the previous slot");

        // The original slot is free again.
        assertEquals(HttpStatus.CREATED,
                book("DR-A", "ROOM-1", first, "reception-a", ORG_A).getStatusCode());

        // A reschedule into an occupied slot is refused and the row is unchanged.
        String occupied = "{\"startsAt\":\"" + iso(first) + "\"}";
        ResponseEntity<String> conflictBody = send(HttpMethod.POST,
                "/api/v1/appointments/" + id + "/reschedule", occupied, "reception-a", ORG_A,
                Map.of("If-Match", "2"));
        assertEquals(HttpStatus.CONFLICT, conflictBody.getStatusCode(), conflictBody.getBody());
    }

    @Test
    void rescheduleKeepsOrReplacesResourcesAndHandlesAClinicianOnlySlot() {
        Instant start = riyadhMonday(8, 9, 30);
        ResponseEntity<String> created = book("DR-A", null, start, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = text(created, "/id");
        assertNull(text(created, "/roomCode"), "a clinician-only appointment has no room");

        // Keeping the assignment while moving the slot.
        Instant second = riyadhMonday(8, 11, 0);
        ResponseEntity<String> moved = send(HttpMethod.POST, "/api/v1/appointments/" + id + "/reschedule",
                "{\"startsAt\":\"" + iso(second) + "\"}", "reception-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, moved.getStatusCode(), moved.getBody());
        assertEquals("DR-A", text(moved, "/clinicianCode"));
        assertNull(text(moved, "/roomCode"));

        // Replacing both the clinician and adding a room.
        Instant third = riyadhMonday(8, 13, 0);
        ResponseEntity<String> reassigned = send(HttpMethod.POST,
                "/api/v1/appointments/" + id + "/reschedule",
                "{\"startsAt\":\"" + iso(third) + "\",\"clinicianCode\":\"DR-B\","
                        + "\"roomCode\":\"ROOM-2\"}", "reception-a", ORG_A, Map.of("If-Match", "2"));
        assertEquals(HttpStatus.OK, reassigned.getStatusCode(), reassigned.getBody());
        assertEquals("DR-B", text(reassigned, "/clinicianCode"));
        assertEquals("ROOM-2", text(reassigned, "/roomCode"));
        assertEquals("3", text(reassigned, "/version"));
        assertEquals(3, json(send(HttpMethod.GET, "/api/v1/appointments/" + id, null,
                "reception-a", ORG_A)).at("/history").size());
    }

    @Test
    void stateMachineMovesBookedThroughCompleted() {
        Instant start = riyadhMonday(6, 9, 30);
        String id = text(book("DR-A", "ROOM-1", start, "reception-a", ORG_A), "/id");

        ResponseEntity<String> checkedIn = send(HttpMethod.POST, "/api/v1/appointments/" + id + "/check-in",
                null, "reception-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, checkedIn.getStatusCode(), checkedIn.getBody());
        assertEquals("checked_in", text(checkedIn, "/state"));

        ResponseEntity<String> started = send(HttpMethod.POST, "/api/v1/appointments/" + id + "/start",
                null, "clinician-a", ORG_A, Map.of("If-Match", "2"));
        assertEquals(HttpStatus.OK, started.getStatusCode(), started.getBody());
        assertEquals("in_progress", text(started, "/state"));

        ResponseEntity<String> completed = send(HttpMethod.POST, "/api/v1/appointments/" + id + "/complete",
                null, "clinician-a", ORG_A, Map.of("If-Match", "3"));
        assertEquals(HttpStatus.OK, completed.getStatusCode(), completed.getBody());
        assertEquals("completed", text(completed, "/state"));

        // Completed is terminal.
        assertEquals(HttpStatus.CONFLICT,
                send(HttpMethod.POST, "/api/v1/appointments/" + id + "/cancel",
                        "{\"reason\":\"too late\"}", "reception-a", ORG_A,
                        Map.of("If-Match", "4")).getStatusCode());

        JsonNode history = json(send(HttpMethod.GET, "/api/v1/appointments/" + id, null,
                "clinician-a", ORG_A)).at("/history");
        assertEquals(4, history.size());
        assertEquals("checked_in", history.at("/1/toState").asText());
        assertEquals("in_progress", history.at("/2/toState").asText());
        assertEquals("completed", history.at("/3/toState").asText());
    }

    @Test
    void cancelAndNoShowRequireAReasonAndRecordActor() {
        Instant start = riyadhMonday(6, 14, 0);
        String cancelled = text(book("DR-A", "ROOM-1", start, "reception-a", ORG_A), "/id");
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments/" + cancelled + "/cancel",
                "{}", "reception-a", ORG_A, Map.of("If-Match", "1")).getStatusCode().value());
        ResponseEntity<String> cancelledResponse = send(HttpMethod.POST,
                "/api/v1/appointments/" + cancelled + "/cancel",
                "{\"reason\":\"patient request\"}", "reception-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, cancelledResponse.getStatusCode(), cancelledResponse.getBody());
        assertEquals("cancelled", text(cancelledResponse, "/state"));

        JsonNode history = json(send(HttpMethod.GET, "/api/v1/appointments/" + cancelled, null,
                "reception-a", ORG_A)).at("/history");
        assertEquals("cancelled", history.at("/1/action").asText());
        assertEquals("patient request", history.at("/1/reason").asText());
        assertEquals(actorId(ORG_A, "reception-a"), history.at("/1/actorId").asText());

        // The cancelled slot is bookable again.
        assertEquals(HttpStatus.CREATED,
                book("DR-A", "ROOM-1", start, "reception-a", ORG_A).getStatusCode());

        Instant later = riyadhMonday(6, 16, 0);
        String absent = text(book("DR-A", "ROOM-1", later, "reception-a", ORG_A), "/id");
        ResponseEntity<String> noShow = send(HttpMethod.POST, "/api/v1/appointments/" + absent + "/no-show",
                "{\"reason\":\"did not attend\"}", "reception-a", ORG_A, Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, noShow.getStatusCode(), noShow.getBody());
        assertEquals("no_show", text(noShow, "/state"));
    }

    @Test
    void outsideAvailabilityNeedsARecordedOverride() {
        Instant outside = riyadhMonday(2, 20, 0);
        ResponseEntity<String> refused = book("DR-A", "ROOM-1", outside, "reception-a", ORG_A);
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("OUTSIDE_AVAILABILITY", text(refused, "/code"));

        ResponseEntity<String> overridden = book("DR-A", "ROOM-1", outside, "reception-a", ORG_A,
                "urgent add-on approved by the duty clinician");
        assertEquals(HttpStatus.CREATED, overridden.getStatusCode(), overridden.getBody());
        assertEquals("urgent add-on approved by the duty clinician", text(overridden, "/overrideReason"));

        // The weekday window is evaluated in the facility time zone: DR-MON is open
        // on Monday only, so a Tuesday 10:00 Riyadh slot is outside availability.
        Instant tuesday = LocalDate.now(RIYADH).plusWeeks(1).with(DayOfWeek.TUESDAY)
                .atTime(10, 0).atZone(RIYADH).toInstant();
        assertEquals(422, book("DR-MON", "ROOM-2", tuesday, "reception-a", ORG_A)
                .getStatusCode().value());
        Instant monday = LocalDate.now(RIYADH).plusWeeks(1).with(DayOfWeek.MONDAY)
                .atTime(10, 0).atZone(RIYADH).toInstant();
        assertEquals(HttpStatus.CREATED, book("DR-MON", "ROOM-2", monday, "reception-a", ORG_A)
                .getStatusCode());
    }

    @Test
    void configuredBookingRulesBoundTheSlot() {
        String minNotice = "{\"key\":\"booking.min_notice_minutes\",\"value\":\"10080\"}";
        String maxAdvance = "{\"key\":\"booking.max_advance_days\",\"value\":\"9\"}";
        assertEquals(HttpStatus.CREATED, send(HttpMethod.POST, "/api/v1/configuration", minNotice,
                "admin-c", ORG_C).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/configuration/booking.min_notice_minutes/versions/1/approve", "{}",
                "admin-c", ORG_C).getStatusCode());
        assertEquals(HttpStatus.CREATED, send(HttpMethod.POST, "/api/v1/configuration", maxAdvance,
                "admin-c", ORG_C).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/configuration/booking.max_advance_days/versions/1/approve", "{}",
                "admin-c", ORG_C).getStatusCode());

        Instant tooSoon = utcInDays(2);
        ResponseEntity<String> min = book("DR-A", "ROOM-1", tooSoon, "reception-c", ORG_C);
        assertEquals(422, min.getStatusCode().value(), min.getBody());
        assertEquals("MIN_NOTICE_NOT_MET", text(min, "/code"));

        Instant tooFar = utcInDays(15);
        ResponseEntity<String> max = book("DR-A", "ROOM-1", tooFar, "reception-c", ORG_C);
        assertEquals(422, max.getStatusCode().value(), max.getBody());
        assertEquals("MAX_ADVANCE_EXCEEDED", text(max, "/code"));

        Instant inside = utcInDays(8);
        assertEquals(HttpStatus.CREATED, book("DR-A", "ROOM-1", inside, "reception-c", ORG_C)
                .getStatusCode(), "a slot inside both configured bounds must be accepted");
    }

    @Test
    void tenantsWithIdenticalIdentifiersStayIsolated() {
        Instant start = utcDay(1, 10, 0);
        ResponseEntity<String> beta = book("DR-A", "ROOM-1", start, "reception-b", ORG_B);
        assertEquals(HttpStatus.CREATED, beta.getStatusCode(), beta.getBody());
        String id = text(beta, "/id");

        // The same local codes exist in ORG_A, but its members cannot see or infer
        // the peer tenant's appointment.
        assertEquals(HttpStatus.NOT_FOUND,
                send(HttpMethod.GET, "/api/v1/appointments/" + id, null, "reception-a", ORG_A)
                        .getStatusCode());
        ResponseEntity<String> own = send(HttpMethod.GET, "/api/v1/appointments/" + id, null,
                "reception-b", ORG_B);
        assertEquals(HttpStatus.OK, own.getStatusCode(), own.getBody());
        assertEquals(id, text(own, "/appointment/id"));

        // The same wall-clock slot is free in the other tenant.
        ResponseEntity<String> alpha = book("DR-A", "ROOM-1", start, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, alpha.getStatusCode(), alpha.getBody());
        assertFalse(id.equals(text(alpha, "/id")));

        // A tenant B patient cannot be booked into a tenant A slot.
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.POST, "/api/v1/appointments",
                bookingBody("MAIN", patientId(ORG_B), "NEW", "DR-A", "ROOM-2", start.plusSeconds(3600), null),
                "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void seriesIsAllOrNothing() throws Exception {
        Instant start = riyadhMonday(7, 9, 30);
        String body = "{\"facilityCode\":\"MAIN\",\"patientId\":\"" + patientId(ORG_A) + "\","
                + "\"visitType\":\"FOLLOW\",\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\","
                + "\"startsAt\":\"" + iso(start) + "\",\"repeatWeeks\":1,\"occurrences\":3}";
        ResponseEntity<String> created = send(HttpMethod.POST, "/api/v1/appointments/series", body,
                "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        JsonNode appointments = json(created);
        assertEquals(3, appointments.size());
        assertEquals("1", appointments.at("/0/version").asText());
        assertEquals("booked", appointments.at("/0/state").asText());
        assertEquals(appointments.at("/0/seriesId").asText(), appointments.at("/2/seriesId").asText());
        // Each occurrence sits one week after the previous one.
        assertEquals(Instant.parse(appointments.at("/0/startsAt").asText()).plusSeconds(7 * 24 * 3600),
                Instant.parse(appointments.at("/1/startsAt").asText()));

        // Occupy the second occurrence of a would-be new series.
        Instant seriesStart = start.plusSeconds(28 * 24 * 3600);
        Instant blocked = seriesStart.plusSeconds(7 * 24 * 3600);
        assertEquals(HttpStatus.CREATED, book("DR-A", "ROOM-1", blocked, "reception-a", ORG_A)
                .getStatusCode());
        String conflictingSeries = "{\"facilityCode\":\"MAIN\",\"patientId\":\"" + patientId(ORG_A) + "\","
                + "\"visitType\":\"FOLLOW\",\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\","
                + "\"startsAt\":\"" + iso(seriesStart) + "\",\"repeatWeeks\":1,\"occurrences\":2}";
        assertEquals(HttpStatus.CONFLICT,
                send(HttpMethod.POST, "/api/v1/appointments/series", conflictingSeries,
                        "reception-a", ORG_A).getStatusCode());
        assertEquals(0, scalarAsLong(ORG_A, "SELECT count(*) FROM public.appointment "
                + "WHERE starts_at = '" + iso(seriesStart) + "'"),
                "a refused series must not leave partial rows behind");

        // Bounds are validated before any write.
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments/series",
                conflictingSeries.replace("\"occurrences\":2", "\"occurrences\":0"),
                "reception-a", ORG_A).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments/series",
                conflictingSeries.replace("\"repeatWeeks\":1", "\"repeatWeeks\":9"),
                "reception-a", ORG_A).getStatusCode().value());
    }

    @Test
    void authorizationAndInputValidation() {
        Instant start = riyadhMonday(1, 16, 0);
        // Unauthenticated and unauthorized callers.
        assertEquals(HttpStatus.UNAUTHORIZED,
                send(HttpMethod.POST, "/api/v1/appointments",
                        bookingBody("MAIN", patientId(ORG_A), "NEW", "DR-A", "ROOM-1", start, null),
                        null, ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, book("DR-A", "ROOM-1", start, "auditor-a", ORG_A)
                .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, book("DR-A", "ROOM-1", start, "clinician-a", ORG_A)
                .getStatusCode());

        // Unknown facility, resources and visit type are concealed or rejected.
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.POST, "/api/v1/appointments",
                bookingBody("NOPE", patientId(ORG_A), "NEW", "DR-A", "ROOM-1", start, null),
                "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, book("DR-Z", "ROOM-1", start, "reception-a", ORG_A)
                .getStatusCode());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments",
                bookingBody("MAIN", patientId(ORG_A), "UNKNOWN", "DR-A", "ROOM-1", start, null),
                "reception-a", ORG_A).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments",
                bookingBody("MAIN", "not-a-uuid", "NEW", "DR-A", "ROOM-1", start, null),
                "reception-a", ORG_A).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments",
                bookingBody("MAIN", patientId(ORG_A), "NEW", "DR-A", "ROOM-1", start,
                        "x".repeat(201)), "reception-a", ORG_A).getStatusCode().value());

        // A bad instant and an oversized reason are rejected before the database.
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments",
                "{\"facilityCode\":\"MAIN\",\"patientId\":\"" + patientId(ORG_A) + "\","
                        + "\"visitType\":\"NEW\",\"clinicianCode\":\"DR-A\","
                        + "\"startsAt\":\"2026-13-45T99:00:00Z\"}", "reception-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/appointments",
                bookingBody("MAIN", patientId(ORG_A), "NEW", "DR-A", "ROOM-1", start, null)
                        .replace("\"NEW\"", "\"NEW\"").replace(iso(start), "tomorrow"),
                "reception-a", ORG_A).getStatusCode().value());

        // A missing appointment is concealed.
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.GET,
                "/api/v1/appointments/44444444-4444-4444-4444-444444444444", null,
                "reception-a", ORG_A).getStatusCode());
        assertEquals(422, send(HttpMethod.GET, "/api/v1/appointments/not-a-uuid", null,
                "reception-a", ORG_A).getStatusCode().value());
    }

    @Test
    void adminEndpointsCreateVisitTypesResourcesAndWindows() {
        String visitType = "{\"code\":\"PROCEDURE\",\"durationMinutes\":45}";
        ResponseEntity<String> createdType = send(HttpMethod.POST, "/api/v1/scheduling/visit-types",
                visitType, "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, createdType.getStatusCode(), createdType.getBody());
        assertEquals("45", text(createdType, "/durationMinutes"));

        String resource = "{\"facilityCode\":\"MAIN\",\"kind\":\"clinician\","
                + "\"code\":\"DR-NEW\",\"displayName\":\"Synthetic New Clinician\"}";
        ResponseEntity<String> createdResource = send(HttpMethod.POST, "/api/v1/scheduling/resources",
                resource, "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, createdResource.getStatusCode(), createdResource.getBody());
        assertEquals("DR-NEW", text(createdResource, "/code"));

        String window = "{\"weekday\":2,\"startLocal\":\"08:00\",\"endLocal\":\"12:00\"}";
        ResponseEntity<String> createdWindow = send(HttpMethod.POST,
                "/api/v1/scheduling/resources/DR-NEW/availability", window, "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, createdWindow.getStatusCode(), createdWindow.getBody());
        assertEquals("2", text(createdWindow, "/weekday"));

        ResponseEntity<String> listed = send(HttpMethod.GET, "/api/v1/scheduling/resources?facilityCode=MAIN",
                null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, listed.getStatusCode(), listed.getBody());
        assertTrue(listed.getBody().contains("DR-NEW"));

        // Administration is limited to tenant administrators.
        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST, "/api/v1/scheduling/resources",
                resource.replace("DR-NEW", "DR-X"), "reception-a", ORG_A).getStatusCode());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/scheduling/resources",
                resource.replace("\"clinician\"", "\"scanner\""), "admin-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/scheduling/visit-types",
                "{\"code\":\"BAD\",\"durationMinutes\":1}", "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/scheduling/resources/DR-NEW/availability",
                "{\"weekday\":9,\"startLocal\":\"08:00\",\"endLocal\":\"12:00\"}",
                "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, send(HttpMethod.POST, "/api/v1/scheduling/resources/DR-NEW/availability",
                "{\"weekday\":2,\"startLocal\":\"12:00\",\"endLocal\":\"08:00\"}",
                "admin-a", ORG_A).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND, send(HttpMethod.POST,
                "/api/v1/scheduling/resources/DR-UNKNOWN/availability",
                window, "admin-a", ORG_A).getStatusCode());
    }

    private long auditCount(String organization, String appointmentId) {
        try {
            return scalarAsLong(organization, "SELECT count(*) FROM public.audit_event "
                    + "WHERE action = 'APPOINTMENT_BOOK' AND resource_id = '" + appointmentId + "'");
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the audit trail", exception);
        }
    }
}
