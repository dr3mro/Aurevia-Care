package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * CLN-06 clause 3 (API evidence): only explicit confirmation creates a booking.
 *
 * <p>A suggestion computes and explains a civil date; an authorized override records
 * provenance but still books nothing. The separate confirm command is the single caller
 * of the booking port and runs exactly once for the version the caller read. This class
 * drives those commands through the real HTTP endpoints against a fresh real-PostgreSQL
 * database created from the production Flyway migrations, then checks the persisted
 * appointment row rather than trusting a response field alone.</p>
 *
 * <p>Negative cases cover a clinician without booking permission, a peer tenant whose
 * active organization does not own the suggestion, a stale version and a replayed
 * confirmation. Fixtures are synthetic: no real identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_explicit_confirmation_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ExplicitConfirmationCreatesBookingTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_explicit_confirmation_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER = "reception-2222";
    private static final String ZONE = "America/New_York";
    private static final String ENCOUNTER = "2027-03-06T15:00:00Z";
    // The 14-day interval books 2027-03-20 in the replay test. Keep the overridden
    // date distinct: one clinician owns the fixed 09:00 slot and a collision would
    // turn the second successful confirm into a slot conflict instead of a 200.
    private static final String OVERRIDDEN_DATE = "2027-03-25";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter LOCAL_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and seed
     * synthetic memberships and one synthetic patient in each organization.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            seedOrganization(statement, ORG_A, ZONE);
            seedOrganization(statement, ORG_B, "UTC");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_B, PEER, "reception");
            seedPolicy(statement, ORG_A, "followup.interval.follow", "14");
        }
    }

    /**
     * Drop the fixture database after the class so a rerun starts from a clean state.
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
     * @param port the port chosen by Spring Boot
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Persisted facts of the single appointment created by confirmation. */
    private record BookedRow(String reason, String localStart, String state, String visitType,
                             Instant startsAt) {
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
                + "VALUES ('" + org + "', 'FOLLOW', 15)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic doctor' "
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
                // Only confirm reads this header; a harmless value on other
                // commands keeps the shared request builder simple.
                .header("If-Match", ifMatch == null ? "1" : ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    private static String suggestBody(String patientId) {
        return """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"%s"}
                """.formatted(patientId, ENCOUNTER);
    }

    private JsonNode suggest(String patient) throws Exception {
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest",
                suggestBody(patient), RECEPTION, ORG_A, null);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    private ResponseEntity<String> confirm(String id, String subject, String org, String ifMatch) {
        return post("/api/v1/follow-ups/" + id + "/confirm", "{}", subject, org, ifMatch);
    }

    private static long appointmentCount(String org, String patient) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
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

    /**
     * Read the persisted appointment created by confirmation, located by the identifier
     * returned in the confirm response. The facility-local start is rendered with the
     * owning facility's zone so a daylight-saving shift cannot hide a wrong slot.
     *
     * @param org       owning organization
     * @param bookingId identifier returned by the confirm response
     * @return the reason, facility-local start, state and visit type on that row
     * @throws Exception when the row cannot be read
     */
    private static BookedRow bookedRow(String org, String bookingId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT override_reason, "
                            + "to_char(starts_at AT TIME ZONE CAST(? AS text), 'YYYY-MM-DD HH24:MI'), "
                            + "state, visit_type, starts_at FROM public.appointment "
                            + "WHERE id = CAST(? AS uuid)")) {
                query.setString(1, ZONE);
                query.setString(2, bookingId);
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next(), "the confirmed booking row must exist");
                    return new BookedRow(result.getString(1), result.getString(2),
                            result.getString(3), result.getString(4),
                            result.getTimestamp(5).toInstant());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void explicitConfirmationCreatesExactlyOneBookingAtTheOverriddenDate() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);

        JsonNode suggestion = suggest(patient);
        assertFalse(suggestion.get("confirmed").asBoolean());
        assertTrue(suggestion.get("bookingId").isNull());
        assertEquals(before, appointmentCount(ORG_A, patient));
        String id = suggestion.get("id").asText();

        ResponseEntity<String> overriddenResponse = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"" + OVERRIDDEN_DATE + "\",\"reason\":\"clinician directed\"}",
                RECEPTION, ORG_A, null);
        assertEquals(200, overriddenResponse.getStatusCode().value(), overriddenResponse.getBody());
        JsonNode overridden = json(overriddenResponse);
        assertEquals(OVERRIDDEN_DATE, overridden.get("suggestedDate").asText());
        assertEquals(2, overridden.get("version").asInt());
        assertFalse(overridden.get("confirmed").asBoolean());
        assertTrue(overridden.get("bookingId").isNull());
        assertEquals(before, appointmentCount(ORG_A, patient));

        ResponseEntity<String> confirmedResponse = confirm(id, RECEPTION, ORG_A, "\"2\"");
        assertEquals(200, confirmedResponse.getStatusCode().value(), confirmedResponse.getBody());
        JsonNode confirmed = json(confirmedResponse);
        assertTrue(confirmed.get("confirmed").asBoolean());
        assertFalse(confirmed.get("bookingId").isNull());
        String bookingId = confirmed.get("bookingId").asText();
        assertFalse(bookingId.isBlank());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));

        BookedRow row = bookedRow(ORG_A, bookingId);
        assertEquals("follow-up confirmation", row.reason());
        assertEquals("booked", row.state());
        assertEquals("FOLLOW", row.visitType());
        // 09:00 on 2027-03-25 in New York is 13:00Z (EDT, after the spring-forward).
        Instant slot = LocalDate.of(2027, 3, 25).atTime(9, 0).atZone(ZoneId.of(ZONE)).toInstant();
        Instant expected = Instant.parse("2027-03-25T13:00:00Z");
        assertEquals(expected, slot);
        assertEquals(expected, row.startsAt());
        assertEquals(LocalDateTime.of(2027, 3, 25, 9, 0).format(LOCAL_MINUTE), row.localStart());
    }

    @Test
    void clinicianCannotConfirmBecauseBookingIsNotTheirAction() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggest(patient).get("id").asText();

        ResponseEntity<String> denied = confirm(id, CLINICIAN, ORG_A, "1");
        assertEquals(403, denied.getStatusCode().value(), denied.getBody());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void peerTenantCannotConfirmAnotherTenantsSuggestion() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String peerPatient = patientId(ORG_B, PEER);
        long before = appointmentCount(ORG_A, patient);
        long peerBefore = appointmentCount(ORG_B, peerPatient);
        String id = suggest(patient).get("id").asText();

        ResponseEntity<String> denied = confirm(id, PEER, ORG_B, "1");
        assertEquals(404, denied.getStatusCode().value(), denied.getBody());
        assertEquals(before, appointmentCount(ORG_A, patient));
        assertEquals(peerBefore, appointmentCount(ORG_B, peerPatient));
    }

    @Test
    void staleVersionDoesNotBook() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggest(patient).get("id").asText();

        ResponseEntity<String> stale = confirm(id, RECEPTION, ORG_A, "\"3\"");
        assertEquals(412, stale.getStatusCode().value(), stale.getBody());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void secondConfirmationDoesNotBookAgain() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggest(patient).get("id").asText();

        ResponseEntity<String> first = confirm(id, RECEPTION, ORG_A, "1");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        assertTrue(json(first).get("confirmed").asBoolean());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));

        ResponseEntity<String> replay = confirm(id, RECEPTION, ORG_A, "2");
        assertEquals(409, replay.getStatusCode().value(), replay.getBody());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));
    }
}
