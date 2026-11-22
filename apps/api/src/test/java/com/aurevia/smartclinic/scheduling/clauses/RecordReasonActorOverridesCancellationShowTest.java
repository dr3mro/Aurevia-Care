package com.aurevia.smartclinic.scheduling.clauses;

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
 * CLN-01 clause 5 acceptance on real PostgreSQL: an availability override, a
 * cancellation and a no-show each carry the recorded reason and the
 * authenticated actor, while a missing reason, a stale version, an
 * outside-availability slot without an override and another tenant are all
 * refused. Cancellation and no-show reasons survive through the real HTTP entry
 * point into the append-only appointment history; an override reason lives on
 * the appointment projection. The actor is derived by the V8 database trigger
 * from the authenticated subject, never from a request-body field. Fixtures are
 * synthetic and the peer tenant shares the same local codes. Dates are fixed
 * Sundays inside the 365-day default booking window; the seeded Sunday
 * 09:00-12:00 America/New_York availability and zero default minimum notice are
 * fixture assumptions, not the clause under test.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_reason_actor_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class RecordReasonActorOverridesCancellationShowTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_reason_actor_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String PEER = "reception-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Distinct Sundays keep every test's protected resources independent in the
    // shared database. Availability is 09:00-12:00 America/New_York on Sunday:
    // 13:00Z is 09:00 local (inside), 18:00Z is 14:00 local (outside).
    private static final String OVERRIDE_START = "2027-06-06T18:00:00Z";
    private static final String OUTSIDE_NO_REASON_START = "2027-06-13T18:00:00Z";
    private static final String CANCEL_START = "2027-06-20T13:00:00Z";
    private static final String NO_SHOW_START = "2027-06-27T13:00:00Z";
    private static final String CANCEL_NO_REASON_START = "2027-07-04T13:00:00Z";
    private static final String NO_SHOW_NO_REASON_START = "2027-07-11T13:00:00Z";
    private static final String STALE_START = "2027-07-18T13:00:00Z";
    private static final String PEER_START = "2027-07-25T13:00:00Z";
    private static final String BLANK_CANCEL_START = "2027-08-01T13:00:00Z";
    private static final String BLANK_NO_SHOW_START = "2027-08-08T13:00:00Z";
    private static final String SPOOF_ACTOR_START = "2027-08-15T13:00:00Z";

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

    /** Look up a synthetic subject's tenant-local identity id in the forced-RLS schema. */
    private static String identityId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id::text FROM public.user_identity "
                            + "WHERE organization_id = CAST(? AS uuid) AND subject = ?")) {
                query.setString(1, org);
                query.setString(2, subject);
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? result.getString(1) : null;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static String patientId(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
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

    private static String bookingBody(String patient, String start, String overrideReason) {
        return "{\"patientId\":\"" + patient + "\",\"facilityCode\":\"MAIN\",\"visitType\":\"NEW\","
                + "\"clinicianCode\":\"DR-A\",\"roomCode\":\"ROOM-1\",\"startsAt\":\"" + start + "\","
                + "\"overrideReason\":" + (overrideReason == null
                        ? "null" : "\"" + overrideReason + "\"") + "}";
    }

    private String book(String patient, String start) throws Exception {
        ResponseEntity<String> created = post("/api/v1/appointments", bookingBody(patient, start, null),
                RECEPTION, ORG_A, null);
        assertEquals(201, created.getStatusCode().value(), created.getBody());
        return json(created).get("id").asText();
    }

    private static String reasonBody(String reason) {
        return reason == null ? "{}" : "{\"reason\":\"" + reason + "\"}";
    }

    @Test
    void overrideBookingRecordsTheReasonAndAuthenticatedActor() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String reason = "clinic closed for maintenance; manager approved";

        ResponseEntity<String> created = post("/api/v1/appointments",
                bookingBody(patient, OVERRIDE_START, reason), RECEPTION, ORG_A, null);

        assertEquals(201, created.getStatusCode().value(), created.getBody());
        JsonNode booked = json(created);
        assertEquals(reason, booked.get("overrideReason").asText());
        String id = booked.get("id").asText();
        JsonNode history = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A)).get("history");
        assertEquals(1, history.size());
        assertEquals(identityId(ORG_A, RECEPTION), history.get(0).get("actorId").asText());
    }

    @Test
    void cancellationRecordsTheReasonAndAuthenticatedActor() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, CANCEL_START);
        String reason = "patient requested cancellation";

        ResponseEntity<String> cancelled = post("/api/v1/appointments/" + id + "/cancel",
                reasonBody(reason), RECEPTION, ORG_A, "\"1\"");

        assertEquals(200, cancelled.getStatusCode().value(), cancelled.getBody());
        JsonNode result = json(cancelled);
        assertEquals("cancelled", result.get("state").asText());
        assertEquals(2, result.get("version").asInt());
        JsonNode history = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A)).get("history");
        assertEquals(2, history.size());
        JsonNode event = history.get(1);
        assertEquals("cancelled", event.get("action").asText());
        assertEquals("booked", event.get("fromState").asText());
        assertEquals("cancelled", event.get("toState").asText());
        assertEquals(reason, event.get("reason").asText());
        assertEquals(identityId(ORG_A, RECEPTION), event.get("actorId").asText());
    }

    @Test
    void noShowRecordsTheReasonAndAuthenticatedActor() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, NO_SHOW_START);
        String reason = "patient did not attend; two calls logged";

        ResponseEntity<String> noShow = post("/api/v1/appointments/" + id + "/no-show",
                reasonBody(reason), RECEPTION, ORG_A, "\"1\"");

        assertEquals(200, noShow.getStatusCode().value(), noShow.getBody());
        JsonNode result = json(noShow);
        assertEquals("no_show", result.get("state").asText());
        JsonNode history = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A)).get("history");
        assertEquals(2, history.size());
        JsonNode event = history.get(1);
        assertEquals("no_show", event.get("action").asText());
        assertEquals("booked", event.get("fromState").asText());
        assertEquals("no_show", event.get("toState").asText());
        assertEquals(reason, event.get("reason").asText());
        assertEquals(identityId(ORG_A, RECEPTION), event.get("actorId").asText());
    }

    @Test
    void cancellationWithoutReasonIsRefusedAndLeavesNoHistoryEvent() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, CANCEL_NO_REASON_START);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/cancel",
                reasonBody(null), RECEPTION, ORG_A, "\"1\"");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REASON_REQUIRED", json(refused).get("code").asText());
        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("booked", detail.get("appointment").get("state").asText());
        assertEquals(1, detail.get("history").size());
    }

    @Test
    void noShowWithoutReasonIsRefusedAndLeavesNoHistoryEvent() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, NO_SHOW_NO_REASON_START);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/no-show",
                reasonBody(null), RECEPTION, ORG_A, "\"1\"");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REASON_REQUIRED", json(refused).get("code").asText());
        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("booked", detail.get("appointment").get("state").asText());
        assertEquals(1, detail.get("history").size());
    }

    @Test
    void blankCancellationReasonIsRefusedAndLeavesNoHistoryEvent() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, BLANK_CANCEL_START);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/cancel",
                reasonBody("   "), RECEPTION, ORG_A, "\"1\"");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REASON_REQUIRED", json(refused).get("code").asText());
        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("booked", detail.get("appointment").get("state").asText());
        assertEquals(1, detail.get("history").size());
    }

    @Test
    void blankNoShowReasonIsRefusedAndLeavesNoHistoryEvent() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, BLANK_NO_SHOW_START);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/no-show",
                reasonBody("   "), RECEPTION, ORG_A, "\"1\"");

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("REASON_REQUIRED", json(refused).get("code").asText());
        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("booked", detail.get("appointment").get("state").asText());
        assertEquals(1, detail.get("history").size());
    }

    @Test
    void cancelIgnoresAClientSuppliedActor() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, SPOOF_ACTOR_START);
        String spoofed = identityId(ORG_B, PEER);
        String body = "{\"reason\":\"reason recorded\",\"actorId\":\"" + spoofed
                + "\",\"createdBy\":\"" + spoofed + "\"}";

        ResponseEntity<String> cancelled = post("/api/v1/appointments/" + id + "/cancel",
                body, RECEPTION, ORG_A, "\"1\"");

        assertEquals(200, cancelled.getStatusCode().value(), cancelled.getBody());
        JsonNode history = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A)).get("history");
        assertEquals(2, history.size());
        assertEquals("reason recorded", history.get(1).get("reason").asText());
        assertEquals(identityId(ORG_A, RECEPTION), history.get(1).get("actorId").asText());
    }

    @Test
    void bookingOutsideAvailabilityWithoutOverrideReasonIsRefused() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);

        ResponseEntity<String> refused = post("/api/v1/appointments",
                bookingBody(patient, OUTSIDE_NO_REASON_START, null), RECEPTION, ORG_A, null);

        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("OUTSIDE_AVAILABILITY", json(refused).get("code").asText());
    }

    @Test
    void cancellationWithStaleVersionIsRefused() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, STALE_START);
        ResponseEntity<String> checkedIn = post("/api/v1/appointments/" + id + "/check-in",
                "{}", RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, checkedIn.getStatusCode().value(), checkedIn.getBody());

        // Version 1 is now genuinely stale: the check-in advanced the row to 2.
        // A lower version is the real optimistic-lock race; version 0 cannot be
        // used because IfMatch.parse rejects it as malformed before the domain.
        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/cancel",
                reasonBody("stale reason"), RECEPTION, ORG_A, "\"1\"");

        assertEquals(412, refused.getStatusCode().value(), refused.getBody());
        assertEquals("STALE_VERSION", json(refused).get("code").asText());
        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("checked_in", detail.get("appointment").get("state").asText());
        assertEquals(2, detail.get("history").size());
    }

    @Test
    void peerTenantCannotCancelAnotherTenantsAppointment() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String id = book(patient, PEER_START);

        ResponseEntity<String> refused = post("/api/v1/appointments/" + id + "/cancel",
                reasonBody("peer tenant attempt"), PEER, ORG_B, "\"1\"");

        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        JsonNode detail = json(get("/api/v1/appointments/" + id, RECEPTION, ORG_A));
        assertEquals("booked", detail.get("appointment").get("state").asText());
        assertEquals(1, detail.get("history").size());
    }
}
