package com.aurevia.prescription.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
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
 * RX-02 clause 6 proof through the normal HTTP entry point: one electronic
 * prescription carries a single stable submission id that survives retries and
 * renewal and is refused by every downstream command when a different id is
 * supplied. Uses its own PostgreSQL database and synthetic fixtures only; no
 * patient data or credentials are used. Tagged {@code integration} so the fast
 * gate skips it and the integrator runs it against a real database.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_use_stable_submission_ids_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class UseStableSubmissionIdsTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_use_stable_submission_ids_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ATTEST = "{\"note\":\"synthetic attestation\"}";
    private static final String CANONICAL_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String WRONG_ID = "99999999-9999-4999-8999-999999999999";

    private RestClient rest;

    /** Point the HTTP client at the random port chosen for this test context. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate the synthetic database from the authoritative migrations and seed two bounded tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_use_stable_submission_ids_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_use_stable_submission_ids_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedOrganization(statement, ORG_A);
            seedOrganization(statement, ORG_B);
            seedMember(statement, ORG_A, "clinician-1111", "clinician");
            seedMember(statement, ORG_A, "other-1111", "clinician");
            seedMember(statement, ORG_A, "pharmacy-1111", "pharmacy_staff");
            seedMember(statement, ORG_A, "admin-1111", "tenant_admin");
            seedMember(statement, ORG_B, "clinician-2222", "clinician");
            // The prescriber is entitled and E_PRESCRIBE is approved in ORG_A;
            // ORG_B has no policies because its only role is a concealed cross-tenant read.
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.market", "US");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.entitlement.clinician-1111", "enabled");
            seedPolicy(statement, ORG_A, "admin-1111", "rx02.approved_channels", "E_PRESCRIBE");
        }
    }

    /** Drop the synthetic database so each run starts from a clean state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_use_stable_submission_ids_it WITH (FORCE)");
        }
    }

    @Test
    void electronicSubmissionKeepsOneStableIdAcrossRetryAndRenewal() {
        String org = ORG_A;
        String prescriber = "clinician-1111";
        String id = newId();

        assertEquals(HttpStatus.CREATED, create(org, prescriber, id, prescriber).getStatusCode());
        assertEquals(HttpStatus.OK, post(org, prescriber, id + "/attest", 1, ATTEST).getStatusCode());

        ResponseEntity<String> transmitted = transmit(org, prescriber, id, 2, "E_PRESCRIBE", "ambiguous");
        assertEquals(HttpStatus.OK, transmitted.getStatusCode(), transmitted.getBody());
        String sid = field(transmitted, "/submissionId");
        assertNotNull(sid, "an electronic transmission must carry a submission id");
        assertEquals(36, sid.length(), "the submission id must be a canonical UUID");
        assertTrue(sid.matches(CANONICAL_UUID), "the submission id must be a lowercase canonical UUID");
        assertEquals(sid.toLowerCase(Locale.ROOT), sid, "the submission id must be lowercase");
        assertEquals(sid, UUID.fromString(sid).toString(), "the submission id must round-trip as a UUID");
        assertEquals("ambiguous", field(transmitted, "/state"));
        assertEquals(1, countSubmissionRows(org, id), "transmit stamps exactly one submission row");
        assertEquals(sid, submissionRow(org, id, "submission_id"),
                "the ledger row must be bound to the returned submission id");
        assertEquals("E_PRESCRIBE", submissionRow(org, id, "channel"),
                "the ledger row must record the actual channel");

        ResponseEntity<String> verified = verify(org, prescriber, id, 3, sid, "absent");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("true", field(verified, "/retryAllowed"),
                "an absent partner status must authorize a retry");

        ResponseEntity<String> retried = retry(org, prescriber, id, 4, sid, "submitted");
        assertEquals(HttpStatus.OK, retried.getStatusCode(), retried.getBody());
        assertEquals(sid, field(retried, "/submissionId"), "the retry must reuse the stamped id");
        assertEquals("submitted", field(retried, "/state"));
        assertEquals(1, countSubmissionRows(org, id), "a retry must not create a second submission");
        assertEquals(2, countEventsOfType(org, id, "submitted"), "two send facts, one submission");

        ResponseEntity<String> renewed = post(org, prescriber, id + "/renew", 5,
                "{\"note\":\"synthetic renewal\"}");
        assertEquals(HttpStatus.OK, renewed.getStatusCode(), renewed.getBody());
        assertEquals(1, countSubmissionRows(org, id), "a renewal must not create a second submission");
        assertEquals(sid, field(renewed, "/submissionId"),
                "the prescription keeps its submission id after renewal");

        int submissionsCarryingId = 0;
        boolean verificationCarriesId = false;
        String renewalSubmission = null;
        for (JsonNode event : json(get(org, prescriber, id)).at("/events")) {
            String eventSubmission = event.at("/submissionId").asText();
            String eventType = event.at("/eventType").asText();
            if ("submitted".equals(eventType) && sid.equals(eventSubmission)) {
                submissionsCarryingId++;
            }
            if ("partner_status_verified".equals(eventType) && sid.equals(eventSubmission)) {
                verificationCarriesId = true;
            }
            if ("renewal".equals(eventType)) {
                renewalSubmission = eventSubmission;
            }
        }
        assertEquals(2, submissionsCarryingId, "both send facts carry the stable submission id");
        assertTrue(verificationCarriesId, "the verification fact carries the stable submission id");
        assertEquals(sid, renewalSubmission, "the renewal must link to the original submission id");
    }

    @Test
    void aDifferentValidSubmissionIdIsRefusedByEveryDownstreamCommand() {
        String org = ORG_A;
        String prescriber = "clinician-1111";
        String wrong = WRONG_ID;
        assertTrue(wrong.matches(CANONICAL_UUID), "the mismatched id must itself be a valid UUID");

        String confirmedId = newId();
        create(org, prescriber, confirmedId, prescriber);
        post(org, prescriber, confirmedId + "/attest", 1, ATTEST);
        ResponseEntity<String> submitted = transmit(org, prescriber, confirmedId, 2, "E_PRESCRIBE", "submitted");
        String sid = field(submitted, "/submissionId");
        assertNotNull(sid);
        assertNotEquals(sid, wrong, "the mismatch case must use a different id");

        assertRefused(confirm(org, prescriber, confirmedId, 3, wrong),
                HttpStatus.UNPROCESSABLE_CONTENT, "SUBMISSION_MISMATCH");
        assertEquals("submitted", field(get(org, prescriber, confirmedId), "/prescription/state"),
                "a refused confirmation must leave the prescription unchanged");
        assertEquals(HttpStatus.OK, confirm(org, prescriber, confirmedId, 3, sid).getStatusCode());
        assertRefused(pharmacy(org, "pharmacy-1111", confirmedId, 4, wrong, "accepted", "wrong id"),
                HttpStatus.UNPROCESSABLE_CONTENT, "SUBMISSION_MISMATCH");
        assertEquals(HttpStatus.OK, pharmacy(org, "pharmacy-1111", confirmedId, 4, sid,
                "accepted", "synthetic").getStatusCode());
        assertRefused(dispense(org, "pharmacy-1111", confirmedId, 5, wrong, "wrong id"),
                HttpStatus.UNPROCESSABLE_CONTENT, "SUBMISSION_MISMATCH");

        String ambiguousId = newId();
        create(org, prescriber, ambiguousId, prescriber);
        post(org, prescriber, ambiguousId + "/attest", 1, ATTEST);
        ResponseEntity<String> ambiguous = transmit(org, prescriber, ambiguousId, 2, "E_PRESCRIBE", "ambiguous");
        String ambiguousSid = field(ambiguous, "/submissionId");
        assertNotNull(ambiguousSid);
        assertNotEquals(ambiguousSid, wrong, "the mismatch case must use a different id");
        assertRefused(verify(org, prescriber, ambiguousId, 3, wrong, "absent"),
                HttpStatus.UNPROCESSABLE_CONTENT, "SUBMISSION_MISMATCH");
        assertRefused(retry(org, prescriber, ambiguousId, 3, wrong, "submitted"),
                HttpStatus.UNPROCESSABLE_CONTENT, "SUBMISSION_MISMATCH");
        assertEquals(1, countSubmissionRows(org, ambiguousId),
                "a refused retry must not add a submission row");
    }

    @Test
    void retryByAnotherClinicianIsForbidden() {
        String org = ORG_A;
        String id = newId();
        create(org, "clinician-1111", id, "clinician-1111");
        post(org, "clinician-1111", id + "/attest", 1, ATTEST);
        ResponseEntity<String> transmitted = transmit(org, "clinician-1111", id, 2, "E_PRESCRIBE", "ambiguous");
        String sid = field(transmitted, "/submissionId");

        assertRefused(retry(org, "other-1111", id, 3, sid, "submitted"),
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertEquals(1, countSubmissionRows(org, id), "a denied retry must not create a submission");
    }

    @Test
    void aForeignTenantCannotRetryAnotherTenantsSubmission() {
        String id = newId();
        create(ORG_A, "clinician-1111", id, "clinician-1111");
        post(ORG_A, "clinician-1111", id + "/attest", 1, ATTEST);
        ResponseEntity<String> transmitted = transmit(ORG_A, "clinician-1111", id, 2, "E_PRESCRIBE", "ambiguous");
        String sid = field(transmitted, "/submissionId");

        assertRefused(retry(ORG_B, "clinician-2222", id, 3, sid, "submitted"),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertEquals(1, countSubmissionRows(ORG_A, id),
                "a cross-tenant retry must not touch the owner's submission");
    }

    @Test
    void aStaleRetryVersionIsRejectedWithoutResubmitting() {
        String org = ORG_A;
        String id = newId();
        create(org, "clinician-1111", id, "clinician-1111");
        post(org, "clinician-1111", id + "/attest", 1, ATTEST);
        ResponseEntity<String> transmitted = transmit(org, "clinician-1111", id, 2, "E_PRESCRIBE", "ambiguous");
        String sid = field(transmitted, "/submissionId");

        assertRefused(retry(org, "clinician-1111", id, 9, sid, "submitted"),
                HttpStatus.PRECONDITION_FAILED, "STALE_VERSION");
        assertEquals(1, countSubmissionRows(org, id), "a stale retry must not create a submission");
        assertEquals(1, countEventsOfType(org, id, "submitted"), "a stale retry must not resubmit");
    }

    // ---------- helpers ----------

    private ResponseEntity<String> create(String org, String subject, String id, String prescriber) {
        String body = "{\"prescriptionId\":\"" + id + "\",\"patientId\":\"" + patientId(org)
                + "\",\"medication\":\"amoxicillin\",\"strength\":\"500 mg\","
                + "\"dose\":\"1 capsule\",\"route\":\"oral\",\"frequency\":\"twice daily\","
                + "\"quantity\":30,\"prescriberSubject\":\"" + prescriber + "\"}";
        return send(HttpMethod.POST, "/api/v1/prescriptions", body, subject, org);
    }

    private ResponseEntity<String> transmit(String org, String subject, String id, int version,
                                            String channel, String outcome) {
        return post(org, subject, id + "/transmit", version,
                "{\"channel\":\"" + channel + "\",\"outcome\":\"" + outcome + "\"}");
    }

    private ResponseEntity<String> confirm(String org, String subject, String id, int version, String sid) {
        return post(org, subject, id + "/confirm-delivery", version,
                "{\"submissionId\":\"" + sid + "\"}");
    }

    private ResponseEntity<String> verify(String org, String subject, String id, int version, String sid,
                                          String status) {
        return post(org, subject, id + "/verify-partner-status", version,
                "{\"submissionId\":\"" + sid + "\",\"status\":\"" + status + "\"}");
    }

    private ResponseEntity<String> retry(String org, String subject, String id, int version, String sid,
                                         String outcome) {
        return post(org, subject, id + "/retry", version,
                "{\"submissionId\":\"" + sid + "\",\"outcome\":\"" + outcome + "\"}");
    }

    private ResponseEntity<String> pharmacy(String org, String subject, String id, int version, String sid,
                                            String outcome, String note) {
        return post(org, subject, id + "/pharmacy-response", version,
                "{\"submissionId\":\"" + sid + "\",\"outcome\":\"" + outcome
                        + "\",\"note\":\"" + note + "\"}");
    }

    private ResponseEntity<String> dispense(String org, String subject, String id, int version, String sid,
                                            String note) {
        return post(org, subject, id + "/dispensing", version,
                "{\"submissionId\":\"" + sid + "\",\"note\":\"" + note + "\"}");
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
        JsonNode code = json(response).at("/code");
        assertFalse(code.isMissingNode(), "missing error code in " + response.getBody());
        return code.asText();
    }

    private static void assertRefused(ResponseEntity<String> response, HttpStatus status, String expectedCode) {
        assertEquals(status, response.getStatusCode(), response.getBody());
        assertEquals(expectedCode, code(response), response.getBody());
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

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static String patientId(String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(result.next(), "the seeded patient must exist");
                return result.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int countSubmissionRows(String org, String prescriptionId) {
        return count("SELECT COUNT(*) FROM public.prescription_submission WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid)", org);
    }

    private static int countEventsOfType(String org, String prescriptionId, String type) {
        return count("SELECT COUNT(*) FROM public.prescription_event WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid) AND event_type = '" + type + "'", org);
    }

    private static int count(String sql, String org) {
        return Integer.parseInt(scalar(sql, org));
    }

    private static String submissionRow(String org, String prescriptionId, String column) {
        return scalar("SELECT " + column + "::text FROM public.prescription_submission WHERE prescription_id = CAST('"
                + prescriptionId + "' AS uuid)", org);
    }

    private static String scalar(String sql, String org) {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, org, "clinician-" + suffix(org));
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next(), "the scalar query must return one row");
                return result.getString(1);
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
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Synthetic " + suffix(org) + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', 'bootstrap-" + suffix(org) + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', 'synthetic patient', "
                + "'1990-05-20')");
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + suffix(org) + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
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
