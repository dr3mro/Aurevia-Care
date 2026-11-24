package com.aurevia.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RX-02 clause 7 (API evidence): an ambiguous transmission is retried only after
 * the partner has been queried and reports the submission absent.
 *
 * <p>The positive case walks the real HTTP entry points from an ambiguous send
 * through partner-status verification to a retry that reuses the original stable
 * submission id. The negative cases show that a retry before verification, a
 * partner that already holds the submission, an unknown partner answer, a wrong
 * actor, a foreign tenant, a stale version, a mismatched submission id and a
 * malformed status are all refused without a second send or a state change.</p>
 *
 * <p>All requests go through {@code POST /api/v1/prescriptions/{id}/verify-partner-status}
 * and {@code POST /api/v1/prescriptions/{id}/retry} against a fresh database
 * created from the production Flyway migrations. Fixtures are synthetic: no real
 * identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_rx02_verify_retry_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class AmbiguousTransmissionVerifyPartnerStatusBeforeRetryingTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PRESCRIBER = "clinician-1111";
    private static final String OTHER_PRESCRIBER = "other-1111";
    private static final String FOREIGN_PRESCRIBER = "clinician-2222";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/orevia_rx02_verify_retry_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed two synthetic tenants, their members and one synthetic patient.
     *
     * @throws Exception when the database cannot be created or seeded
     */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_rx02_verify_retry_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_rx02_verify_retry_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, PRESCRIBER, "clinician");
            seedMember(statement, ORG_A, OTHER_PRESCRIBER, "clinician");
            seedMember(statement, ORG_A, "admin-1111", "tenant_admin");
            seedMember(statement, ORG_B, FOREIGN_PRESCRIBER, "clinician");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.market", "US");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.entitlement." + PRESCRIBER, "enabled");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.approved_channels", "E_PRESCRIBE");
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
            statement.execute("DROP DATABASE IF EXISTS orevia_rx02_verify_retry_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void verifyingAbsentBeforeRetryResubmitsWithTheSameSubmissionId() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> verified = verify(ORG_A, PRESCRIBER, id, 3, sid, "absent");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("ambiguous", field(verified, "/state"));
        assertEquals("4", field(verified, "/version"));
        assertEquals("true", field(verified, "/retryAllowed"));

        ResponseEntity<String> retried = retry(ORG_A, PRESCRIBER, id, 4, sid);
        assertEquals(HttpStatus.OK, retried.getStatusCode(), retried.getBody());
        assertEquals("submitted", field(retried, "/state"));
        assertEquals("5", field(retried, "/version"));
        assertEquals(sid, field(retried, "/submissionId"), "the retry reuses the stable submission id");
        assertEquals("false", field(retried, "/retryAllowed"), "the consumed verification cannot be replayed");
        assertEquals(1, countSubmissionRows(ORG_A, id), "one logical submission despite two send facts");
        assertEquals(2, countEventsOfType(ORG_A, id, "submitted"));
        assertEquals(1, countEventsOfType(ORG_A, id, "partner_status_verified"));

        JsonNode events = json(get(ORG_A, PRESCRIBER, id)).at("/events");
        assertEquals(4, events.size());
        assertEquals("attested", events.at("/0/eventType").asText());
        assertEquals("submitted", events.at("/1/eventType").asText());
        assertEquals("partner_status_verified", events.at("/2/eventType").asText());
        assertEquals("absent", events.at("/2/outcome").asText(), "the partner answer is an immutable fact");
        assertEquals("submitted", events.at("/3/eventType").asText());
        assertEquals(sid, events.at("/1/submissionId").asText());
        assertEquals(sid, events.at("/2/submissionId").asText());
        assertEquals(sid, events.at("/3/submissionId").asText());
        assertTrue(events.at("/3/detail").asText().startsWith("attempt=2"),
                "the retry is the second send attempt of the same submission");
    }

    @Test
    void retryBeforeVerificationIsRefusedWithoutSendingAgain() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> refused = retry(ORG_A, PRESCRIBER, id, 3, sid);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("RETRY_NOT_ALLOWED", code(refused));
        assertUnchanged(id, "ambiguous", 3, 1, 0);
    }

    @Test
    void verifyPresentReturnsToSubmittedAndForbidsRetry() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> verified = verify(ORG_A, PRESCRIBER, id, 3, sid, "present");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("submitted", field(verified, "/state"), "a partner that holds the submission takes it back");
        assertEquals("false", field(verified, "/retryAllowed"));

        ResponseEntity<String> refused = retry(ORG_A, PRESCRIBER, id, 4, sid);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", code(refused));
        assertUnchanged(id, "submitted", 4, 1, 1);
    }

    @Test
    void verifyUnknownKeepsAmbiguousAndStillForbidsRetry() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> verified = verify(ORG_A, PRESCRIBER, id, 3, sid, "unknown");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("ambiguous", field(verified, "/state"));
        assertEquals("false", field(verified, "/retryAllowed"));

        ResponseEntity<String> refused = retry(ORG_A, PRESCRIBER, id, 4, sid);
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("RETRY_NOT_ALLOWED", code(refused));
        assertUnchanged(id, "ambiguous", 4, 1, 1);
    }

    @Test
    void aDifferentClinicianCannotVerifyTheSubmission() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> denied = verify(ORG_A, OTHER_PRESCRIBER, id, 3, sid, "absent");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", code(denied));
        assertUnchanged(id, "ambiguous", 3, 1, 0);
    }

    @Test
    void aClinicianFromAnotherTenantCannotVerifyTheSubmission() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> denied = verify(ORG_B, FOREIGN_PRESCRIBER, id, 3, sid, "absent");
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertEquals("NOT_FOUND", code(denied));
        assertUnchanged(id, "ambiguous", 3, 1, 0);
    }

    @Test
    void aStaleVersionCannotVerifyTheSubmission() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> refused = verify(ORG_A, PRESCRIBER, id, 2, sid, "absent");
        assertEquals(HttpStatus.PRECONDITION_FAILED, refused.getStatusCode(), refused.getBody());
        assertEquals("STALE_VERSION", code(refused));
        assertUnchanged(id, "ambiguous", 3, 1, 0);
    }

    @Test
    void aMismatchedSubmissionIdCannotVerifyTheSubmission() {
        String id = newId();
        ambiguous(id);

        ResponseEntity<String> refused = verify(ORG_A, PRESCRIBER, id, 3,
                UUID.randomUUID().toString(), "absent");
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, refused.getStatusCode(), refused.getBody());
        assertEquals("SUBMISSION_MISMATCH", code(refused));
        assertUnchanged(id, "ambiguous", 3, 1, 0);
    }

    @Test
    void anInvalidPartnerStatusCannotVerifyTheSubmission() {
        String id = newId();
        String sid = ambiguous(id);

        ResponseEntity<String> refused = verify(ORG_A, PRESCRIBER, id, 3, sid, "maybe");
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_STATUS", code(refused));
        assertUnchanged(id, "ambiguous", 3, 1, 0);
    }

    /** Drive a fresh synthetic prescription to the ambiguous state and return its submission id. */
    private String ambiguous(String id) {
        ResponseEntity<String> created = create(id);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("draft", field(created, "/state"));

        ResponseEntity<String> attested = post(id + "/attest", 1, "{\"note\":\"synthetic attestation\"}");
        assertEquals(HttpStatus.OK, attested.getStatusCode(), attested.getBody());
        assertEquals("attested", field(attested, "/state"));

        ResponseEntity<String> transmitted = transmit(id, 2, "E_PRESCRIBE", "ambiguous");
        assertEquals(HttpStatus.OK, transmitted.getStatusCode(), transmitted.getBody());
        assertEquals("ambiguous", field(transmitted, "/state"));
        assertEquals("false", field(transmitted, "/retryAllowed"),
                "an unverified ambiguous send must not authorize a retry");
        String sid = field(transmitted, "/submissionId");
        assertNotNull(sid);
        return sid;
    }

    /** Assert that a refused command left the state, version and send facts untouched. */
    private void assertUnchanged(String id, String state, int version, int sendFacts, int verifications) {
        ResponseEntity<String> view = get(ORG_A, PRESCRIBER, id);
        assertEquals(state, field(view, "/prescription/state"));
        assertEquals(String.valueOf(version), field(view, "/prescription/version"));
        assertEquals(sendFacts, countEventsOfType(ORG_A, id, "submitted"));
        assertEquals(verifications, countEventsOfType(ORG_A, id, "partner_status_verified"));
        assertEquals(1, countSubmissionRows(ORG_A, id));
    }

    private ResponseEntity<String> create(String id) {
        String body = "{\"prescriptionId\":\"" + id + "\",\"patientId\":\"" + patientId(ORG_A)
                + "\",\"medication\":\"amoxicillin\",\"strength\":\"500 mg\","
                + "\"dose\":\"1 capsule\",\"route\":\"oral\",\"frequency\":\"twice daily\","
                + "\"quantity\":30,\"prescriberSubject\":\"" + PRESCRIBER + "\"}";
        return send(HttpMethod.POST, "/api/v1/prescriptions", body, PRESCRIBER, ORG_A);
    }

    private ResponseEntity<String> transmit(String id, int version, String channel, String outcome) {
        return post(id + "/transmit", version,
                "{\"channel\":\"" + channel + "\",\"outcome\":\"" + outcome + "\"}");
    }

    private ResponseEntity<String> verify(String org, String subject, String id, int version, String sid,
                                          String status) {
        return post(org, subject, id + "/verify-partner-status", version,
                "{\"submissionId\":\"" + sid + "\",\"status\":\"" + status + "\"}");
    }

    private ResponseEntity<String> retry(String org, String subject, String id, int version, String sid) {
        return post(org, subject, id + "/retry", version,
                "{\"submissionId\":\"" + sid + "\",\"outcome\":\"submitted\"}");
    }

    private ResponseEntity<String> post(String path, int version, String body) {
        return post(ORG_A, PRESCRIBER, path, version, body);
    }

    private ResponseEntity<String> post(String org, String subject, String path, int version, String body) {
        return send(HttpMethod.POST, "/api/v1/prescriptions/" + path, body, subject, org,
                Map.of("If-Match", String.valueOf(version)));
    }

    private ResponseEntity<String> get(String org, String subject, String id) {
        return send(HttpMethod.GET, "/api/v1/prescriptions/" + id, null, subject, org);
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization) {
        return send(method, uri, body, subject, organization, Map.of());
    }

    private ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject,
                                        String organization, Map<String, String> extra) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.setContentType(MediaType.APPLICATION_JSON);
        extra.forEach(headers::set);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private static String field(ResponseEntity<String> response, String pointer) {
        JsonNode value = json(response).at(pointer);
        assertFalse(value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
        return value.isNull() ? null : value.asText();
    }

    private static String code(ResponseEntity<String> response) {
        JsonNode body = json(response);
        JsonNode code = body.has("code") ? body.get("code") : body.at("/error/code");
        return code.isMissingNode() ? "" : code.asText();
    }

    private static JsonNode json(ResponseEntity<String> response) {
        try {
            return MAPPER.readTree(response.getBody());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, PRESCRIBER);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next());
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int countSubmissionRows(String org, String prescriptionId) {
        return count(org, "SELECT COUNT(*) FROM public.rx02_submission WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid)");
    }

    private static int countEventsOfType(String org, String prescriptionId, String type) {
        return count(org, "SELECT COUNT(*) FROM public.rx02_event WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid) AND event_type = '" + type + "'");
    }

    private static int count(String org, String sql) {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, PRESCRIBER);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void bind(Connection connection, String org, String subject) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, org);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    private static void seedOrganization(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
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

    private static void seedPolicy(Statement statement, String org, String actor, String key, String value)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }
}
