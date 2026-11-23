package com.aurevia.smartclinic.chart.clauses;

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
 * CLN-06 clause 1 (API evidence): a follow-up date is suggested from an approved
 * visit-type interval or an applicable prior follow-up, and the rule is explained.
 *
 * <p>The normal case derives the civil date from the tenant's approved interval for
 * the visit type. An applicable prior follow-up of the same visit type takes
 * precedence and is named in the explanation, even when no interval is approved.
 * When neither exists the command refuses with {@code NO_FOLLOW_UP_RULE}. A subject
 * without {@code APPOINTMENT_READ}, or an actor organization that cannot see the
 * requested facility, is refused and no suggestion is stored. Suggestion never
 * books: only the separate explicit-confirmation command creates an appointment.</p>
 *
 * <p>All requests go through the real {@code POST /api/v1/follow-ups/suggest}
 * entry point against a fresh database created from the production Flyway
 * migrations. Fixtures are synthetic: no real identity or patient data is used.
 * Each test uses its own synthetic patient so row-count assertions are independent
 * of test order on the class-level database.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_clause1_followup_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SuggestFollowUpDateUsingConfiguredVisitTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "smartclinic_clause1_followup_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String BOOTSTRAP = "bootstrap-a";
    private static final String RECEPTION = "reception-a";
    private static final String PRIVACY = "privacy-a";
    private static final String PEER = "peer-a";
    private static final String FACILITY = "MAIN";
    private static final String VISIT = "FOLLOWUP";
    private static final String VISIT_WITHOUT_CONFIG = "CHECK";
    private static final String CONFIG_KEY = "followup.interval.followup";
    private static final String ENCOUNTER = "2027-03-06T15:00:00Z";
    private static final String PRIOR_APPOINTMENT = UUID.randomUUID().toString();
    private static final String PRIOR_WITHOUT_CONFIG_APPOINTMENT = UUID.randomUUID().toString();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @LocalServerPort
    int port;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed two synthetic organizations, an approved interval and two prior follow-ups.
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
            seedPrimaryOrganization(statement);
            seedPeerOrganization(statement);
            seedApprovedInterval(statement);
            seedPriorAppointments(statement);
        }
    }

    /**
     * Drop the fixture database after the class.
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

    private static void seedPrimaryOrganization(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + ORG_A + "', 'Synthetic clause org A')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + BOOTSTRAP + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + ORG_A + "', '" + FACILITY + "', 'Main clinic', 'UTC')");
        // CHECK deliberately has no approved follow-up interval; it drives the
        // no-configured-rule branches while still allowing a prior follow-up.
        statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                + "VALUES ('" + ORG_A + "', '" + VISIT + "', 15), ('" + ORG_A + "', '"
                + VISIT_WITHOUT_CONFIG + "', 15)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + ORG_A + "', id, 'clinician', 'DR-A', 'Synthetic doctor' "
                + "FROM public.facility WHERE code = '" + FACILITY + "'");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + ORG_A + "', id, 'room', 'ROOM-1', 'Synthetic room' "
                + "FROM public.facility WHERE code = '" + FACILITY + "'");
        seedPatient(statement, "MRN-CFG", "Synthetic Config Patient", "synthetic config patient");
        seedPatient(statement, "MRN-PRIOR", "Synthetic Prior Patient", "synthetic prior patient");
        seedPatient(statement, "MRN-PRIOR-NOCFG", "Synthetic Prior No Config Patient",
                "synthetic prior no config patient");
        seedPatient(statement, "MRN-NORULE", "Synthetic No Rule Patient", "synthetic no rule patient");
        seedPatient(statement, "MRN-ROLE", "Synthetic Role Patient", "synthetic role patient");
        seedPatient(statement, "MRN-INPUT", "Synthetic Input Patient", "synthetic input patient");
        seedPatient(statement, "MRN-CROSS", "Synthetic Cross Tenant Patient",
                "synthetic cross tenant patient");
        seedMember(statement, ORG_A, BOOTSTRAP, RECEPTION, "reception");
        seedMember(statement, ORG_A, BOOTSTRAP, PRIVACY, "privacy_auditor");
    }

    private static void seedPeerOrganization(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_B + "'");
        statement.execute("SET app.actor = 'bootstrap-b'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + ORG_B + "', 'Synthetic clause org B')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_B + "', 'bootstrap-b', 'Bootstrap')");
        // The peer organization has only its own facility code. It deliberately has
        // no MAIN facility, so an ORG_A facility reference cannot resolve there and
        // facility lookup fails closed under forced RLS.
        statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                + "VALUES ('" + ORG_B + "', 'PEER', 'Peer clinic', 'UTC')");
        seedMember(statement, ORG_B, "bootstrap-b", PEER, "reception");
    }

    private static void seedPatient(Statement statement, String mrn, String display, String normalized)
            throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_A + "', 1, '" + mrn + "', 'CLINIC', '" + display + "', '"
                + normalized + "', DATE '1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String bootstrap,
                                   String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "' "
                + "AND u.organization_id = '" + org + "'");
    }

    private static void seedApprovedInterval(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        // V5 derives created_by/approved_by from app.actor, which must be a user of
        // the active tenant; effective_config folds only approved, effective entries.
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) "
                + "VALUES ('" + ORG_A + "', '" + CONFIG_KEY + "', "
                + "public.next_config_version('" + CONFIG_KEY + "'), '14')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + ORG_A + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + CONFIG_KEY + "' AND version = 1");
    }

    private static void seedPriorAppointments(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + BOOTSTRAP + "'");
        // The appointment fold selects a non-terminal appointment of the same visit
        // type that started before the encounter. The two slots do not overlap the
        // clinician's gist exclusion constraint.
        statement.execute("""
                INSERT INTO public.appointment
                    (organization_id, id, version, patient_id, facility_id,
                     clinician_resource_id, visit_type, starts_at, ends_at, state)
                SELECT '11111111-1111-1111-1111-111111111111', CAST('%s' AS uuid), 1,
                       (SELECT id FROM public.patient_record WHERE mrn = 'MRN-PRIOR'),
                       f.id, r.id, 'FOLLOWUP', TIMESTAMPTZ '2027-02-20T14:00:00Z',
                       TIMESTAMPTZ '2027-02-20T14:15:00Z', 'booked'
                  FROM public.facility f
                  JOIN public.scheduling_resource r
                    ON r.organization_id = f.organization_id AND r.code = 'DR-A'
                 WHERE f.code = 'MAIN'
                """.formatted(PRIOR_APPOINTMENT));
        statement.execute("""
                INSERT INTO public.appointment
                    (organization_id, id, version, patient_id, facility_id,
                     clinician_resource_id, visit_type, starts_at, ends_at, state)
                SELECT '11111111-1111-1111-1111-111111111111', CAST('%s' AS uuid), 1,
                       (SELECT id FROM public.patient_record WHERE mrn = 'MRN-PRIOR-NOCFG'),
                       f.id, r.id, 'CHECK', TIMESTAMPTZ '2027-02-21T14:00:00Z',
                       TIMESTAMPTZ '2027-02-21T14:15:00Z', 'booked'
                  FROM public.facility f
                  JOIN public.scheduling_resource r
                    ON r.organization_id = f.organization_id AND r.code = 'DR-A'
                 WHERE f.code = 'MAIN'
                """.formatted(PRIOR_WITHOUT_CONFIG_APPOINTMENT));
    }

    private static String patientId(String mrn) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG_A);
                bind.setString(2, BOOTSTRAP);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id::text FROM public.patient_record WHERE mrn = ?")) {
                query.setString(1, mrn);
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? result.getString(1) : UUID.randomUUID().toString();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private ResponseEntity<String> suggest(String patient, String facility, String visit,
                                           String encounterAt, String subject, String org) {
        String body = """
                {"patientId":"%s","facilityCode":"%s","visitType":"%s","encounterAt":"%s"}
                """.formatted(patient, facility, visit, encounterAt);
        return post(body, subject, org);
    }

    private ResponseEntity<String> post(String body, String subject, String org) {
        return rest.post().uri("/api/v1/follow-ups/suggest")
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

    private static long countSuggestions(String org, String patient) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM public.follow_up_suggestion "
                            + "WHERE patient_id = CAST(? AS uuid)")) {
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

    private static long appointmentCount(String patient) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                bind.setString(1, ORG_A);
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

    @Test
    void suggestUsesConfiguredIntervalAndExplainsTheRule() throws Exception {
        String patient = patientId("MRN-CFG");
        long before = appointmentCount(patient);

        ResponseEntity<String> response = suggest(patient, FACILITY, VISIT, ENCOUNTER,
                RECEPTION, ORG_A);

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode suggestion = json(response);
        assertEquals("2027-03-20", suggestion.get("suggestedDate").asText());
        assertEquals(14, suggestion.get("intervalDays").asInt());
        assertEquals("CONFIGURED_INTERVAL", suggestion.get("basis").asText());
        String explanation = suggestion.get("ruleExplanation").asText();
        assertTrue(explanation.contains(CONFIG_KEY), explanation);
        assertTrue(explanation.contains("14 days"), explanation);
        assertTrue(explanation.contains("2027-03-06"), explanation);
        assertFalse(suggestion.get("confirmed").asBoolean());
        assertTrue(suggestion.get("bookingId").isNull());
        assertEquals(1, suggestion.get("version").asInt());
        assertEquals(patient, suggestion.get("patientId").asText());
        assertEquals(1, countSuggestions(ORG_A, patient));
        assertEquals(before, appointmentCount(patient));
    }

    @Test
    void applicablePriorFollowUpTakesPrecedenceOverConfiguredInterval() throws Exception {
        String patient = patientId("MRN-PRIOR");
        long before = appointmentCount(patient);

        ResponseEntity<String> response = suggest(patient, FACILITY, VISIT, ENCOUNTER,
                RECEPTION, ORG_A);

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode suggestion = json(response);
        assertEquals("2027-02-20", suggestion.get("suggestedDate").asText());
        assertTrue(suggestion.get("intervalDays").isNull());
        assertEquals("PRIOR_FOLLOW_UP", suggestion.get("basis").asText());
        String explanation = suggestion.get("ruleExplanation").asText();
        assertTrue(explanation.contains(PRIOR_APPOINTMENT), explanation);
        assertTrue(explanation.contains("takes precedence"), explanation);
        assertTrue(explanation.contains("14 days"), explanation);
        assertTrue(explanation.contains(CONFIG_KEY), explanation);
        assertFalse(suggestion.get("confirmed").asBoolean());
        assertEquals(1, countSuggestions(ORG_A, patient));
        assertEquals(before, appointmentCount(patient));
    }

    @Test
    void priorFollowUpWithoutConfiguredRuleStillExplainsPrecedence() throws Exception {
        String patient = patientId("MRN-PRIOR-NOCFG");

        ResponseEntity<String> response = suggest(patient, FACILITY, VISIT_WITHOUT_CONFIG,
                ENCOUNTER, RECEPTION, ORG_A);

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode suggestion = json(response);
        assertEquals("2027-02-21", suggestion.get("suggestedDate").asText());
        assertTrue(suggestion.get("intervalDays").isNull());
        assertEquals("PRIOR_FOLLOW_UP", suggestion.get("basis").asText());
        String explanation = suggestion.get("ruleExplanation").asText();
        assertTrue(explanation.contains(PRIOR_WITHOUT_CONFIG_APPOINTMENT), explanation);
        assertTrue(explanation.contains("no configured interval"), explanation);
        assertTrue(explanation.contains("CHECK"), explanation);
        assertEquals(1, countSuggestions(ORG_A, patient));
    }

    @Test
    void suggestIsRefusedWhenNoIntervalOrPriorExists() throws Exception {
        String patient = patientId("MRN-NORULE");

        ResponseEntity<String> response = suggest(patient, FACILITY, VISIT_WITHOUT_CONFIG,
                ENCOUNTER, RECEPTION, ORG_A);

        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("NO_FOLLOW_UP_RULE"), response.getBody());
        assertEquals(0, countSuggestions(ORG_A, patient));
    }

    @Test
    void suggestIsRefusedForSubjectWithoutAppointmentReadRole() throws Exception {
        String patient = patientId("MRN-ROLE");

        ResponseEntity<String> response = suggest(patient, FACILITY, VISIT, ENCOUNTER,
                PRIVACY, ORG_A);

        assertEquals(403, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("FORBIDDEN"), response.getBody());
        assertEquals(0, countSuggestions(ORG_A, patient));
    }

    @Test
    void crossTenantSuggestionIsRefused() throws Exception {
        String patient = patientId("MRN-CROSS");

        ResponseEntity<String> response = suggest(patient, FACILITY, VISIT, ENCOUNTER,
                PEER, ORG_B);

        // ORG_B has no MAIN facility, so facility lookup fails closed under forced
        // RLS before any suggestion is stored. The ORG_A facility is concealed.
        assertEquals(404, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("NOT_FOUND"), response.getBody());
        assertEquals(0, countSuggestions(ORG_A, patient));
        assertEquals(0, countSuggestions(ORG_B, patient));
    }

    @Test
    void missingVisitTypeOrEncounterIsRefused() throws Exception {
        String patient = patientId("MRN-INPUT");

        ResponseEntity<String> noVisit = post("""
                {"patientId":"%s","facilityCode":"MAIN","encounterAt":"%s"}
                """.formatted(patient, ENCOUNTER), RECEPTION, ORG_A);
        assertEquals(422, noVisit.getStatusCode().value(), noVisit.getBody());
        assertTrue(noVisit.getBody().contains("INVALID_VISIT_TYPE"), noVisit.getBody());

        ResponseEntity<String> noEncounter = post("""
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOWUP"}
                """.formatted(patient), RECEPTION, ORG_A);
        assertEquals(422, noEncounter.getStatusCode().value(), noEncounter.getBody());
        assertTrue(noEncounter.getBody().contains("INVALID_ENCOUNTER_AT"), noEncounter.getBody());
        assertEquals(0, countSuggestions(ORG_A, patient));
    }
}
