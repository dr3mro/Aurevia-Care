package com.aurevia.it.schedulingcheckin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

import org.flywaydb.core.Flyway;
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

/**
 * S-G1-02 acceptance on a real PostgreSQL instance (CLN-02, ARC-01/02/03, OPS-02).
 *
 * <p>Fixtures are synthetic: two organizations share the same facility, resource
 * and visit-type codes, and one tenant has a deactivated member. No real patient
 * data or credentials are used. The suite runs under the full Maven verify gate;
 * the fast swarm tier excludes it with the {@code integration} tag.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_s_g1_02_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CheckInWorklistIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_s_g1_02_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final ZoneId UTC = ZoneId.of("UTC");

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_s_g1_02_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_s_g1_02_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "clinician-a", "clinician");
            seedMember(statement, ORG_A, "nurse-a", "clinical_assistant");
            seedMember(statement, ORG_A, "auditor-a", "privacy_auditor");
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_B, "reception-b", "reception");
            seedRevokedMember(statement, ORG_A, "former-a", "reception");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_s_g1_02_it WITH (FORCE)");
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap', 'Bootstrap')");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + org + "', 'MAIN', 'Main clinic', 'UTC')");
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + org + "', 'NEW', 30)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic DR-A' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'room', 'ROOM-1', 'Synthetic ROOM-1' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.resource_availability "
                + "(organization_id, resource_id, weekday, start_local, end_local) "
                + "SELECT '" + org + "', r.id, d, TIME '09:00', TIME '17:00' "
                + "FROM public.scheduling_resource r CROSS JOIN generate_series(1, 7) AS d");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
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

    private static void seedRevokedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'revoked' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static Instant utcDay(int weeksAhead, int hour, int minute) {
        return LocalDate.now(UTC).plusWeeks(weeksAhead).with(DayOfWeek.MONDAY)
                .atTime(hour, minute).atZone(UTC).toInstant();
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

    private static String text(ResponseEntity<String> response, String pointer) {
        JsonNode node = json(response);
        JsonNode value = node.at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse " + response.getBody(), exception);
        }
    }

    private static String patientId(String organization) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "bootstrap");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read synthetic patient", exception);
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

    private static long count(String organization, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, "bootstrap");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getLong(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static boolean mutate(String organization, String subject, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, organization, subject);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                return false;
            }
        }
    }

    private String book(String organization, String subject, Instant start) {
        String body = "{\"facilityCode\":\"MAIN\",\"patientId\":\"" + patientId(organization) + "\","
                + "\"visitType\":\"NEW\",\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\","
                + "\"startsAt\":\"" + iso(start) + "\"}";
        return text(send(HttpMethod.POST, "/api/v1/appointments", body, subject, organization), "/id");
    }

    private ResponseEntity<String> verify(String appointmentId, String subject, String organization,
                                          String body) {
        return send(HttpMethod.POST, "/api/v1/clinic/verifications", body, subject, organization);
    }

    private static String verificationBody(String appointmentId, String identifiers, String method,
                                           String alternateCode, String alternateReason) {
        return "{\"appointmentId\":\"" + appointmentId + "\",\"identifiers\":" + identifiers
                + ",\"method\":\"" + method + "\""
                + (alternateCode == null ? "" : ",\"alternateCode\":\"" + alternateCode + "\"")
                + (alternateReason == null ? "" : ",\"alternateReason\":\"" + alternateReason + "\"")
                + "}";
    }

    private ResponseEntity<String> checkIn(String appointmentId, String version, String subject,
                                           String organization) {
        return send(HttpMethod.POST, "/api/v1/clinic/appointments/" + appointmentId + "/check-in",
                null, subject, organization, Map.of("If-Match", version));
    }

    @Test
    void verifyThenCheckInCreatesWorklistEntryHistoryAndAudit() throws Exception {
        String appointmentId = book(ORG_A, "reception-a", utcDay(1, 10, 0));
        ResponseEntity<String> verification = verify(appointmentId, "reception-a", ORG_A,
                verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null));
        assertEquals(HttpStatus.CREATED, verification.getStatusCode(), verification.getBody());
        assertEquals(2, json(verification).at("/identifiers").size());
        assertEquals("MRN", json(verification).at("/identifiers/0").asText());
        assertEquals("DOB", json(verification).at("/identifiers/1").asText());
        assertEquals("reception-a", text(verification, "/verifier"));

        ResponseEntity<String> checked = checkIn(appointmentId, "1", "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, checked.getStatusCode(), checked.getBody());
        assertEquals(appointmentId, text(checked, "/appointmentId"));
        assertEquals("MAIN", text(checked, "/facilityCode"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.worklist_entry "
                + "WHERE appointment_id = '" + appointmentId + "'"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.appointment_event "
                + "WHERE appointment_id = '" + appointmentId + "' AND action = 'checked_in'"));
        assertTrue(count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'IDENTITY_VERIFY' AND resource_id = '" + appointmentId + "'") >= 1);
        assertTrue(count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'VERIFIED_CHECK_IN' AND resource_id = '" + appointmentId + "'") >= 1);
    }

    @Test
    void identityRulesAuthorizationAndGates() {
        Instant start = utcDay(2, 10, 0);
        String appointmentId = book(ORG_A, "reception-a", start);

        ResponseEntity<String> weak = verify(appointmentId, "reception-a", ORG_A,
                verificationBody(appointmentId, "[\"DOB\",\"VISIT_DATE\"]", "two_identifiers", null, null));
        assertEquals(422, weak.getStatusCode().value(), weak.getBody());
        assertEquals("INSUFFICIENT_IDENTIFIERS", text(weak, "/code"));

        ResponseEntity<String> clinician = verify(appointmentId, "clinician-a", ORG_A,
                verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null));
        assertEquals(HttpStatus.FORBIDDEN, clinician.getStatusCode(), clinician.getBody());
        assertEquals(HttpStatus.UNAUTHORIZED,
                verify(appointmentId, null, ORG_A,
                        verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers",
                                null, null)).getStatusCode());

        ResponseEntity<String> noVerification = checkIn(appointmentId, "1", "reception-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, noVerification.getStatusCode(), noVerification.getBody());
        assertEquals("VERIFICATION_REQUIRED", text(noVerification, "/code"));
        assertEquals(428, send(HttpMethod.POST,
                "/api/v1/clinic/appointments/" + appointmentId + "/check-in", null,
                "reception-a", ORG_A).getStatusCode().value());

        ResponseEntity<String> alternate = verify(appointmentId, "reception-a", ORG_A,
                verificationBody(appointmentId, "[]", "documented_alternate",
                        "SITE_BACKUP", "documented outage"));
        assertEquals(HttpStatus.CREATED, alternate.getStatusCode(), alternate.getBody());
        assertEquals("SITE_BACKUP", text(alternate, "/alternateCode"));

        assertEquals(HttpStatus.OK, checkIn(appointmentId, "1", "reception-a", ORG_A).getStatusCode());
        assertEquals(412, checkIn(appointmentId, "1", "reception-a", ORG_A).getStatusCode().value(),
                "a verified appointment cannot be checked in twice");
    }

    @Test
    void denialAuditRowIsCommittedNotRolledBack() throws Exception {
        String appointmentId = book(ORG_A, "reception-a", utcDay(2, 14, 0));
        ResponseEntity<String> denied = verify(appointmentId, "auditor-a", ORG_A,
                verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());

        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.audit_event "
                + "WHERE action = 'IDENTITY_VERIFY' AND outcome = 'DENIED' "
                + "AND resource_id = '" + appointmentId + "'"),
                "a forbidden command must leave a committed DENIED audit row (OPS-02)");
    }

    @Test
    void worklistOrdersByScheduledTimeAndConfiguredPriority() throws Exception {
        String firstId = book(ORG_A, "reception-a", utcDay(3, 9, 0));
        String secondId = book(ORG_A, "reception-a", utcDay(3, 11, 0));
        for (String id : List.of(firstId, secondId)) {
            assertEquals(HttpStatus.CREATED, verify(id, "reception-a", ORG_A,
                    verificationBody(id, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null))
                    .getStatusCode());
            assertEquals(HttpStatus.OK, checkIn(id, "1", "reception-a", ORG_A).getStatusCode());
        }
        // The ledgers are append-only and this suite shares one database across all
        // test methods, so earlier check-ins and walk-ins remain on the worklist.
        // Scope every assertion to the two appointments created by this test rather
        // than assuming an empty table; the repository still orders the whole
        // worklist, so filtering by id preserves the relative order under test.
        assertEquals(2, count(ORG_A, "SELECT count(*) FROM public.worklist_entry "
                + "WHERE appointment_id IN ('" + firstId + "','" + secondId + "')"));
        ResponseEntity<String> list = send(HttpMethod.GET, "/api/v1/clinic/worklist?facilityCode=MAIN",
                null, "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, list.getStatusCode(), list.getBody());
        List<String> ordered = new ArrayList<>();
        for (JsonNode item : json(list)) {
            String appointment = item.at("/appointmentId").asText();
            if (firstId.equals(appointment) || secondId.equals(appointment)) {
                ordered.add(appointment);
            }
        }
        assertEquals(List.of(firstId, secondId), ordered,
                "earlier scheduled time sorts before the later one");
        assertEquals(422, send(HttpMethod.GET, "/api/v1/clinic/worklist?facilityCode=main", null,
                "reception-a", ORG_A).getStatusCode().value());
        assertEquals(422, send(HttpMethod.GET,
                "/api/v1/clinic/worklist?facilityCode=MAIN&limit=0", null,
                "reception-a", ORG_A).getStatusCode().value());
    }

    @Test
    void configuredPriorityIsAppliedToWorklistItems() {
        assertEquals(HttpStatus.CREATED, send(HttpMethod.POST, "/api/v1/configuration",
                "{\"key\":\"clinic.worklist.priority.NEW\",\"value\":\"7\"}",
                "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/configuration/clinic.worklist.priority.NEW/versions/1/approve",
                "{}", "admin-a", ORG_A).getStatusCode());

        String appointmentId = book(ORG_A, "reception-a", utcDay(7, 10, 0));
        assertEquals(HttpStatus.CREATED, verify(appointmentId, "reception-a", ORG_A,
                verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null))
                .getStatusCode());
        assertEquals(HttpStatus.OK, checkIn(appointmentId, "1", "reception-a", ORG_A).getStatusCode());

        JsonNode items = json(send(HttpMethod.GET, "/api/v1/clinic/worklist?facilityCode=MAIN",
                null, "reception-a", ORG_A));
        boolean found = false;
        for (JsonNode item : items) {
            if (appointmentId.equals(item.at("/appointmentId").asText())) {
                assertEquals(7, item.at("/priority").asInt(),
                        "the approved priority setting must be resolved onto the worklist item");
                found = true;
            }
        }
        assertTrue(found, "the checked-in appointment must appear on the worklist");
    }

    @Test
    void assignmentHandoffAndRevocation() throws Exception {
        String appointmentId = book(ORG_A, "reception-a", utcDay(4, 10, 0));
        assertEquals(HttpStatus.CREATED, verify(appointmentId, "reception-a", ORG_A,
                verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null))
                .getStatusCode());
        String itemId = text(checkIn(appointmentId, "1", "reception-a", ORG_A), "/id");

        String handoffBody = "{\"toSubject\":\"clinician-a\",\"reason\":\"coverage\"}";
        assertEquals("NO_ACTIVE_ASSIGNEE", text(send(HttpMethod.POST,
                "/api/v1/clinic/worklist/" + itemId + "/handoff", handoffBody,
                "reception-a", ORG_A, Map.of("If-Match", "1")), "/code"));

        ResponseEntity<String> assigned = send(HttpMethod.POST,
                "/api/v1/clinic/worklist/" + itemId + "/assign",
                "{\"assigneeSubject\":\"nurse-a\"}", "reception-a", ORG_A,
                Map.of("If-Match", "1"));
        assertEquals(HttpStatus.OK, assigned.getStatusCode(), assigned.getBody());
        assertEquals("nurse-a", text(assigned, "/assigneeSubject"));
        assertNotNull(text(assigned, "/assignedAt"));
        assertEquals("2", text(assigned, "/version"));

        ResponseEntity<String> transferred = send(HttpMethod.POST,
                "/api/v1/clinic/worklist/" + itemId + "/handoff", handoffBody,
                "reception-a", ORG_A, Map.of("If-Match", "2"));
        assertEquals(HttpStatus.OK, transferred.getStatusCode(), transferred.getBody());
        assertEquals("clinician-a", text(transferred, "/assigneeSubject"));
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.handoff_event "
                + "WHERE worklist_entry_id = '" + itemId + "' AND from_subject = 'nurse-a' "
                + "AND to_subject = 'clinician-a' AND reason = 'coverage'"));

        String inactiveBody = "{\"toSubject\":\"former-a\",\"reason\":\"backup\"}";
        ResponseEntity<String> inactive = send(HttpMethod.POST,
                "/api/v1/clinic/worklist/" + itemId + "/handoff", inactiveBody,
                "reception-a", ORG_A, Map.of("If-Match", "3"));
        assertEquals(422, inactive.getStatusCode().value(), inactive.getBody());
        assertEquals("INVALID_ASSIGNEE", text(inactive, "/code"));

        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.GET,
                "/api/v1/clinic/worklist?facilityCode=MAIN", null,
                "former-a", ORG_A).getStatusCode());
    }

    @Test
    void unscheduledIntakeAppearsInWorklistAndTenantsStayIsolated() throws Exception {
        ResponseEntity<String> intake = send(HttpMethod.POST, "/api/v1/clinic/worklist/intake",
                "{\"facilityCode\":\"MAIN\",\"patientId\":\"" + patientId(ORG_A) + "\","
                        + "\"reason\":\"walk-in\"}", "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, intake.getStatusCode(), intake.getBody());
        String itemId = text(intake, "/id");
        assertEquals("walk-in", text(intake, "/intakeReason"));
        assertNotNull(itemId);

        JsonNode own = json(send(HttpMethod.GET, "/api/v1/clinic/worklist?facilityCode=MAIN",
                null, "reception-a", ORG_A));
        assertTrue(own.toString().contains(itemId), "the walk-in must appear on its own facility worklist");
        JsonNode peer = json(send(HttpMethod.GET, "/api/v1/clinic/worklist?facilityCode=MAIN",
                null, "reception-b", ORG_B));
        assertFalse(peer.toString().contains(itemId), "another tenant must not see the walk-in");
        assertEquals(422, send(HttpMethod.POST,
                "/api/v1/clinic/worklist/" + itemId + "/assign",
                "{\"assigneeSubject\":\"reception-b\"}", "reception-a", ORG_A,
                Map.of("If-Match", "1")).getStatusCode().value());
    }

    @Test
    void concurrentAssignmentLeavesExactlyOneWinner() throws Exception {
        String appointmentId = book(ORG_A, "reception-a", utcDay(5, 10, 0));
        assertEquals(HttpStatus.CREATED, verify(appointmentId, "reception-a", ORG_A,
                verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null))
                .getStatusCode());
        String itemId = text(checkIn(appointmentId, "1", "reception-a", ORG_A), "/id");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return send(HttpMethod.POST, "/api/v1/clinic/worklist/" + itemId + "/assign",
                            "{\"assigneeSubject\":\"nurse-a\"}", "reception-a", ORG_A,
                            Map.of("If-Match", "1"));
                }));
            }
            int ok = 0;
            int stale = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                int status = future.get(60, TimeUnit.SECONDS).getStatusCode().value();
                if (status == 200) {
                    ok++;
                } else if (status == 412) {
                    stale++;
                }
            }
            assertEquals(1, ok, "exactly one concurrent assignment may win");
            assertEquals(1, stale, "the competing assignment must be refused as stale");
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count(ORG_A, "SELECT count(*) FROM public.handoff_event "
                + "WHERE worklist_entry_id = '" + itemId + "'"));
    }

    @Test
    void verificationLedgerIsAppendOnlyAndEncounterOpeningIsGuarded() throws Exception {
        String appointmentId = book(ORG_A, "reception-a", utcDay(6, 10, 0));
        assertEquals(HttpStatus.CREATED, verify(appointmentId, "reception-a", ORG_A,
                verificationBody(appointmentId, "[\"MRN\",\"DOB\"]", "two_identifiers", null, null))
                .getStatusCode());
        assertEquals(HttpStatus.OK, checkIn(appointmentId, "1", "reception-a", ORG_A).getStatusCode());

        assertFalse(mutate(ORG_A, "reception-a", "UPDATE public.check_in_verification "
                + "SET alternate_reason = 'edited' WHERE appointment_id = '" + appointmentId + "'"),
                "the verification ledger must reject updates");
        assertFalse(mutate(ORG_A, "reception-a", "TRUNCATE TABLE public.handoff_event"),
                "the handoff ledger must reject truncation");

        assertEquals(HttpStatus.FORBIDDEN, send(HttpMethod.POST,
                "/api/v1/clinic/appointments/" + appointmentId + "/open-encounter", null,
                "reception-a", ORG_A, Map.of("If-Match", "2")).getStatusCode());
        assertEquals(HttpStatus.OK, send(HttpMethod.POST,
                "/api/v1/clinic/appointments/" + appointmentId + "/open-encounter", null,
                "clinician-a", ORG_A, Map.of("If-Match", "2")).getStatusCode());
    }
}
