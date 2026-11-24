package com.aurevia.chart.clauses;

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
 * CLN-06 clause 1 proof at the normal HTTP entry point: a follow-up civil date is
 * suggested from the tenant's approved visit-type interval, or from an applicable
 * prior follow-up when one exists, and the rule is explained. A suggestion does
 * not create a booking, and the rule refuses to invent a date when neither input
 * is available.
 *
 * <p>Uses the same synthetic fixture style as the neighbouring chart clause
 * tests: a dedicated PostgreSQL database migrated from {@code db/migrations},
 * the {@code synthetic-auth} profile and a real {@code RestClient} against a
 * random server port. No production code, migration or contract is changed by
 * this suite. The facility uses {@code America/New_York} so the configured
 * one-day interval crosses a daylight-saving boundary and proves civil-date
 * arithmetic rather than a 24-hour addition.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_cln06_suggest_c01_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class SuggestFollowUpDateUsingConfiguredVisitTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String DB = "orevia_cln06_suggest_c01_it";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String RECEPTION = "reception-1111";
    static final String AUDITOR = "auditor-1111";
    static final String INTERVAL_PATIENT_MRN = "MRN-S1";
    static final String PRIOR_PATIENT_MRN = "MRN-S2";
    static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    RestClient rest;

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
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG_A + "', 'Synthetic 1111')");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG_A + "', 'bootstrap-1111', 'Bootstrap')");
            statement.execute("INSERT INTO public.facility (organization_id, code, name, time_zone) "
                    + "VALUES ('" + ORG_A + "', 'MAIN', 'Main clinic', 'America/New_York')");
            // NOCONF deliberately has no approved interval so the refusal path is
            // reachable without relying on a malformed request.
            statement.execute("INSERT INTO public.visit_type (organization_id, code, duration_minutes) "
                    + "VALUES ('" + ORG_A + "', 'NEW', 30), ('" + ORG_A + "', 'FOLLOW', 15), "
                    + "('" + ORG_A + "', 'NOCONF', 15)");
            statement.execute("INSERT INTO public.scheduling_resource "
                    + "(organization_id, facility_id, kind, code, display_name) "
                    + "SELECT '" + ORG_A + "', id, 'clinician', 'DR-A', 'Synthetic doctor' "
                    + "FROM public.facility WHERE code = 'MAIN'");
            seedPatient(statement, INTERVAL_PATIENT_MRN, "Synthetic Interval Patient");
            seedPatient(statement, PRIOR_PATIENT_MRN, "Synthetic Prior Patient");
            seedMember(statement, RECEPTION, "reception");
            // A privacy auditor has no APPOINTMENT_READ permission, so a suggest
            // request from it is refused at the authorization boundary.
            seedMember(statement, AUDITOR, "privacy_auditor");
            seedPolicy(statement, "followup.interval.new", "1");
            seedPolicy(statement, "followup.interval.follow", "14");
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

    /** Seed one synthetic patient; V7 requires a civil date of birth or a coded unknown reason. */
    private static void seedPatient(Statement statement, String mrn, String name) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'bootstrap-1111'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_A + "', 1, '" + mrn + "', 'CLINIC', '"
                + name + "', '" + name.toLowerCase() + "', DATE '1990-05-20')");
    }

    /** Seed one synthetic member with an active membership and one role. */
    private static void seedMember(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'bootstrap-1111'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + ORG_A + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG_A + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Seed one approved configuration value; only approved values form the setting fold. */
    private static void seedPolicy(Statement statement, String key, String value) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'bootstrap-1111'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + ORG_A + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + ORG_A + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static String patientId(String mrn) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, ORG_A);
                bind.setString(2, RECEPTION);
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

    private static long appointmentCount(String patient) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
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

    /** Seed a prior non-terminal appointment of the given visit type for the patient. */
    private static void seedPriorAppointment(String patient, String visitType, String startsAt,
                                             String endsAt) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'bootstrap-1111'");
            statement.execute("""
                    INSERT INTO public.appointment
                        (organization_id, id, version, patient_id, facility_id,
                         clinician_resource_id, visit_type, starts_at, ends_at, state)
                    SELECT '%s', gen_random_uuid(), 1, CAST('%s' AS uuid), f.id, r.id, '%s',
                           TIMESTAMPTZ '%s', TIMESTAMPTZ '%s', 'booked'
                      FROM public.facility f
                      JOIN public.scheduling_resource r
                        ON r.organization_id = f.organization_id AND r.code = 'DR-A'
                     WHERE f.code = 'MAIN'
                    """.formatted(ORG_A, patient, visitType, startsAt, endsAt));
        }
    }

    private ResponseEntity<String> post(String path, String body, String subject, String org) {
        return rest.post().uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .header("If-Match", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    @Test
    void suggestsFollowUpDateFromConfiguredVisitTypeInterval() throws Exception {
        String patient = patientId(INTERVAL_PATIENT_MRN);
        long before = appointmentCount(patient);

        // 04:30Z on 8 March 2026 is 23:30 EST on 7 March in New York. Adding one
        // civil day yields 8 March; adding 24 hours would yield 9 March because
        // the intervening night has only 23 hours.
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"NEW",
                 "encounterAt":"2026-03-08T04:30:00Z"}
                """.formatted(patient);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A);

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode suggestion = json(response);
        assertEquals("2026-03-08", suggestion.get("suggestedDate").asText());
        assertEquals(1, suggestion.get("intervalDays").asInt());
        assertEquals("CONFIGURED_INTERVAL", suggestion.get("basis").asText());
        assertTrue(suggestion.get("ruleExplanation").asText().contains("1 days"));
        assertTrue(suggestion.get("ruleExplanation").asText().contains("followup.interval.new"));
        assertFalse(suggestion.get("confirmed").asBoolean());
        assertTrue(suggestion.get("bookingId").isNull(), "a suggestion must not be booked");
        assertEquals(before, appointmentCount(patient), "a suggestion must not create a booking");
    }

    @Test
    void suggestsApplicablePriorFollowUpDateOverConfiguredInterval() throws Exception {
        String patient = patientId(PRIOR_PATIENT_MRN);
        // FOLLOW has an approved 14-day interval (which would give 2027-03-20),
        // but this earlier non-terminal appointment of the same visit type must win.
        seedPriorAppointment(patient, "FOLLOW", "2027-02-20T14:00:00Z", "2027-02-20T14:15:00Z");

        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A);

        assertEquals(200, response.getStatusCode().value(), response.getBody());
        JsonNode suggestion = json(response);
        assertEquals("PRIOR_FOLLOW_UP", suggestion.get("basis").asText());
        assertEquals("2027-02-20", suggestion.get("suggestedDate").asText());
        assertTrue(suggestion.get("intervalDays").isNull());
        assertTrue(suggestion.get("ruleExplanation").asText().contains("takes precedence"));
        assertFalse(suggestion.get("confirmed").asBoolean());
    }

    @Test
    void refusesSuggestionWhenNoIntervalAndNoPriorFollowUpApply() throws Exception {
        String patient = patientId(INTERVAL_PATIENT_MRN);
        long before = appointmentCount(patient);

        // NOCONF has no approved interval and the patient has no prior appointment
        // of that visit type, so the rule must refuse instead of inventing a date.
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"NOCONF",
                 "encounterAt":"2027-03-06T15:00:00Z"}
                """.formatted(patient);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, RECEPTION, ORG_A);

        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("NO_FOLLOW_UP_RULE"));
        assertEquals(before, appointmentCount(patient), "a refused suggestion must not create a booking");
    }

    @Test
    void refusesSuggestionForRoleWithoutAppointmentRead() throws Exception {
        String patient = patientId(INTERVAL_PATIENT_MRN);
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"NEW",
                 "encounterAt":"2026-03-08T04:30:00Z"}
                """.formatted(patient);

        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body, AUDITOR, ORG_A);

        assertEquals(403, response.getStatusCode().value(), response.getBody());
    }
}
