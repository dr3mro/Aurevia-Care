package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * CLN-05 clause 3 (API evidence): signing a clinical draft saves the signer, the
 * clinical effective time, the signed time, the content checksum and the template
 * version on an immutable revision, and refuses writes that would break that rule.
 *
 * <p>Every request goes through the real {@code /api/v1/clinical-notes} entry
 * point against a fresh PostgreSQL database created from the production Flyway
 * migrations. The response and a separate tenant-scoped JDBC readback must agree:
 * an HTTP status alone would not prove that the metadata was saved. The signer is
 * deliberately a different subject from the clinical author, so a response that
 * echoed the author instead of the authenticated signer cannot pass.</p>
 *
 * <p>Fixtures are synthetic: two synthetic organizations, synthetic staff
 * subjects and one synthetic patient. No real identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_clause3_it")
@ActiveProfiles("synthetic-auth")
class SaveSignerEffectiveTimeSignedTimeContentTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_clause3_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    private static final String SIGNER = "clinician-a";
    private static final String AUTHOR = "clinician-b";
    private static final String CONTENT = "Chief concern: chest pain";
    private static final String EFFECTIVE_AT = "2020-01-01T00:00:00Z";
    private static final String ATTESTATION = "I attest I reviewed this note";

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_clause3_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln05_clause3_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, SIGNER, "clinician", PATIENT_A);
            seed(statement, ORG_A, AUTHOR, "clinician", null);
            seed(statement, ORG_A, "assistant-a", "clinical_assistant", null);
            seed(statement, ORG_A, "reception-a", "reception", null);
            seed(statement, ORG_B, "clinician-other", "clinician", null);
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_clause3_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @LocalServerPort
    int port;

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
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                + "AND u.id = m.user_id WHERE u.subject = '" + subject + "'");
        if (patientId != null) {
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                    + "dob, language, contacts, identifiers, consents) "
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-CLN05-CLAUSE3', 'CLINIC-A', "
                    + "'Synthetic Signing Patient', 'synthetic signing patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        return request(method, uri, body, subject, org, null);
    }

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

    private String createBody() {
        return "{\"patientId\":\"" + PATIENT_A + "\",\"title\":\"Visit note\","
                + "\"content\":\"" + CONTENT + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":3,"
                + "\"clinicalAuthorSubject\":\"" + AUTHOR + "\","
                + "\"effectiveAt\":\"" + EFFECTIVE_AT + "\"}";
    }

    private String createId() {
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/clinical-notes",
                createBody(), SIGNER, ORG_A);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        assertEquals("draft", field(created.getBody(), "state"));
        assertFalse(field(created.getBody(), "effectiveAt").isEmpty());
        assertEquals("3", field(created.getBody(), "templateVersion"));
        assertEquals(AUTHOR, field(created.getBody(), "clinicalAuthorSubject"));
        String id = field(created.getBody(), "id");
        assertFalse(id.isEmpty(), "create must return a note id");
        return id;
    }

    private ResponseEntity<String> sign(String id, String subject, String org,
                                        String version, String body) {
        return request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                body, subject, org, "\"" + version + "\"");
    }

    private ResponseEntity<String> sign(String id, String version) {
        return sign(id, SIGNER, ORG_A, version, "{\"attestation\":\"" + ATTESTATION + "\"}");
    }

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
     * Read both revisions back under the tenant context and assert that the signed
     * row carries server-derived metadata while the original draft stays unsigned.
     *
     * @param id               opaque note identifier
     * @param expectedSigner   authenticated subject that signed the draft
     * @param expectedChecksum SHA-256 of the stored content
     * @param notBefore        instant captured just before the sign command
     * @throws Exception when the database cannot be read
     */
    private static void assertPersistedSignature(String id, String expectedSigner,
                                                 String expectedChecksum, String notBefore)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            // The migration owner is subject to FORCE RLS too, so bind the tenant
            // before reading the rows written inside the request transaction.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT state, signer_subject, signed_at, effective_at, "
                            + "(signed_at >= effective_at) AS signed_after_effective, "
                            + "(effective_at = CAST('" + EFFECTIVE_AT + "' AS timestamptz)) "
                            + "AS effective_unchanged, "
                            + "(signed_at >= CAST('" + notBefore + "' AS timestamptz)) "
                            + "AS signed_not_early, content_checksum, template_version, "
                            + "clinical_author_subject, data_entry_subject, supersedes_version "
                            + "FROM public.clinical_note_revision "
                            + "WHERE id = CAST('" + id + "' AS uuid) AND version = 2")) {
                assertTrue(result.next(), "the signed revision must be persisted");
                assertEquals("signed", result.getString("state"));
                assertEquals(expectedSigner, result.getString("signer_subject"));
                assertNotNull(result.getString("signed_at"));
                assertTrue(result.getBoolean("signed_after_effective"));
                assertTrue(result.getBoolean("signed_not_early"));
                assertTrue(result.getBoolean("effective_unchanged"));
                assertEquals(expectedChecksum, result.getString("content_checksum"));
                assertEquals(3, result.getInt("template_version"));
                assertEquals(AUTHOR, result.getString("clinical_author_subject"));
                assertEquals(SIGNER, result.getString("data_entry_subject"));
                assertEquals(1, result.getInt("supersedes_version"));
                assertFalse(result.next(), "there is exactly one signed row for the note");
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT state, signer_subject, signed_at FROM public.clinical_note_revision "
                            + "WHERE id = CAST('" + id + "' AS uuid) AND version = 1")) {
                assertTrue(result.next(), "the original draft must remain in the ledger");
                assertEquals("draft", result.getString("state"));
                assertNull(result.getString("signer_subject"));
                assertNull(result.getString("signed_at"));
            }
        }
    }

    /**
     * Count stored revisions in a given state, or every revision when the state is
     * null. The read runs inside the tenant that owns the note.
     *
     * @param id    opaque note identifier
     * @param state state to filter, or null for all revisions
     * @return number of matching rows
     * @throws Exception when the database cannot be read
     */
    private static int countRows(String id, String state) throws Exception {
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
     * Assert that a refused command left the note as a single unsigned draft.
     *
     * @param id opaque note identifier
     * @throws Exception when the database cannot be read
     */
    private void assertStillDraft(String id) throws Exception {
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, SIGNER, ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode(), view.getBody());
        assertEquals("draft", field(view.getBody(), "state"));
        assertEquals("1", field(view.getBody(), "version"));
        assertEquals(0, countRows(id, "signed"));
        assertEquals(1, countRows(id, "draft"));
    }

    @Test
    void signingPersistsSignerEffectiveTimeSignedTimeChecksumAndTemplateVersion() throws Exception {
        String id = createId();
        String notBefore = Instant.now().minusSeconds(2).toString();
        ResponseEntity<String> signed = sign(id, "1");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        String body = signed.getBody();
        assertEquals("signed", field(body, "state"));
        assertEquals("2", field(body, "version"));
        assertEquals("1", field(body, "supersedesVersion"));
        assertEquals(SIGNER, field(body, "signerSubject"));
        assertNotEquals(AUTHOR, field(body, "signerSubject"),
                "the signer is the authenticated clinician, not the clinical author");
        assertEquals(AUTHOR, field(body, "clinicalAuthorSubject"));
        assertEquals(SIGNER, field(body, "dataEntrySubject"));
        assertEquals(ATTESTATION, field(body, "attestation"));
        String signedAt = field(body, "signedAt");
        assertFalse(signedAt.isEmpty());
        assertFalse("null".equals(signedAt));
        String effectiveAt = field(body, "effectiveAt");
        assertFalse(effectiveAt.isEmpty());
        assertFalse("null".equals(effectiveAt));
        assertEquals(sha256(CONTENT), field(body, "contentChecksum"));
        assertEquals("3", field(body, "templateVersion"));

        // Instants are compared as timestamptz in PostgreSQL: the JSON render is
        // the driver's text form, whose layout is not a stable ISO-8601 string.
        assertPersistedSignature(id, SIGNER, sha256(CONTENT), notBefore);

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, SIGNER, ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode(), view.getBody());
        assertTrue(view.getBody().contains("\"draft\""),
                "the original draft must remain in the append-only history");
        assertTrue(view.getBody().contains("\"signed\""));
    }

    @Test
    void signingByUnauthorisedRoleIsRefused() throws Exception {
        String id = createId();
        String body = "{\"attestation\":\"" + ATTESTATION + "\"}";
        ResponseEntity<String> assistant = sign(id, "assistant-a", ORG_A, "1", body);
        assertEquals(HttpStatus.FORBIDDEN, assistant.getStatusCode(), assistant.getBody());
        assertEquals("FORBIDDEN", field(assistant.getBody(), "code"));
        ResponseEntity<String> reception = sign(id, "reception-a", ORG_A, "1", body);
        assertEquals(HttpStatus.FORBIDDEN, reception.getStatusCode(), reception.getBody());
        assertEquals("FORBIDDEN", field(reception.getBody(), "code"));
        assertStillDraft(id);
    }

    @Test
    void crossTenantCannotSeeOrSignTheDraft() throws Exception {
        String id = createId();
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-other", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, view.getStatusCode(), view.getBody());
        assertEquals("NOT_FOUND", field(view.getBody(), "code"));
        ResponseEntity<String> signed = sign(id, "clinician-other", ORG_B, "1",
                "{\"attestation\":\"" + ATTESTATION + "\"}");
        assertEquals(HttpStatus.NOT_FOUND, signed.getStatusCode(), signed.getBody());
        assertEquals("NOT_FOUND", field(signed.getBody(), "code"));
        assertStillDraft(id);
    }

    @Test
    void staleVersionCannotSignTheDraft() throws Exception {
        String id = createId();
        ResponseEntity<String> stale = sign(id, "2");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertStillDraft(id);
    }

    @Test
    void missingAttestationCannotSignTheDraft() throws Exception {
        String id = createId();
        ResponseEntity<String> missing = sign(id, SIGNER, ORG_A, "1", "{}");
        assertEquals(422, missing.getStatusCode().value(), missing.getBody());
        assertEquals("INVALID_ATTESTATION", field(missing.getBody(), "code"));
        assertStillDraft(id);
    }

    @Test
    void signedRevisionCannotBeSignedAgainOrEdited() throws Exception {
        String id = createId();
        assertEquals(HttpStatus.OK, sign(id, "1").getStatusCode());
        ResponseEntity<String> again = sign(id, "2");
        assertEquals(HttpStatus.CONFLICT, again.getStatusCode(), again.getBody());
        assertEquals("INVALID_TRANSITION", field(again.getBody(), "code"));
        ResponseEntity<String> edited = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                "{\"title\":\"Late edit\",\"content\":\"Late content\"}", SIGNER, ORG_A, "\"2\"");
        assertEquals(HttpStatus.CONFLICT, edited.getStatusCode(), edited.getBody());
        assertEquals("IMMUTABLE_REVISION", field(edited.getBody(), "code"));
        assertEquals(2, countRows(id, null));
    }

    @Test
    void clientSuppliedSignatureMetadataIsIgnored() throws Exception {
        String id = createId();
        // SignRequest has only attestation; extra JSON keys must never become an
        // authoritative signer, time, checksum or template version. Each spoofed
        // value differs from the server-derived value so this assertion can fail.
        String spoofedChecksum = "0".repeat(64);
        String notBefore = Instant.now().minusSeconds(2).toString();
        String body = "{\"attestation\":\"" + ATTESTATION + "\","
                + "\"signerSubject\":\"" + AUTHOR + "\","
                + "\"signedAt\":\"2000-01-01T00:00:00Z\","
                + "\"contentChecksum\":\"" + spoofedChecksum + "\","
                + "\"templateVersion\":99}";
        ResponseEntity<String> signed = sign(id, SIGNER, ORG_A, "1", body);
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals(SIGNER, field(signed.getBody(), "signerSubject"));
        assertEquals("3", field(signed.getBody(), "templateVersion"));
        assertEquals(sha256(CONTENT), field(signed.getBody(), "contentChecksum"));
        String signedAt = field(signed.getBody(), "signedAt");
        assertFalse(signedAt.isEmpty());
        assertFalse("null".equals(signedAt));
        assertFalse(signedAt.startsWith("2000"));
        assertFalse(signed.getBody().contains(spoofedChecksum));
        assertPersistedSignature(id, SIGNER, sha256(CONTENT), notBefore);
    }
}
