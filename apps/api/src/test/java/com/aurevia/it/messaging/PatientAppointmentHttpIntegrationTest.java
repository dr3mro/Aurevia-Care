package com.aurevia.it.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PAT-01 / ARC-02 HTTP acceptance for patient appointment self-service over the
 * durable tenant-forced-RLS stores. Every request carries only a synthetic
 * subject and an explicitly selected organization. The verified link comes from
 * the seeded database fold; committed facts are re-read through a fresh JDBC
 * connection as a restart proxy. All values are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_patient_appointment_http_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class PatientAppointmentHttpIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SECOND_CHART = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String SUBJECT_A = "patient-http-a";
    private static final String SUBJECT_B = "patient-http-b";
    private static final String GHOST = "ghost-http";
    private static final String SEEDER = "seeder-http";
    private static final String APPOINTMENT = "appt-http-1";
    private static final String STALE_APPOINTMENT = "appt-http-2";
    private static final String FOREIGN_APPOINTMENT = "appt-http-3";
    private static final String VERSIONED_APPOINTMENT = "appt-http-4";
    private static final String DEVICE = "device-http-1";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/aurevia_patient_appointment_http_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String BASE = "/api/v1/patient/appointments";
    private static final Instant STARTS = Instant.parse("2026-12-01T07:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RestClient rest;

    /** Recreate the ephemeral database and seed two isolated tenants with synthetic charts. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_patient_appointment_http_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_patient_appointment_http_it "
                    + "OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, SUBJECT_A, "MRN-APA");
            seedSecondChartAndLink(statement, ORG_A, SUBJECT_A, SECOND_CHART, "MRN-APA-2");
            seedTenant(statement, ORG_B, SUBJECT_B, "MRN-APB");
            bindGuc(statement, ORG_A);
            seedAppointment(statement, ORG_A, APPOINTMENT, CHART, 1);
            seedAppointment(statement, ORG_A, STALE_APPOINTMENT, CHART, 1);
            seedAppointment(statement, ORG_A, FOREIGN_APPOINTMENT, CHART, 1);
            seedAppointment(statement, ORG_A, VERSIONED_APPOINTMENT, CHART, 2);
        }
    }

    /** Drop the ephemeral database so the suite can be replayed on a clean schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_patient_appointment_http_it WITH (FORCE)");
        }
    }

    /** Bind a RestClient to the random application port before each scenario. */
    @BeforeEach
    void initRestClient(@org.springframework.boot.test.web.server.LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void bindGuc(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SEEDER + "'");
    }

    private static void seedTenant(Statement statement, String org, String subject, String mrn)
            throws Exception {
        bindGuc(statement, org);
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Synthetic " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + SEEDER + "', 'Seeder')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', 'Patient')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + CHART + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
        statement.execute("INSERT INTO public.patient_link "
                + "(organization_id, subject, patient_id, active, created_by) "
                + "SELECT '" + org + "', '" + subject + "', '" + CHART + "', true, "
                + "(SELECT id FROM public.user_identity WHERE subject = '" + SEEDER + "')");
    }

    private static void seedSecondChartAndLink(Statement statement, String org, String subject,
                                                String chart, String mrn) throws Exception {
        bindGuc(statement, org);
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + chart + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Second Patient', 'synthetic second patient', DATE '1991-06-21')");
        statement.execute("INSERT INTO public.patient_link "
                + "(organization_id, subject, patient_id, active, created_by) "
                + "SELECT '" + org + "', '" + subject + "', '" + chart + "', true, "
                + "(SELECT id FROM public.user_identity WHERE subject = '" + SEEDER + "')");
    }

    private static void seedAppointment(Statement statement, String org, String id, String patient,
                                        int version) throws Exception {
        statement.execute("INSERT INTO public.patient_appointment_projection "
                + "(organization_id, id, version, patient_id, facility_code, facility_time_zone, "
                + "starts_at, status, confirmation_enabled, confirmed_at) "
                + "VALUES ('" + org + "', '" + id + "', " + version + ", '" + patient
                + "', 'MAIN', 'Asia/Riyadh', CAST('" + STARTS + "' AS timestamptz), "
                + "'booked', true, NULL)");
    }

    private static Connection freshConnection(String org) throws Exception {
        Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = '" + SUBJECT_A + "'");
        }
        return connection;
    }

    private static int scalarCount(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : -1;
        }
    }

    private ResponseEntity<String> send(HttpMethod method, String path, String subject, String org,
                                         String body) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(path)
                .header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", org)
                .contentType(MediaType.APPLICATION_JSON);
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> get(String path, String subject, String org) {
        return send(HttpMethod.GET, path, subject, org, null);
    }

    private ResponseEntity<String> post(String path, String subject, String org, String body) {
        return send(HttpMethod.POST, path, subject, org, body);
    }

    private static JsonNode tree(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot parse appointment response: " + response.getBody(),
                    exception);
        }
    }

    private static String confirmBody(String patient, int expectedVersion) {
        return "{\"patientId\":\"" + patient + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String deviceBody(String patient, String tokenId) {
        return "{\"patientId\":\"" + patient + "\",\"tokenId\":\"" + tokenId
                + "\",\"token\":\"synthetic-provider-token\"}";
    }

    /** The linked patient drives list, confirm and register through the real controller and JDBC stores. */
    @Test
    void linkedPatientUsesTheDurableStoresThroughHttp() throws Exception {
        JsonNode listed = tree(get(BASE + "?patientId=" + CHART, SUBJECT_A, ORG_A));
        assertEquals(200, get(BASE + "?patientId=" + CHART, SUBJECT_A, ORG_A).getStatusCode().value());
        assertEquals("Asia/Riyadh", listed.get(0).get("facilityTimeZone").asText());

        ResponseEntity<String> confirmed = post(BASE + "/" + APPOINTMENT + "/confirm", SUBJECT_A,
                ORG_A, confirmBody(CHART, 1));
        assertEquals(200, confirmed.getStatusCode().value());
        assertEquals(2, tree(confirmed).get("version").asInt());
        assertNotNull(tree(confirmed).get("confirmedAt").asText());

        assertEquals(204, post(BASE + "/devices", SUBJECT_A, ORG_A,
                deviceBody(CHART, DEVICE)).getStatusCode().value());

        try (Connection connection = freshConnection(ORG_A)) {
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM "
                    + "public.patient_appointment_projection WHERE id = '" + APPOINTMENT
                    + "' AND version = 2 AND confirmed_at IS NOT NULL"));
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM public.device_token "
                    + "WHERE token_id = '" + DEVICE + "' AND patient_id = '" + CHART + "'"));
            assertTrue(scalarCount(connection, "SELECT count(*) FROM public.audit_event "
                    + "WHERE action = 'PATIENT_APPOINTMENT_CONFIRM' AND outcome = 'SUCCESS'") >= 1);
        }
    }

    /** A fresh database connection proves the committed facts are not process-local. */
    @Test
    void committedConfirmationSurvivesAFreshConnection() throws Exception {
        assertEquals(200, post(BASE + "/" + STALE_APPOINTMENT + "/confirm", SUBJECT_A, ORG_A,
                confirmBody(CHART, 1)).getStatusCode().value());
        try (Connection connection = freshConnection(ORG_A)) {
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM "
                    + "public.patient_appointment_projection WHERE id = '" + STALE_APPOINTMENT
                    + "' AND version = 2 AND confirmed_at IS NOT NULL"));
        }
    }

    /** Same local chart id in both tenants; forced RLS must conceal the first tenant's rows. */
    @Test
    void otherTenantSeesNoneOfTheFirstTenantsRows() throws Exception {
        ResponseEntity<String> listB = get(BASE + "?patientId=" + CHART, SUBJECT_B, ORG_B);
        assertEquals(200, listB.getStatusCode().value());
        assertEquals(0, tree(listB).size());
        assertEquals(404, post(BASE + "/" + FOREIGN_APPOINTMENT + "/confirm", SUBJECT_B, ORG_B,
                confirmBody(CHART, 1)).getStatusCode().value());
        try (Connection first = freshConnection(ORG_A); Connection second = freshConnection(ORG_B)) {
            assertEquals(1, scalarCount(first, "SELECT count(*) FROM "
                    + "public.patient_appointment_projection WHERE id = '" + FOREIGN_APPOINTMENT + "'"));
            assertEquals(0, scalarCount(second, "SELECT count(*) FROM "
                    + "public.patient_appointment_projection WHERE id = '" + FOREIGN_APPOINTMENT + "'"));
        }
    }

    /** An unlinked subject is denied before any appointment row is read or written. */
    @Test
    void unlinkedSubjectIsForbiddenAndTheDenialIsAudited() throws Exception {
        ResponseEntity<String> denied = get(BASE + "?patientId=" + CHART, GHOST, ORG_A);
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("FORBIDDEN", tree(denied).get("code").asText());
        try (Connection connection = freshConnection(ORG_A)) {
            assertTrue(scalarCount(connection, "SELECT count(*) FROM public.audit_event "
                    + "WHERE action = 'PATIENT_APPOINTMENT_LIST' AND outcome = 'DENIED'") >= 1);
        }
    }

    /** A caller version behind the committed row fails on the database precondition. */
    @Test
    void staleConfirmationIsRejectedWithPreconditionFailed() throws Exception {
        ResponseEntity<String> stale = post(BASE + "/" + VERSIONED_APPOINTMENT + "/confirm",
                SUBJECT_A, ORG_A, confirmBody(CHART, 1));
        assertEquals(412, stale.getStatusCode().value());
        assertEquals("STALE_VERSION", tree(stale).get("code").asText());
        try (Connection connection = freshConnection(ORG_A)) {
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM "
                    + "public.patient_appointment_projection WHERE id = '" + VERSIONED_APPOINTMENT
                    + "' AND version = 2 AND confirmed_at IS NULL"));
        }
    }

    /** A device id cannot be rebound to a second patient in the same tenant. */
    @Test
    void duplicateDeviceIdIsRejectedWithConflict() throws Exception {
        assertEquals(204, post(BASE + "/devices", SUBJECT_A, ORG_A,
                deviceBody(CHART, "device-http-conflict")).getStatusCode().value());
        ResponseEntity<String> conflict = post(BASE + "/devices", SUBJECT_A, ORG_A,
                deviceBody(SECOND_CHART, "device-http-conflict"));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("DEVICE_CONFLICT", tree(conflict).get("code").asText());
        try (Connection connection = freshConnection(ORG_A)) {
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM public.device_token "
                    + "WHERE token_id = 'device-http-conflict' AND patient_id = '" + CHART + "'"));
        }
    }

    /** Invalid-token dispositions are appended without copying the provider token into a log. */
    @Test
    void invalidTokenDispositionIsDurableAndTokenFree() throws Exception {
        String tokenId = "device-http-invalid";
        assertEquals(204, post(BASE + "/devices", SUBJECT_A, ORG_A,
                deviceBody(CHART, tokenId)).getStatusCode().value());
        assertEquals(204, post(BASE + "/devices/" + tokenId + "/invalid?patientId=" + CHART
                + "&reason=INVALID_TOKEN", SUBJECT_A, ORG_A, null).getStatusCode().value());
        try (Connection connection = freshConnection(ORG_A)) {
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM "
                    + "public.device_token_invalid_log WHERE token_id = '" + tokenId
                    + "' AND reason = 'INVALID_TOKEN'"));
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM public.device_token "
                    + "WHERE token_id = '" + tokenId + "' AND state = 'invalid'"));
            assertTrue(scalarCount(connection, "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'device_token_invalid_log' "
                    + "AND column_name = 'token'") == 0);
        }
    }

    /** A generic notice dispatched over HTTP reaches the real local gateway and is audited. */
    @Test
    void notificationAfterRegistrationIsAuditedThroughHttp() throws Exception {
        String tokenId = "device-http-notify";
        assertEquals(204, post(BASE + "/devices", SUBJECT_A, ORG_A,
                deviceBody(CHART, tokenId)).getStatusCode().value());
        assertEquals(204, post(BASE + "/" + APPOINTMENT + "/notify", SUBJECT_A, ORG_A,
                "{\"patientId\":\"" + CHART + "\",\"status\":\"CHECKED_IN\"}")
                .getStatusCode().value());
        try (Connection connection = freshConnection(ORG_A)) {
            // One audit fact per active device; other scenarios may register more.
            assertTrue(scalarCount(connection, "SELECT count(*) FROM public.audit_event "
                    + "WHERE action = 'PUSH_NOTIFY_CHECKED_IN_SENT' AND outcome = 'SUCCESS'") >= 1);
        }
    }

    /** Arbitrary client disposition text is normalized before it reaches the durable ledger. */
    @Test
    void arbitraryDispositionTextIsNotPersistedVerbatim() throws Exception {
        String tokenId = "device-http-invalid-normalized";
        assertEquals(204, post(BASE + "/devices", SUBJECT_A, ORG_A,
                deviceBody(CHART, tokenId)).getStatusCode().value());
        assertEquals(204, post(BASE + "/devices/" + tokenId + "/invalid?patientId=" + CHART
                + "&reason=arbitrary-text-123", SUBJECT_A, ORG_A, null).getStatusCode().value());
        try (Connection connection = freshConnection(ORG_A)) {
            assertEquals(1, scalarCount(connection, "SELECT count(*) FROM "
                    + "public.device_token_invalid_log WHERE token_id = '" + tokenId
                    + "' AND reason = 'UNSPECIFIED'"));
            assertEquals(0, scalarCount(connection, "SELECT count(*) FROM "
                    + "public.device_token_invalid_log WHERE reason = 'arbitrary-text-123'"));
        }
    }

    /** A malformed client patient id must fail closed as a denial, not propagate as a server error. */
    @Test
    void malformedPatientIdReturnsForbiddenNotServerError() {
        ResponseEntity<String> denied = get(BASE + "?patientId=not-a-uuid", SUBJECT_A, ORG_A);
        assertEquals(403, denied.getStatusCode().value());
        assertEquals("FORBIDDEN", tree(denied).get("code").asText());
    }
}
