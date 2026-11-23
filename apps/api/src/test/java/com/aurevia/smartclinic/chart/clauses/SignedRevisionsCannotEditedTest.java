package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

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

import org.flywaydb.core.Flyway;

/**
 * CLN-05 clause 4 evidence at the HTTP entry point: a signed revision cannot be
 * edited.
 *
 * <p>The rule is implemented in the neighbouring clinical-notes slice, not in
 * chart.record: {@code ClinicalNoteService.updateDraft} checks authorization,
 * the read version and then {@code requireEditable}, which refuses a signed
 * revision with HTTP 409 and code {@code IMMUTABLE_REVISION}. The append-only
 * ledger in V15 makes a signed row immutable in storage as well. This class
 * proves the rule through the real {@code /api/v1/clinical-notes} routes; the
 * chart clause package and document paths are fixed by the story scope.</p>
 *
 * <p>Every assertion is an outcome: a status, a response field or the persisted
 * row under the owning tenant. Synthetic fixtures only; forced row level
 * security confines every row to its active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_chart_signed_it")
@ActiveProfiles("synthetic-auth")
class SignedRevisionsCannotEditedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_chart_signed_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String DRAFT_CONTENT = "Chief concern: synthetic chart pain";
    private static final String LATE_CONTENT = "Late content that must never be stored";

    @LocalServerPort
    int port;

    RestClient rest;

    /** Create the isolated database, apply production migrations and seed synthetic staff and patients. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_chart_signed_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_chart_signed_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedPatient(statement, ORG_A, "MRN-SIGNED-A", "Synthetic Signed Patient A");
            seedPatient(statement, ORG_B, "MRN-SIGNED-B", "Synthetic Signed Patient B");
        }
    }

    /** Drop the fixture database so a rerun starts from a clean migration state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_chart_signed_it WITH (FORCE)");
        }
    }

    /**
     * Seed one active member with one role in an organization.
     *
     * @param statement fixture statement with a privileged connection
     * @param org       tenant identifier
     * @param subject   synthetic subject used in requests
     * @param role      role key granting the clinical permission
     * @throws Exception when the fixture cannot be written
     */
    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
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
    }

    /**
     * Seed the synthetic patient that satisfies the patient-context check on signing.
     *
     * @param statement fixture statement with a privileged connection
     * @param org       owning tenant
     * @param mrn       synthetic medical record number
     * @param name      synthetic display name
     * @throws Exception when the fixture cannot be written
     */
    private static void seedPatient(Statement statement, String org, String mrn, String name)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'clinician-" + (ORG_A.equals(org) ? "a" : "b") + "'");
        // V7 requires exactly one of dob / dob_unknown_reason; a synthetic civil date
        // satisfies that append-only invariant before any note behaviour runs.
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                + " dob, language, contacts, identifiers, consents) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, '" + mrn + "', 'CLINIC', '"
                + name + "', '" + name.toLowerCase(java.util.Locale.ROOT) + "', '1990-05-20', 'en', "
                + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
    }

    /** Build the HTTP client against the randomly allocated application port. */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Send a request with the synthetic identity headers and an optional version precondition.
     *
     * @param method  HTTP method
     * @param uri     absolute request path
     * @param body    JSON body, or null for a bodyless request
     * @param subject synthetic acting subject, or null to omit it
     * @param org     active organization, or null to omit it
     * @param ifMatch quoted expected version, or null to omit it
     * @return the unchecked response so error statuses can be asserted
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
     * Create a synthetic draft note in the given organization.
     *
     * @param subject acting clinician
     * @param org     active organization
     * @return the created draft response
     */
    private ResponseEntity<String> create(String subject, String org) {
        String body = "{\"patientId\":\"" + PATIENT + "\",\"title\":\"Synthetic chart note\","
                + "\"content\":\"" + DRAFT_CONTENT + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"" + subject + "\"}";
        return request(HttpMethod.POST, "/api/v1/clinical-notes", body, subject, org, null);
    }

    /**
     * Sign a note at the version the caller read.
     *
     * @param subject     acting clinician
     * @param org         active organization
     * @param id          note identifier
     * @param version     version read by the caller
     * @param attestation explicit signer attestation
     * @return the sign response
     */
    private ResponseEntity<String> sign(String subject, String org, String id,
                                        String version, String attestation) {
        return request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + attestation + "\"}", subject, org, "\"" + version + "\"");
    }

    /**
     * Attempt to overwrite a note revision at the version the caller read.
     *
     * @param subject acting user
     * @param org     active organization
     * @param id      note identifier
     * @param version version read by the caller
     * @param content replacement content
     * @return the edit response
     */
    private ResponseEntity<String> edit(String subject, String org, String id,
                                        String version, String content) {
        String body = "{\"title\":\"Late edit\",\"content\":\"" + content + "\"}";
        return request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id, body,
                subject, org, "\"" + version + "\"");
    }

    /**
     * Read a scalar field from the flat JSON responses used by these tests.
     *
     * @param json response body
     * @param key  field name
     * @return the field value, or an empty string when it is absent
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
     * Snapshot the current revision exactly as it is stored in the owning tenant.
     *
     * @param id note identifier
     * @return a stable string of version, state, content, checksum, signer and signed time
     * @throws Exception when the tenant-guarded read fails
     */
    private String rowSnapshot(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT version, state, content, content_checksum, signer_subject, signed_at "
                            + "FROM public.clinical_note_revision WHERE id = CAST('" + id + "' AS uuid) "
                            + "ORDER BY version DESC LIMIT 1")) {
                assertTrue(result.next(), "the revision must exist in the owning tenant");
                return result.getInt("version") + "|" + result.getString("state") + "|"
                        + result.getString("content") + "|" + result.getString("content_checksum") + "|"
                        + result.getString("signer_subject") + "|" + result.getTimestamp("signed_at");
            }
        }
    }

    /**
     * Prove the rule: a version-valid edit of a signed revision is refused and leaves no trace.
     *
     * @throws Exception when the persisted row cannot be read
     */
    @Test
    void editingASignedNoteRevisionIsRefusedAndTheSignedRowIsUnchanged() throws Exception {
        ResponseEntity<String> created = create("clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals("draft", field(created.getBody(), "state"));

        ResponseEntity<String> signed = sign("clinician-a", ORG_A, id, "1",
                "I attest I reviewed this note");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("clinician-a", field(signed.getBody(), "signerSubject"));
        assertFalse(field(signed.getBody(), "signedAt").isEmpty());
        String signedChecksum = field(signed.getBody(), "contentChecksum");
        assertEquals(64, signedChecksum.length(), "the signed row must carry a SHA-256 checksum");

        String before = rowSnapshot(id);
        ResponseEntity<String> edit = edit("clinician-a", ORG_A, id, "2", LATE_CONTENT);
        assertEquals(HttpStatus.CONFLICT, edit.getStatusCode(), edit.getBody());
        assertEquals("IMMUTABLE_REVISION", field(edit.getBody(), "code"));

        assertEquals(before, rowSnapshot(id), "a refused edit must not change the signed row");
        ResponseEntity<String> reread = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A, null);
        assertEquals(HttpStatus.OK, reread.getStatusCode(), reread.getBody());
        assertTrue(reread.getBody().contains("\"signed\""));
        assertTrue(reread.getBody().contains(signedChecksum));
        assertFalse(reread.getBody().contains(LATE_CONTENT));
    }

    /**
     * Prove the role boundary: reception may neither sign nor edit a signed revision.
     *
     * @throws Exception when the persisted row cannot be read
     */
    @Test
    void receptionCannotSignOrEditASignedNoteRevision() throws Exception {
        ResponseEntity<String> created = create("clinician-a", ORG_A);
        String id = field(created.getBody(), "id");
        String beforeSigning = rowSnapshot(id);

        ResponseEntity<String> deniedSign = sign("reception-a", ORG_A, id, "1", "attested");
        assertEquals(HttpStatus.FORBIDDEN, deniedSign.getStatusCode(), deniedSign.getBody());
        assertEquals(beforeSigning, rowSnapshot(id), "a denied sign must leave the draft untouched");

        assertEquals(HttpStatus.OK,
                sign("clinician-a", ORG_A, id, "1", "attested").getStatusCode());
        String signed = rowSnapshot(id);

        ResponseEntity<String> deniedEdit = edit("reception-a", ORG_A, id, "2", LATE_CONTENT);
        assertEquals(HttpStatus.FORBIDDEN, deniedEdit.getStatusCode(), deniedEdit.getBody());
        assertEquals(signed, rowSnapshot(id), "a denied edit must leave the signed row untouched");
    }

    /**
     * Prove the tenant boundary: another organization can neither read nor edit the revision.
     *
     * @throws Exception when the persisted row cannot be read
     */
    @Test
    void anotherTenantCannotReadOrEditASignedNoteRevision() throws Exception {
        ResponseEntity<String> created = create("clinician-a", ORG_A);
        String id = field(created.getBody(), "id");
        assertEquals(HttpStatus.OK,
                sign("clinician-a", ORG_A, id, "1", "attested").getStatusCode());
        String before = rowSnapshot(id);

        ResponseEntity<String> foreignRead = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-b", ORG_B, null);
        assertEquals(HttpStatus.NOT_FOUND, foreignRead.getStatusCode(), foreignRead.getBody());
        assertEquals(HttpStatus.NOT_FOUND,
                edit("clinician-b", ORG_B, id, "2", LATE_CONTENT).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                sign("clinician-b", ORG_B, id, "2", "attested").getStatusCode());
        assertEquals(before, rowSnapshot(id), "foreign-tenant requests must not reach the row");
    }

    /**
     * Prove the precondition boundary: a stale read cannot edit the signed revision.
     *
     * @throws Exception when the persisted row cannot be read
     */
    @Test
    void staleVersionCannotEditASignedNoteRevision() throws Exception {
        ResponseEntity<String> created = create("clinician-a", ORG_A);
        String id = field(created.getBody(), "id");
        assertEquals(HttpStatus.OK,
                sign("clinician-a", ORG_A, id, "1", "attested").getStatusCode());
        String before = rowSnapshot(id);

        // The version read before signing is now stale. The precondition is checked
        // before the immutable-state guard, so this must report STALE_VERSION (412).
        ResponseEntity<String> stale = edit("clinician-a", ORG_A, id, "1", LATE_CONTENT);
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals(before, rowSnapshot(id));
    }

    /**
     * Prove the input boundary: a sign without a signer attestation is refused and cannot sign.
     *
     * @throws Exception when the persisted row cannot be read
     */
    @Test
    void signingWithoutSignerAttestationIsRefused() throws Exception {
        ResponseEntity<String> created = create("clinician-a", ORG_A);
        String id = field(created.getBody(), "id");
        String before = rowSnapshot(id);

        ResponseEntity<String> denied = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", "{}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(422, denied.getStatusCode().value(), denied.getBody());
        assertEquals("INVALID_ATTESTATION", field(denied.getBody(), "code"));
        assertEquals(before, rowSnapshot(id), "a refused sign must not append signed metadata");

        ResponseEntity<String> reread = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A, null);
        assertEquals("draft", field(reread.getBody(), "state"));
        assertFalse(reread.getBody().contains("\"signed\""));
    }
}
