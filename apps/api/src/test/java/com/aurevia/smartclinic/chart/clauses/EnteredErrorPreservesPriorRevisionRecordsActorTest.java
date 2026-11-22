package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * CLN-05 clause 5 API proof for the chart's clinical-note ledger: marking a signed
 * revision entered-in-error must append a new revision that links to (and does
 * not alter) the prior signed revision, and must record the actor, the reason and
 * the affected recipients. The actor is stamped by the database from the
 * request's transaction-local identity, never accepted from the request body.
 * The positive test signs as one clinician and records the error as a second,
 * distinct clinician, so a row that merely copied the signer's identity fails.
 *
 * <p>Every fixture is synthetic. This class proves only this clause; the wider
 * draft-to-sign and amendment lifecycle is covered by its owning tests.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_chart_clause05_it")
@ActiveProfiles("synthetic-auth")
class EnteredErrorPreservesPriorRevisionRecordsActorTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_chart_clause05_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_chart_clause05_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_chart_clause05_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician", PATIENT);
            seed(statement, ORG_A, "clinician-c", "clinician", null);
            seed(statement, ORG_A, "clerk-a", "reception", null);
            seed(statement, ORG_B, "clinician-b", "clinician", PATIENT);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_chart_clause05_it WITH (FORCE)");
        }
    }

    /**
     * Seed one synthetic organization, subject, role assignment and optional patient.
     *
     * @param statement  statement on the freshly migrated test database
     * @param org        organization identifier
     * @param subject    synthetic user subject
     * @param role       role granted to the subject in that organization
     * @param patientId  optional patient visible in that organization
     * @throws Exception when a seed statement fails
     */
    private static void seed(Statement statement, String org, String subject, String role,
                             String patientId) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status, valid_from) "
                + "SELECT '" + org + "', id, 'active', '2020-01-01T00:00:00Z'::timestamptz "
                + "FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
        if (patientId != null) {
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                    + " dob, language, contacts, identifiers, consents) "
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-CHART-CLN05', 'CLINIC-A', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    /**
     * Send one synthetic API request without a version precondition.
     *
     * @param method  HTTP method
     * @param uri     request path
     * @param body    JSON body, or {@code null} for a request without one
     * @param subject synthetic subject header, or {@code null}
     * @param org     active organization header, or {@code null}
     * @return the response with its body
     */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        return request(method, uri, body, subject, org, null);
    }

    /**
     * Send one synthetic API request, capturing any status so error bodies can be asserted.
     *
     * @param method   HTTP method
     * @param uri      request path
     * @param body     JSON body, or {@code null} for a request without one
     * @param subject  synthetic subject header, or {@code null}
     * @param org      active organization header, or {@code null}
     * @param ifMatch  version precondition, or {@code null}
     * @return the response with its body
     */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        if (ifMatch != null) {
            headers.set("If-Match", ifMatch);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** @return the body used to create a synthetic signed-note draft. */
    private String createBody() {
        return "{\"patientId\":\"" + PATIENT + "\",\"title\":\"Visit note\","
                + "\"content\":\"Chief concern: chest pain\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"clinician-a\"}";
    }

    /**
     * Create a draft in organization A.
     *
     * @return the create response
     */
    private ResponseEntity<String> createDraft() {
        return request(HttpMethod.POST, "/api/v1/clinical-notes", createBody(), "clinician-a", ORG_A, null);
    }

    /**
     * Create and sign a draft, returning the note identifier.
     *
     * @return the identifier of the signed note
     */
    private String signedNoteId() {
        String id = field(createDraft().getBody(), "id");
        ResponseEntity<String> signed = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"I attest I reviewed this note\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        return id;
    }

    /**
     * Read one scalar field out of a flat JSON response body.
     *
     * @param json source JSON
     * @param key  field name
     * @return the field text, or an empty string when absent
     */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (start < json.length() && json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    /** @return the expected SHA-256 of the synthetic note content. */
    private static String contentChecksum() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest("Chief concern: chest pain".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Assert the persisted revision chain directly, so "preserved" means the
     * earlier signed row is still present and untouched, not merely hidden in a
     * rendered view.
     *
     * @param id note identifier
     * @throws Exception when the database assertion fails
     */
    private static void assertStoredChain(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT version, state, data_entry_subject, supersedes_version, entered_error_reason, "
                            + "entered_error_actor, affected_recipients::text FROM public.clinical_note_revision "
                            + "WHERE id = '" + id + "' ORDER BY version")) {
                assertTrue(rows.next(), "the draft revision must remain");
                assertEquals(1, rows.getInt("version"));
                assertEquals("draft", rows.getString("state"));
                assertTrue(rows.next(), "the signed revision must remain");
                assertEquals(2, rows.getInt("version"));
                assertEquals("signed", rows.getString("state"));
                assertEquals("clinician-a", rows.getString("data_entry_subject"));
                assertNull(rows.getString("entered_error_reason"),
                        "the prior signed revision must not be edited");
                assertNull(rows.getString("entered_error_actor"));
                assertTrue(rows.next(), "an entered-in-error revision must be appended");
                assertEquals(3, rows.getInt("version"));
                assertEquals("entered_in_error", rows.getString("state"));
                assertEquals("clinician-c", rows.getString("data_entry_subject"));
                assertEquals(2, rows.getInt("supersedes_version"));
                assertEquals("wrong patient", rows.getString("entered_error_reason"));
                assertEquals("clinician-c", rows.getString("entered_error_actor"),
                        "the actor is the current request subject, not the prior signer");
                assertTrue(rows.getString("affected_recipients").contains("lab-a"));
                assertTrue(rows.getString("affected_recipients").contains("clinic-b"));
                assertFalse(rows.next(), "only three revisions should exist");
            }
        }
    }

    /**
     * Positive proof: the signed revision survives unchanged while a new
     * entered-in-error revision, recorded by a clinician other than the signer,
     * carries that actor, the reason and the recipients.
     *
     * @throws Exception when the stored-chain assertion fails
     */
    @Test
    void enteredInErrorAppendsASupersedingRevisionAndRecordsActorReasonAndRecipients() throws Exception {
        String id = signedNoteId();
        String body = "{\"reason\":\"wrong patient\","
                + "\"affectedRecipients\":\"[\\\"lab-a\\\",\\\"clinic-b\\\"]\"}";
        ResponseEntity<String> result = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                body, "clinician-c", ORG_A, "\"2\"");
        assertEquals(HttpStatus.OK, result.getStatusCode(), result.getBody());
        assertEquals("entered_in_error", field(result.getBody(), "state"));
        assertEquals("3", field(result.getBody(), "version"));
        assertEquals("2", field(result.getBody(), "supersedesVersion"));
        assertEquals("wrong patient", field(result.getBody(), "enteredErrorReason"));
        assertEquals("clinician-c", field(result.getBody(), "enteredErrorActor"));
        assertTrue(result.getBody().contains("lab-a"));
        assertTrue(result.getBody().contains("clinic-b"));
        // The content and its checksum are carried forward, not rewritten.
        assertTrue(result.getBody().contains("Chief concern: chest pain"));
        assertEquals(contentChecksum(), field(result.getBody(), "contentChecksum"));

        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertTrue(view.getBody().contains("\"signed\""), "the signed original must remain readable");
        assertTrue(view.getBody().contains("\"entered_in_error\""));
        assertStoredChain(id);
    }

    /**
     * Negative proof: an unsigned draft is not a lawful source for this clause.
     */
    @Test
    void enteredInErrorRefusesAnUnsignedDraft() {
        String id = field(createDraft().getBody(), "id");
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                "{\"reason\":\"wrong patient\",\"affectedRecipients\":\"[\\\"lab-a\\\"]\"}",
                "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", field(refused.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertFalse(view.getBody().contains("entered_in_error"),
                "a refused command must not append a revision");
    }

    /**
     * Negative proof: a stale version may not supersede the current revision.
     */
    @Test
    void enteredInErrorRefusesAStaleVersion() {
        String id = signedNoteId();
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                "{\"reason\":\"wrong patient\",\"affectedRecipients\":\"[\\\"lab-a\\\"]\"}",
                "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, refused.getStatusCode(), refused.getBody());
        assertEquals("STALE_VERSION", field(refused.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertFalse(view.getBody().contains("entered_in_error"));
    }

    /**
     * Negative proof: a reason is mandatory, so a blank one is refused before any write.
     */
    @Test
    void enteredInErrorRefusesABlankReason() {
        String id = signedNoteId();
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                "{\"reason\":\"  \",\"affectedRecipients\":\"[]\"}",
                "clinician-a", ORG_A, "\"2\"");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_REASON", field(refused.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertFalse(view.getBody().contains("entered_in_error"));
    }

    /**
     * Negative proof: reception lacks the clinical permission to mark a note entered-in-error.
     */
    @Test
    void enteredInErrorRefusesAReceptionRole() {
        String id = signedNoteId();
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                "{\"reason\":\"wrong patient\",\"affectedRecipients\":\"[\\\"lab-a\\\"]\"}",
                "clerk-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("FORBIDDEN", field(refused.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertFalse(view.getBody().contains("entered_in_error"),
                "a denied command must not append a revision");
    }

    /**
     * Negative proof: a note in another tenant is concealed, not corrected.
     */
    @Test
    void enteredInErrorRefusesAnotherTenant() {
        String id = signedNoteId();
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                "{\"reason\":\"wrong patient\",\"affectedRecipients\":\"[\\\"lab-a\\\"]\"}",
                "clinician-b", ORG_B, "\"2\"");
        assertEquals(HttpStatus.NOT_FOUND, refused.getStatusCode(), refused.getBody());
        assertEquals("NOT_FOUND", field(refused.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertFalse(view.getBody().contains("entered_in_error"),
                "a concealed cross-tenant command must not append a revision");
    }

    /**
     * Negative proof: recipients must be a bounded JSON array before a revision is appended.
     */
    @Test
    void enteredInErrorRefusesMalformedRecipients() {
        String id = signedNoteId();
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                "{\"reason\":\"wrong patient\",\"affectedRecipients\":\"{\\\"a\\\":1}\"}",
                "clinician-a", ORG_A, "\"2\"");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_RECIPIENTS", field(refused.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertFalse(view.getBody().contains("entered_in_error"));
    }
}
