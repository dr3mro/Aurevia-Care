package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * CLN-05 clause 3 acceptance: signing a draft saves the signer, the effective
 * time, the signed time, the content checksum and the template version on the
 * new immutable revision.
 *
 * <p>This class proves only that clause through the real HTTP entry point and a
 * real PostgreSQL revision ledger: a clinician signs a draft and every one of
 * the five facts is asserted on the returned revision and on the persisted row.
 * Two negative tests show that a caller without sign permission and a stale
 * version are both refused, so no sign metadata is saved for them. All records
 * are synthetic. The wider state machine (amendments, addenda, entered-in-error)
 * belongs to other clauses and is not repeated here.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_signer_it")
@ActiveProfiles("synthetic-auth")
class SaveSignerEffectiveTimeSignedTimeContentTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CONTENT = "Chief concern: chest pain";
    private static final String EFFECTIVE_AT = "2026-01-15T09:30:00Z";
    private static final String ATTESTATION = "I attest I reviewed this note";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_signer_it";
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_signer_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln05_signer_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, "clinician-a", "clinician");
            seedUser(statement, "clerk-a", "reception");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, "
                    + " dob, language, contacts, identifiers, consents) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-CLN05-SIGN', 'CLINIC-A', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_signer_it WITH (FORCE)");
        }
    }

    private static void seedUser(Statement statement, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + ORG_A + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + ORG_A + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + ORG_A + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status, valid_from) "
                + "SELECT '" + ORG_A + "', id, 'active', '2020-01-01T00:00:00Z'::timestamptz "
                + "FROM public.user_identity WHERE subject = '" + subject + "' AND organization_id = '" + ORG_A + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + ORG_A + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + ORG_A + "'");
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

    private ResponseEntity<String> createDraft() {
        String body = "{\"patientId\":\"" + PATIENT + "\",\"title\":\"Visit note\","
                + "\"content\":\"" + CONTENT + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":3,"
                + "\"clinicalAuthorSubject\":\"clinician-a\","
                + "\"effectiveAt\":\"" + EFFECTIVE_AT + "\"}";
        return request(HttpMethod.POST, "/api/v1/clinical-notes", body, "clinician-a", ORG_A, null);
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
     * Assert the five signed facts are present on the persisted revision row,
     * not just in the HTTP response. The forced-RLS ledger is read as the tenant.
     *
     * @param id opaque note identifier
     * @throws Exception when the ledger cannot be queried
     */
    private static void assertSignMetadataPersisted(String id) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            var results = statement.executeQuery(
                    "SELECT signer_subject, signed_at, effective_at, content_checksum, template_version "
                    + "FROM public.clinical_note_revision WHERE id = '" + id + "' AND state = 'signed'");
            assertTrue(results.next(), "the signed revision must be persisted");
            assertEquals("clinician-a", results.getString("signer_subject"));
            assertFalse(results.getString("signed_at").isBlank());
            assertEquals(java.time.Instant.parse(EFFECTIVE_AT),
                    results.getObject("effective_at", java.time.OffsetDateTime.class).toInstant());
            assertEquals(sha256(CONTENT), results.getString("content_checksum"));
            assertEquals(3, results.getInt("template_version"));
        }
    }

    @Test
    void signingSavesSignerEffectiveTimeSignedTimeChecksumAndTemplateVersion() throws Exception {
        ResponseEntity<String> draft = createDraft();
        assertEquals(HttpStatus.OK, draft.getStatusCode(), draft.getBody());
        String id = field(draft.getBody(), "id");
        String draftEffectiveAt = field(draft.getBody(), "effectiveAt");
        assertFalse(draftEffectiveAt.isEmpty(), "the draft must carry an effective time to preserve");

        ResponseEntity<String> signed = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("clinician-a", field(signed.getBody(), "signerSubject"));
        assertEquals(ATTESTATION, field(signed.getBody(), "attestation"));
        assertFalse(field(signed.getBody(), "signedAt").isEmpty(), "the signed time must be saved");
        assertEquals(draftEffectiveAt, field(signed.getBody(), "effectiveAt"));
        assertEquals("3", field(signed.getBody(), "templateVersion"));
        assertEquals(sha256(CONTENT), field(signed.getBody(), "contentChecksum"));

        assertSignMetadataPersisted(id);
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(view.getBody().contains("\"draft\""), "the original draft must remain in the ledger");
        assertTrue(view.getBody().contains("\"signed\""), "the signed revision must remain in the ledger");
    }

    @Test
    void signingIsRefusedForRoleWithoutSignPermission() {
        String id = field(createDraft().getBody(), "id");
        ResponseEntity<String> denied = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clerk-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals("FORBIDDEN", field(denied.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertEquals("draft", field(view.getBody(), "state"),
                "a caller without sign permission must leave the note unsigned");
    }

    @Test
    void signingWithStaleVersionIsRefused() {
        String id = field(createDraft().getBody(), "id");
        ResponseEntity<String> edited = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                "{\"title\":\"Revised\",\"content\":\"Revised content\"}",
                "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, edited.getStatusCode(), edited.getBody());
        assertEquals("2", field(edited.getBody(), "version"));

        ResponseEntity<String> stale = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertEquals("draft", field(view.getBody(), "state"),
                "a stale sign must not save sign metadata on any revision");
    }
}
