package com.aurevia.smartclinic.chart.clauses;

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
import java.sql.Timestamp;
import java.time.Instant;
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
 * CLN-06 clause 2 (API evidence): the follow-up rule explains itself and an
 * authorized override records who changed the date, why, and what it replaced.
 *
 * <p>The positive test drives the real HTTP entry points against PostgreSQL and
 * asserts that a configured-interval suggestion carries a human-readable rule
 * explanation naming the approved interval and its config key, and that an
 * override appends the authenticated actor, the reason, the instant, and the
 * original and replacement civil dates to both the response and the stored row
 * while leaving the suggestion unconfirmed and unbooked. The negative tests
 * assert the rule refuses what it must refuse: a blank reason, a role without
 * reschedule authority, a suggestion in another tenant, a second override, and
 * an override of a confirmed suggestion. Each refusal is checked together with
 * the unchanged persisted state.</p>
 *
 * <p>Synthetic fixtures only. The actor is derived from the authenticated
 * subject header, never from the request body; forced row-level security keeps
 * every row inside the active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln06_override_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ExplainRuleAllowAuthorizedOverrideProvenanceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_cln06_override_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER = "reception-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Create a fresh database, apply only production migrations and seed synthetic tenants. */
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
            seedOrganization(statement, ORG_B, "UTC");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_B, PEER, "reception");
            seedPolicy(statement, ORG_A, "followup.interval.follow", "14");
        }
    }

    /** Drop the per-class database so the next run starts from the same state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Seed one synthetic tenant with its facility, visit types, resource and patient. */
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

    /** Seed one active synthetic member with a single role in the given tenant. */
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

    /** Seed an approved configuration value so the interval rule can be applied. */
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

    /** Resolve the tenant-local synthetic patient identifier under the caller's subject. */
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

    /** Post a JSON command as the given synthetic subject in the selected organization. */
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

    /** Parse a JSON response body into a tree for field assertions. */
    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    /** Count booked appointments for the synthetic patient in the given tenant. */
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

    /** Snapshot of the persisted suggestion, including all override provenance columns. */
    private record SuggestionRow(String suggestedDate, int version, String overrideActor,
                                 String overrideReason, Instant recordedAt, String originalDate,
                                 String replacementDate, boolean confirmed, String bookingId) {
    }

    /** Read one suggestion row inside the owning tenant so forced RLS applies. */
    private static SuggestionRow readSuggestion(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG_A);
                bind.setString(2, RECEPTION);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT suggested_date::text, version, override_actor, override_reason, "
                            + "override_recorded_at, override_original_date::text, "
                            + "override_replacement_date::text, confirmed, booking_id "
                            + "FROM public.follow_up_suggestion WHERE id = CAST(? AS uuid)")) {
                query.setString(1, id);
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next(), "the suggestion row must exist");
                    Timestamp recorded = result.getTimestamp("override_recorded_at");
                    return new SuggestionRow(result.getString("suggested_date"), result.getInt("version"),
                            result.getString("override_actor"), result.getString("override_reason"),
                            recorded == null ? null : recorded.toInstant(),
                            result.getString("override_original_date"),
                            result.getString("override_replacement_date"),
                            result.getBoolean("confirmed"), result.getString("booking_id"));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** Assert that a refused override left the original suggestion completely untouched. */
    private static void assertUnchangedSuggestion(SuggestionRow row) {
        assertEquals("2027-03-20", row.suggestedDate());
        assertEquals(1, row.version());
        assertNull(row.overrideActor());
        assertNull(row.overrideReason());
        assertNull(row.recordedAt());
        assertNull(row.originalDate());
        assertNull(row.replacementDate());
        assertFalse(row.confirmed());
        assertNull(row.bookingId());
    }

    /** Suggest a FOLLOW date with the synthetic fixtures used by every case in this class. */
    private JsonNode suggestFollow() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A, null);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    /** Override a suggestion after its identifier is known; the actor comes from the header. */
    private ResponseEntity<String> override(String id, String reason, String subject, String org) {
        String body = """
                {"requestedDate":"2027-03-25","reason":"%s"}
                """.formatted(reason);
        return post("/api/v1/follow-ups/" + id + "/override", body, subject, org, null);
    }

    @Test
    void ruleExplanationStatesIntervalAndAuthorizedOverrideKeepsProvenance() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);

        JsonNode suggestion = suggestFollow();
        assertEquals("CONFIGURED_INTERVAL", suggestion.get("basis").asText());
        assertEquals(14, suggestion.get("intervalDays").asInt());
        assertEquals("2027-03-20", suggestion.get("suggestedDate").asText());
        assertEquals(1, suggestion.get("version").asInt());
        String explanation = suggestion.get("ruleExplanation").asText();
        assertTrue(explanation.contains("approved interval 14 days"), explanation);
        assertTrue(explanation.contains("followup.interval.follow"), explanation);
        assertFalse(suggestion.get("confirmed").asBoolean());
        assertTrue(suggestion.get("bookingId").isNull());
        assertTrue(suggestion.get("override").isNull());

        String id = suggestion.get("id").asText();
        ResponseEntity<String> response = override(id, "clinician directed", RECEPTION, ORG_A);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode overridden = json(response);
        assertEquals("2027-03-25", overridden.get("suggestedDate").asText());
        assertEquals(2, overridden.get("version").asInt());

        // The actor is taken from X-Synthetic-Subject, never from the payload;
        // the request body carries only a date and a reason.
        JsonNode provenance = overridden.get("override");
        assertEquals(RECEPTION, provenance.get("actor").asText());
        assertEquals("clinician directed", provenance.get("reason").asText());
        assertFalse(provenance.get("recordedAt").isNull(), "the override must carry a recorded instant");
        Instant recordedAt = Instant.parse(provenance.get("recordedAt").asText());
        assertEquals("2027-03-20", provenance.get("originalDate").asText());
        assertEquals("2027-03-25", provenance.get("replacementDate").asText());
        String overriddenExplanation = overridden.get("ruleExplanation").asText();
        assertTrue(overriddenExplanation.contains("approved interval 14 days"), overriddenExplanation);
        assertTrue(overriddenExplanation.contains("overridden by " + RECEPTION), overriddenExplanation);
        assertTrue(overriddenExplanation.contains("(was 2027-03-20)"), overriddenExplanation);
        assertFalse(overridden.get("confirmed").asBoolean());
        assertTrue(overridden.get("bookingId").isNull());
        assertEquals(before, appointmentCount(ORG_A, patient));

        SuggestionRow row = readSuggestion(id);
        assertEquals("2027-03-25", row.suggestedDate());
        assertEquals(2, row.version());
        assertEquals(RECEPTION, row.overrideActor());
        assertEquals("clinician directed", row.overrideReason());
        assertNotNull(row.recordedAt(), "the override instant must be persisted");
        assertEquals(recordedAt.getEpochSecond(), row.recordedAt().getEpochSecond(),
                "the persisted override instant must match the response");
        assertEquals("2027-03-20", row.originalDate());
        assertEquals("2027-03-25", row.replacementDate());
        assertFalse(row.confirmed());
        assertNull(row.bookingId());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void requestBodyCannotSpoofTheRecordedOverrideActor() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggestFollow().get("id").asText();

        // The override command has no actor or organization field. Extra JSON
        // properties must never become authoritative provenance: the actor is
        // bound from the authenticated subject by the tenant interceptor.
        String body = """
                {"requestedDate":"2027-03-25","reason":"clinician directed",
                 "actor":"spoofed-actor","subject":"spoofed-actor","organizationId":"%s"}
                """.formatted(ORG_B);
        ResponseEntity<String> response = post("/api/v1/follow-ups/" + id + "/override",
                body, RECEPTION, ORG_A, null);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode overridden = json(response);
        assertEquals(RECEPTION, overridden.get("override").get("actor").asText());
        assertEquals("clinician directed", overridden.get("override").get("reason").asText());

        SuggestionRow row = readSuggestion(id);
        assertEquals(RECEPTION, row.overrideActor());
        assertEquals(2, row.version());
        assertFalse(row.confirmed());
        assertNull(row.bookingId());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void blankReasonOverrideIsRefusedAndLeavesSuggestionUnchanged() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggestFollow().get("id").asText();

        ResponseEntity<String> refused = override(id, " ", RECEPTION, ORG_A);
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_REASON", json(refused).get("code").asText());

        SuggestionRow row = readSuggestion(id);
        assertUnchangedSuggestion(row);
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void clinicianCannotOverrideSuggestion() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggestFollow().get("id").asText();

        // Clinicians may read and complete encounters but lack APPOINTMENT_RESCHEDULE.
        ResponseEntity<String> refused = override(id, "clinician directed", CLINICIAN, ORG_A);
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());

        SuggestionRow row = readSuggestion(id);
        assertUnchangedSuggestion(row);
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void peerTenantCannotOverrideAnotherTenantsSuggestion() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggestFollow().get("id").asText();

        ResponseEntity<String> refused = override(id, "peer", PEER, ORG_B);
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());

        SuggestionRow row = readSuggestion(id);
        assertUnchangedSuggestion(row);
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void secondOverrideIsRefusedAsAlreadyOverridden() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggestFollow().get("id").asText();
        assertEquals(200, override(id, "clinician directed", RECEPTION, ORG_A).getStatusCode().value());
        SuggestionRow firstOverride = readSuggestion(id);

        ResponseEntity<String> refused = override(id, "second reason", RECEPTION, ORG_A);
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("ALREADY_OVERRIDDEN", json(refused).get("code").asText());

        SuggestionRow row = readSuggestion(id);
        assertEquals(firstOverride, row, "a refused second override must not rewrite the first provenance");
        assertEquals("2027-03-25", row.suggestedDate());
        assertEquals(2, row.version());
        assertEquals(RECEPTION, row.overrideActor());
        assertEquals("clinician directed", row.overrideReason());
        assertEquals("2027-03-20", row.originalDate());
        assertEquals("2027-03-25", row.replacementDate());
        assertFalse(row.confirmed());
        assertNull(row.bookingId());
        assertEquals(before, appointmentCount(ORG_A, patient));
    }

    @Test
    void overrideAfterConfirmationIsRefusedAsAlreadyConfirmed() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        String id = suggestFollow().get("id").asText();

        ResponseEntity<String> confirmed = post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "1");
        assertEquals(200, confirmed.getStatusCode().value(), confirmed.getBody());
        assertTrue(json(confirmed).get("confirmed").asBoolean());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));

        ResponseEntity<String> refused = override(id, "clinician directed", RECEPTION, ORG_A);
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("ALREADY_CONFIRMED", json(refused).get("code").asText());

        SuggestionRow row = readSuggestion(id);
        assertEquals("2027-03-20", row.suggestedDate());
        assertEquals(2, row.version());
        assertNull(row.overrideActor());
        assertNull(row.overrideReason());
        assertNull(row.recordedAt());
        assertTrue(row.confirmed());
        assertNotNull(row.bookingId());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));
    }
}
