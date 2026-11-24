package com.aurevia.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CLN-06 clause 3 (API evidence): only explicit confirmation creates a booking.
 *
 * <p>Every request enters through the real {@code /api/v1/follow-ups} HTTP
 * surface against a fresh database created from the authoritative Flyway
 * migrations. The positive test shows that confirmation creates exactly one
 * appointment carrying the follow-up provenance. The negative tests show that
 * suggesting, overriding, opening or completing a task, omitting the version
 * precondition, supplying a non-positive version, supplying a stale version,
 * repeating a confirmation, acting
 * without the booking permission, or acting from a peer tenant all refuse to
 * create a booking. All fixtures are synthetic; no production code is added.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_cln06_confirm_c03_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class ExplicitConfirmationCreatesBookingTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String DB = "aurevia_cln06_confirm_c03_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String RECEPTION = "reception-1111";
    private static final String CLINICIAN = "clinician-1111";
    private static final String PEER = "reception-2222";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    @LocalServerPort
    int port;

    /** Create and seed an isolated synthetic fixture database. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A, "America/New_York");
            seedOrganization(statement, ORG_B, "UTC");
            // Each test that actually books uses its own patient, so an earlier
            // follow-up appointment can never replace the configured interval.
            seedPatient(statement, ORG_A, "MRN-STALE");
            seedPatient(statement, ORG_A, "MRN-REPEAT");
            seedMember(statement, ORG_A, RECEPTION, "reception");
            seedMember(statement, ORG_A, CLINICIAN, "clinician");
            seedMember(statement, ORG_A, "admin-1111", "tenant_admin");
            seedMember(statement, ORG_B, PEER, "reception");
            seedPolicy(statement, ORG_A, "followup.interval.new", "1");
            seedPolicy(statement, ORG_A, "followup.interval.follow", "14");
            seedPolicy(statement, ORG_B, "followup.interval.follow", "30");
        }
    }

    /** Drop the isolated fixture database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Bind the client to the randomly assigned server port. */
    @BeforeEach
    void initRestClient() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
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
                + "VALUES ('" + org + "', 'NEW', 30), ('" + org + "', 'FOLLOW', 15)");
        statement.execute("INSERT INTO public.scheduling_resource "
                + "(organization_id, facility_id, kind, code, display_name) "
                + "SELECT '" + org + "', id, 'clinician', 'DR-A', 'Synthetic doctor' "
                + "FROM public.facility WHERE code = 'MAIN'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20')");
    }

    private static void seedPatient(Statement statement, String org, String mrn) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, '" + mrn + "', 'CLINIC', 'Synthetic " + mrn + "', "
                + "'synthetic " + mrn.toLowerCase() + "', '1990-05-20')");
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
        return patientId(org, subject, "MRN-1");
    }

    private static String patientId(String org, String subject, String mrn) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, subject);
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

    private ResponseEntity<String> post(String path, String body, String subject, String org,
                                         String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("X-Correlation-Id", UUID.randomUUID().toString());
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.post().uri(path)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body(body).retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> response) throws Exception {
        return MAPPER.readTree(response.getBody());
    }

    /** Count the tenant's appointments for a patient, honouring forced RLS. */
    private static long appointmentCount(String org, String patient) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, "reception-" + org.substring(0, 4));
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

    /** Read the provenance reason stamped on a booked appointment. */
    private static String bookingReason(String org, String appointmentId) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, "reception-" + org.substring(0, 4));
                bind.execute();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT override_reason FROM public.appointment WHERE id = CAST(? AS uuid)")) {
                query.setString(1, appointmentId);
                try (ResultSet result = query.executeQuery()) {
                    return result.next() ? result.getString(1) : "";
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private JsonNode suggest(String patient) throws Exception {
        return suggest(patient, "2027-03-06T15:00:00Z");
    }

    /**
     * Suggest a follow-up from a synthetic encounter instant. Callers that go on
     * to confirm use distinct encounter dates because confirmation always books
     * the single 09:00 clinician slot on the suggested date; two suggestions on
     * the same date would clash on that protected resource.
     */
    private JsonNode suggest(String patient, String encounterAt) throws Exception {
        String body = """
                {"patientId":"%s","facilityCode":"MAIN","visitType":"FOLLOW",
                 "encounterAt":"%s"}
                """.formatted(patient, encounterAt);
        ResponseEntity<String> response = post("/api/v1/follow-ups/suggest", body,
                RECEPTION, ORG_A, null);
        assertEquals(200, response.getStatusCode().value(), response.getBody());
        return json(response);
    }

    @Test
    void explicitConfirmationCreatesExactlyOneBookingWithProvenance() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        JsonNode suggestion = suggest(patient);
        String id = suggestion.get("id").asText();
        assertEquals("2027-03-20", suggestion.get("suggestedDate").asText());

        assertFalse(suggestion.get("confirmed").asBoolean());
        assertTrue(suggestion.get("bookingId").isNull());
        assertEquals(before, appointmentCount(ORG_A, patient), "suggestion alone books nothing");

        ResponseEntity<String> confirmed = post("/api/v1/follow-ups/" + id + "/confirm",
                "{}", RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, confirmed.getStatusCode().value(), confirmed.getBody());
        JsonNode result = json(confirmed);
        assertTrue(result.get("confirmed").asBoolean());
        String bookingId = result.get("bookingId").asText();
        assertNotNull(bookingId);
        assertFalse(bookingId.isBlank());
        assertEquals(before + 1, appointmentCount(ORG_A, patient),
                "one explicit confirmation creates exactly one booking");
        assertEquals("follow-up confirmation", bookingReason(ORG_A, bookingId),
                "the booking records why it was created");
    }

    @Test
    void suggestOverrideAndTaskCommandsCreateNoBooking() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        JsonNode suggestion = suggest(patient);
        String id = suggestion.get("id").asText();

        ResponseEntity<String> overrideResponse = post("/api/v1/follow-ups/" + id + "/override",
                "{\"requestedDate\":\"2027-03-25\",\"reason\":\"clinician directed\"}",
                RECEPTION, ORG_A, null);
        assertEquals(200, overrideResponse.getStatusCode().value(), overrideResponse.getBody());
        JsonNode overridden = json(overrideResponse);
        assertEquals(2, overridden.get("version").asInt());
        assertTrue(overridden.get("bookingId").isNull());
        assertEquals(before, appointmentCount(ORG_A, patient), "override books nothing");

        ResponseEntity<String> taskResponse = post("/api/v1/follow-ups/" + id + "/tasks",
                "{\"owner\":\"nurse-1\",\"dueDate\":\"2027-03-18\",\"instruction\":\"call the patient\"}",
                RECEPTION, ORG_A, null);
        assertEquals(200, taskResponse.getStatusCode().value(), taskResponse.getBody());
        JsonNode task = json(taskResponse);
        assertEquals("OPEN", task.get("status").asText());
        assertEquals(before, appointmentCount(ORG_A, patient), "opening a task books nothing");

        ResponseEntity<String> completeResponse = post("/api/v1/follow-ups/tasks/"
                + task.get("id").asText() + "/complete",
                "{\"outcome\":\"patient confirmed by phone\"}", CLINICIAN, ORG_A, null);
        assertEquals(200, completeResponse.getStatusCode().value(), completeResponse.getBody());
        JsonNode completed = json(completeResponse);
        assertEquals("COMPLETED", completed.get("status").asText());
        assertEquals(before, appointmentCount(ORG_A, patient), "completing a task books nothing");
    }

    @Test
    void confirmationWithoutVersionPreconditionCreatesNoBooking() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        JsonNode suggestion = suggest(patient);
        String id = suggestion.get("id").asText();

        assertEquals(428, post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, null).getStatusCode().value());
        assertEquals(before, appointmentCount(ORG_A, patient),
                "a confirmation without an explicit precondition books nothing");
    }

    @Test
    void staleVersionConfirmationCreatesNoBooking() throws Exception {
        String patient = patientId(ORG_A, RECEPTION, "MRN-STALE");
        long before = appointmentCount(ORG_A, patient);
        JsonNode suggestion = suggest(patient, "2027-04-06T15:00:00Z");
        assertEquals("2027-04-20", suggestion.get("suggestedDate").asText());
        String id = suggestion.get("id").asText();

        assertEquals(412, post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "\"99\"").getStatusCode().value());
        assertEquals(before, appointmentCount(ORG_A, patient), "a stale confirmation books nothing");

        ResponseEntity<String> correct = post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, correct.getStatusCode().value(), correct.getBody());
        JsonNode confirmed = json(correct);
        assertTrue(confirmed.get("confirmed").asBoolean());
        assertEquals(before + 1, appointmentCount(ORG_A, patient), "the correct version books once");
    }

    @Test
    void repeatedConfirmationCreatesNoSecondBooking() throws Exception {
        String patient = patientId(ORG_A, RECEPTION, "MRN-REPEAT");
        long before = appointmentCount(ORG_A, patient);
        JsonNode suggestion = suggest(patient, "2027-05-06T15:00:00Z");
        assertEquals("2027-05-20", suggestion.get("suggestedDate").asText());
        String id = suggestion.get("id").asText();

        ResponseEntity<String> first = post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "\"1\"");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        assertTrue(json(first).get("confirmed").asBoolean());
        assertEquals(before + 1, appointmentCount(ORG_A, patient));

        assertEquals(409, post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                RECEPTION, ORG_A, "\"2\"").getStatusCode().value());
        assertEquals(before + 1, appointmentCount(ORG_A, patient),
                "a repeated confirmation never books a second appointment");
    }

    @Test
    void roleWithoutBookingPermissionCreatesNoBooking() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        JsonNode suggestion = suggest(patient);
        String id = suggestion.get("id").asText();

        assertEquals(403, post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                CLINICIAN, ORG_A, "\"1\"").getStatusCode().value());
        assertEquals(before, appointmentCount(ORG_A, patient),
                "a clinician cannot book through follow-up confirmation");
    }

    @Test
    void peerTenantCannotConfirmAnotherTenantsSuggestion() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        String peerPatient = patientId(ORG_B, PEER);
        long before = appointmentCount(ORG_A, patient);
        long peerBefore = appointmentCount(ORG_B, peerPatient);
        JsonNode suggestion = suggest(patient);
        String id = suggestion.get("id").asText();

        assertEquals(404, post("/api/v1/follow-ups/" + id + "/confirm", "{}",
                PEER, ORG_B, "\"1\"").getStatusCode().value());
        assertEquals(before, appointmentCount(ORG_A, patient),
                "a peer tenant cannot book against another tenant's suggestion");
        assertEquals(peerBefore, appointmentCount(ORG_B, peerPatient),
                "the peer tenant books nothing in its own organization either");
    }

    @Test
    void invalidVersionConfirmationCreatesNoBooking() throws Exception {
        String patient = patientId(ORG_A, RECEPTION);
        long before = appointmentCount(ORG_A, patient);
        // An encounter before every booked slot in this fixture keeps the suggestion
        // independent of other tests; no confirmation succeeds here, so no slot is used.
        JsonNode suggestion = suggest(patient, "2027-01-06T15:00:00Z");
        String id = suggestion.get("id").asText();

        ResponseEntity<String> refused = post("/api/v1/follow-ups/" + id + "/confirm",
                "{}", RECEPTION, ORG_A, "\"0\"");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_IF_MATCH", json(refused).get("code").asText(), refused.getBody());
        assertEquals(before, appointmentCount(ORG_A, patient),
                "an invalid version precondition books nothing");
    }
}
