package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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
 * CLN-05 clause 5 (API evidence): entered-in-error preserves the prior revision
 * and records the actor, reason and affected recipients.
 *
 * <p>Entered-in-error is an append-only correction of a signed note. The signed
 * original stays readable with its checksum, and the new revision carries the
 * superseded version plus provenance derived from the authenticated caller, not
 * from the request body. The negative cases show that a clerk, another tenant,
 * a stale precondition, malformed input and an unsigned draft are all refused
 * without appending a revision.</p>
 *
 * <p>Every request goes through the real {@code /api/v1/clinical-notes} entry
 * point against a fresh database created from the production Flyway migrations.
 * All subjects and the patient are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_entered_error_it")
@ActiveProfiles("synthetic-auth")
class EnteredErrorPreservesPriorRevisionRecordsActorTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_entered_error_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONTENT = "Chief concern: chest pain";

    @LocalServerPort
    int port;

    RestClient rest;

    /**
     * Create the isolated fixture database, apply the authoritative migrations and
     * seed synthetic memberships and one synthetic patient.
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_entered_error_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_entered_error_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician", PATIENT_ID);
            seed(statement, ORG_A, "clerk-a", "reception", null);
            seed(statement, ORG_B, "clinician-b", "clinician", null);
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_entered_error_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Seed a synthetic subject with an active membership and role in one tenant,
     * optionally with a synthetic patient visible to that tenant.
     *
     * <p>The membership needs an explicit valid_from: without it the effective
     * membership fold treats the row as inactive and the HTTP request is denied
     * with 403 before it reaches the clinical invariant under test.</p>
     *
     * @param statement connection statement in the fixture database
     * @param org       owning organization identifier
     * @param subject   synthetic authenticated subject
     * @param role      synthetic role key
     * @param patientId patient to seed, or {@code null} when none is needed
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
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-ENTERED-ERROR', 'CLINIC-A', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    /**
     * Issue an HTTP request and capture any status instead of throwing on errors.
     *
     * @param method    HTTP method
     * @param uri       path under the local server
     * @param body      serialized JSON body, or {@code null} for no body
     * @param subject   synthetic caller subject, or {@code null} when unauthenticated
     * @param org       active organization, or {@code null} when absent
     * @param ifMatch   version precondition, or {@code null} when absent
     * @return the full response including error statuses
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

    /**
     * Create a synthetic draft note owned by clinician-a in ORG A.
     *
     * @return the create response
     */
    private ResponseEntity<String> create() {
        String body = "{\"patientId\":\"" + PATIENT_ID + "\",\"title\":\"Visit note\","
                + "\"content\":\"" + CONTENT + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"clinician-a\"}";
        return request(HttpMethod.POST, "/api/v1/clinical-notes", body, "clinician-a", ORG_A, null);
    }

    /**
     * Create and sign a draft so the entered-in-error command has a signed source.
     *
     * @return the signed revision identifier
     */
    private String signedNoteId() {
        String id = field(create().getBody(), "id");
        ResponseEntity<String> signed = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("2", field(signed.getBody(), "version"));
        return id;
    }

    /**
     * Mark a note entered-in-error with the canonical synthetic reason and recipients.
     *
     * @param id      note identifier
     * @param subject calling subject
     * @param org     active organization
     * @param ifMatch version precondition
     * @return the command response
     */
    private ResponseEntity<String> enteredInError(String id, String subject, String org, String ifMatch) {
        return enteredInError(id, "wrong patient", "[\"lab-a\"]", subject, org, ifMatch);
    }

    /**
     * Mark a note entered-in-error with explicit reason and recipient inputs.
     *
     * @param id           note identifier
     * @param reason       recorded reason
     * @param recipients   JSON-array string of affected recipients
     * @param subject      calling subject
     * @param org          active organization
     * @param ifMatch      version precondition
     * @return the command response
     */
    private ResponseEntity<String> enteredInError(String id, String reason, String recipients,
                                                   String subject, String org, String ifMatch) {
        // affectedRecipients is a JSON string inside the JSON body; escaping the
        // inner quotes is what makes it reach NoteInputs.recipients as an array.
        String body = "{\"reason\":\"" + reason + "\",\"affectedRecipients\":\""
                + recipients.replace("\"", "\\\"") + "\"}";
        return request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/entered-in-error",
                body, subject, org, ifMatch);
    }

    /**
     * Read a simple scalar field from a JSON response.
     *
     * @param json parsed response text
     * @param key  field name
     * @return the field value, or an empty string when absent
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

    /**
     * Compute the canonical SHA-256 checksum of the synthetic note content.
     *
     * @param content note content bytes
     * @return lower-case hex checksum
     */
    private static String sha256(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Count ledger rows for one synthetic note in the owning tenant.
     *
     * <p>The migration owner is subject to FORCE RLS too, so the tenant must be
     * bound before reading the rows written inside the request transaction.</p>
     *
     * @param id      note identifier
     * @param state   optional state filter, or {@code null} for all revisions
     * @return number of visible rows
     * @throws Exception when the query fails
     */
    private static int revisionCount(String id, String state) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            String sql = "SELECT count(*) FROM public.clinical_note_revision "
                    + "WHERE id = CAST('" + id + "' AS uuid)"
                    + (state == null ? "" : " AND state = '" + state + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    /**
     * Prove the append-only correction: a signed v2 stays intact and a new v3
     * supersedes it while carrying the actor, reason and recipients.
     *
     * @throws Exception when the SQL assertions fail
     */
    @Test
    void enteredInErrorAppendsRevisionKeepingSignedOriginalAndRecordingActorReasonRecipients() throws Exception {
        String id = signedNoteId();

        ResponseEntity<String> result = enteredInError(id, "clinician-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.OK, result.getStatusCode(), result.getBody());
        assertEquals("entered_in_error", field(result.getBody(), "state"));
        assertEquals("3", field(result.getBody(), "version"));
        assertEquals("2", field(result.getBody(), "supersedesVersion"));
        assertEquals("wrong patient", field(result.getBody(), "enteredErrorReason"));
        assertEquals("clinician-a", field(result.getBody(), "enteredErrorActor"));
        assertTrue(result.getBody().contains("\"affectedRecipients\":\"[\\\"lab-a\\\"]\""),
                result.getBody());

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A, null);
        assertEquals(HttpStatus.OK, view.getStatusCode(), view.getBody());
        assertTrue(view.getBody().contains("\"signed\""), "the signed original must remain readable");
        assertTrue(view.getBody().contains("\"entered_in_error\""));
        assertTrue(view.getBody().contains(CONTENT));

        String checksum = sha256(CONTENT);
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT version, state, content_checksum, supersedes_version, entered_error_reason, "
                            + "entered_error_actor, affected_recipients::text "
                            + "FROM public.clinical_note_revision WHERE id = CAST('" + id + "' AS uuid) "
                            + "ORDER BY version")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("version"));
                assertEquals("draft", resultSet.getString("state"));
                assertEquals(checksum, resultSet.getString("content_checksum"));

                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("version"));
                assertEquals("signed", resultSet.getString("state"));
                assertEquals(checksum, resultSet.getString("content_checksum"));
                assertEquals(1, resultSet.getInt("supersedes_version"));

                assertTrue(resultSet.next());
                assertEquals(3, resultSet.getInt("version"));
                assertEquals("entered_in_error", resultSet.getString("state"));
                assertEquals(checksum, resultSet.getString("content_checksum"));
                assertEquals(2, resultSet.getInt("supersedes_version"));
                assertEquals("wrong patient", resultSet.getString("entered_error_reason"));
                assertEquals("clinician-a", resultSet.getString("entered_error_actor"));
                assertEquals("[\"lab-a\"]", resultSet.getString("affected_recipients"));
                assertFalse(resultSet.next(), "entered-in-error must append, not rewrite the ledger");
            }
        }
    }

    @Test
    void clerkCannotMarkNoteEnteredInError() throws Exception {
        String id = signedNoteId();
        ResponseEntity<String> denied = enteredInError(id, "clerk-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"));
        assertEquals(0, revisionCount(id, "entered_in_error"));
    }

    @Test
    void crossTenantClinicianCannotMarkNoteEnteredInError() throws Exception {
        String id = signedNoteId();
        ResponseEntity<String> denied = enteredInError(id, "clinician-b", ORG_B, "\"2\"");
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertEquals("NOT_FOUND", field(denied.getBody(), "code"));
        assertEquals(2, revisionCount(id, null), "the owning tenant's ledger must be unchanged");
        assertEquals(0, revisionCount(id, "entered_in_error"));
    }

    @Test
    void staleVersionCannotMarkNoteEnteredInError() throws Exception {
        String id = signedNoteId();
        ResponseEntity<String> stale = enteredInError(id, "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals(0, revisionCount(id, "entered_in_error"));
    }

    @Test
    void blankReasonAndMalformedRecipientsAreRefused() throws Exception {
        String id = signedNoteId();
        ResponseEntity<String> blank = enteredInError(id, "   ", "[]", "clinician-a", ORG_A, "\"2\"");
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());
        assertEquals("INVALID_REASON", field(blank.getBody(), "code"));

        ResponseEntity<String> malformed = enteredInError(id, "wrong patient", "lab-a",
                "clinician-a", ORG_A, "\"2\"");
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertEquals("INVALID_RECIPIENTS", field(malformed.getBody(), "code"));
        assertEquals(0, revisionCount(id, "entered_in_error"));
    }

    @Test
    void unsignedDraftCannotBeMarkedEnteredInError() throws Exception {
        String id = field(create().getBody(), "id");
        ResponseEntity<String> denied = enteredInError(id, "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.CONFLICT, denied.getStatusCode(), denied.getBody());
        assertEquals("INVALID_TRANSITION", field(denied.getBody(), "code"));
        assertEquals(1, revisionCount(id, null), "a refused draft correction must append nothing");
    }
}
