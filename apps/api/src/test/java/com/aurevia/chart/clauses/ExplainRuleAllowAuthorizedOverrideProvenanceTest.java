package com.aurevia.chart.clauses;

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
 * CLN-06 clause 2 (API evidence): explain the rule and allow an authorized
 * override with provenance.
 *
 * <p>The positive test drives the real HTTP entry points against PostgreSQL and
 * asserts that an approved visit-type interval is explained on the suggestion and
 * that an authorized override appends the actor, reason, instant and original
 * date to both the response and the persisted row. The negative tests assert the
 * rule refuses what it must refuse: a blank reason, an actor without the
 * reschedule permission, a peer tenant, an already-confirmed suggestion and a
 * second override.</p>
 *
 * <p>Synthetic fixtures only; forced row-level security confines every row to
 * the active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_cln06_override_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ExplainRuleAllowAuthorizedOverrideProvenanceTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "orevia_cln06_override_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
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
            seedOrganization(statement, ORG_B, "UTC");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_B, PEER, "reception");
            seedPolicy(statement, ORG_A, "followup.interval.follow", "14");
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

    /** Seed one tenant with shared local facility, visit-type and patient codes. */
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

    /** Create an active organization member with one bounded role. */
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

    /** Approve one follow-up interval in the tenant configuration fold. */
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

    /** Resolve a tenant-local patient identifier under forced row-level security. */
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
                    if (!result.next()) {
                        throw new AssertionError("synthetic patient row not visible for org " + org);
                    }
                    return result.getString(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** Command-facing projection of the persisted suggestion and its override provenance. */
    private record SuggestionRow(String suggestedDate, String ruleExplanation, String overrideActor,
                                 String overrideReason, String overrideRecordedAt,
                                 String overrideOriginalDate, String overrideReplacementDate,
                                 int version, boolean confirmed) {
    }

    /** Read the persisted row under the tenant's forced row-level security. */
    private static SuggestionRow row(String org, String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT suggested_date::text, rule_explanation, override_actor, override_reason,
                           override_recorded_at::text, override_original_date::text,
                           override_replacement_date::text, version, confirmed
                      FROM public.follow_up_suggestion
                     WHERE organization_id = CAST(? AS uuid) AND id = CAST(? AS uuid)
                    """)) {
                query.setString(1, org);
                query.setString(2, id);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) {
                        throw new AssertionError("suggestion row not visible for id " + id);
                    }
                    return new SuggestionRow(result.getString(1), result.getString(2), result.getString(3),
                            result.getString(4), result.getString(5), result.getString(6),
                            result.getString(7), result.getInt(8), result.getBoolean(9));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /** Post a command as a synthetic subject in the given active organization. */
    private ResponseEntity<String> post(String path, String body, String subject, String org, String ifMatch) {
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

    /** Suggest a FOLLOW visit for the seeded patient in the given tenant. */
    private JsonNode suggest(String subject, String org) throws Exception {
        String patient = patientId(org, subject);
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, subject, org, null);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    @Test
    void suggestionExplainsRuleAndAuthorizedOverrideRecordsProvenanceThroughHttp() throws Exception {
        JsonNode suggestion = suggest(RECEPTION, ORG_A);
        String id = suggestion.get("id").asText();
        assertEquals("2027-03-20", suggestion.get("suggestedDate").asText());
        assertEquals(14, suggestion.get("intervalDays").asInt());
        assertEquals("CONFIGURED_INTERVAL", suggestion.get("basis").asText());
        assertTrue(suggestion.get("ruleExplanation").asText().contains("14 days"),
                suggestion.get("ruleExplanation").asText());
        assertTrue(suggestion.get("ruleExplanation").asText().contains("followup.interval.follow"));
        assertFalse(suggestion.get("confirmed").asBoolean());
        assertTrue(suggestion.get("bookingId").isNull());
        assertTrue(suggestion.get("override").isNull());

        ResponseEntity<String> overriddenResponse = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"clinician directed\"}",
                RECEPTION, ORG_A, null);
        assertEquals(200, overriddenResponse.getStatusCode().value(), overriddenResponse.getBody());
        JsonNode updated = json(overriddenResponse);
        assertEquals("2027-03-25", updated.get("suggestedDate").asText());
        assertEquals(2, updated.get("version").asInt());
        JsonNode override = updated.get("override");
        assertEquals(RECEPTION, override.get("actor").asText());
        assertEquals("clinician directed", override.get("reason").asText());
        assertNotNull(override.get("recordedAt"));
        assertFalse(override.get("recordedAt").isNull());
        assertEquals("2027-03-20", override.get("originalDate").asText());
        assertEquals("2027-03-25", override.get("replacementDate").asText());
        assertTrue(updated.get("ruleExplanation").asText().contains(RECEPTION));
        assertTrue(updated.get("ruleExplanation").asText().contains("clinician directed"));
        assertFalse(updated.get("confirmed").asBoolean());
        assertTrue(updated.get("bookingId").isNull());

        SuggestionRow stored = row(ORG_A, id);
        assertEquals(RECEPTION, stored.overrideActor());
        assertEquals("clinician directed", stored.overrideReason());
        assertNotNull(stored.overrideRecordedAt());
        assertEquals("2027-03-20", stored.overrideOriginalDate());
        assertEquals("2027-03-25", stored.overrideReplacementDate());
        assertEquals(2, stored.version());
        assertFalse(stored.confirmed());
        assertTrue(stored.ruleExplanation().contains(RECEPTION));
        assertTrue(stored.ruleExplanation().contains("clinician directed"));
    }

    @Test
    void overrideWithoutAReasonIsRefusedAndLeavesNoProvenance() throws Exception {
        String id = suggest(RECEPTION, ORG_A).get("id").asText();
        ResponseEntity<String> refused = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"  \"}", RECEPTION, ORG_A, null);
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_REASON", json(refused).get("code").asText());

        SuggestionRow stored = row(ORG_A, id);
        assertEquals(1, stored.version());
        assertNull(stored.overrideActor());
        assertNull(stored.overrideReason());
        assertEquals("2027-03-20", stored.suggestedDate());
    }

    @Test
    void clinicianWhoMayReadCannotOverrideSuggestionWithoutReschedulePermission() throws Exception {
        String id = suggest(CLINICIAN, ORG_A).get("id").asText();
        ResponseEntity<String> readable = get("/api/v1/follow-ups/" + id + "/tasks", CLINICIAN, ORG_A);
        assertEquals(200, readable.getStatusCode().value(), readable.getBody());

        ResponseEntity<String> refused = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"clinician directed\"}",
                CLINICIAN, ORG_A, null);
        assertEquals(403, refused.getStatusCode().value(), refused.getBody());
        assertEquals("FORBIDDEN", json(refused).get("code").asText());
        SuggestionRow stored = row(ORG_A, id);
        assertEquals(1, stored.version());
        assertNull(stored.overrideActor());
    }

    @Test
    void peerTenantCannotOverrideAnotherTenantsSuggestion() throws Exception {
        String id = suggest(RECEPTION, ORG_A).get("id").asText();
        ResponseEntity<String> refused = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"peer\"}", PEER, ORG_B, null);
        assertEquals(404, refused.getStatusCode().value(), refused.getBody());
        assertEquals("NOT_FOUND", json(refused).get("code").asText());
        SuggestionRow stored = row(ORG_A, id);
        assertEquals(1, stored.version());
        assertNull(stored.overrideActor());
    }

    @Test
    void confirmedSuggestionCannotBeOverridden() throws Exception {
        String id = suggest(RECEPTION, ORG_A).get("id").asText();
        ResponseEntity<String> confirmedResponse = post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, confirmedResponse.getStatusCode().value(), confirmedResponse.getBody());
        JsonNode confirmed = json(confirmedResponse);
        assertTrue(confirmed.get("confirmed").asBoolean());
        assertFalse(confirmed.get("bookingId").isNull());
        assertNotNull(confirmed.get("bookingId").asText());
        assertFalse(confirmed.get("bookingId").asText().isEmpty());

        ResponseEntity<String> refused = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"late change\"}", RECEPTION, ORG_A, null);
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("ALREADY_CONFIRMED", json(refused).get("code").asText());
        SuggestionRow stored = row(ORG_A, id);
        assertTrue(stored.confirmed());
        assertNull(stored.overrideActor());
        assertEquals(2, stored.version());
    }

    @Test
    void suggestionCannotBeOverriddenTwice() throws Exception {
        String id = suggest(RECEPTION, ORG_A).get("id").asText();
        ResponseEntity<String> first = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"first change\"}", RECEPTION, ORG_A, null);
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        assertEquals(2, json(first).get("version").asInt());
        assertEquals("2027-03-25", row(ORG_A, id).overrideReplacementDate());

        ResponseEntity<String> refused = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-26\",\"reason\":\"second change\"}", RECEPTION, ORG_A, null);
        assertEquals(409, refused.getStatusCode().value(), refused.getBody());
        assertEquals("ALREADY_OVERRIDDEN", json(refused).get("code").asText());
        SuggestionRow stored = row(ORG_A, id);
        assertEquals(2, stored.version());
        assertEquals("2027-03-25", stored.overrideReplacementDate());
    }
}
