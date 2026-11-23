package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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
 * CLN-05 clause 1 (API evidence): draft to signed, with separate amendment drafts
 * and signed addenda.
 *
 * <p>The positive test drives the real HTTP entry point against PostgreSQL and
 * asserts the whole lifecycle: an original draft is signed with an explicit
 * attestation, a separate amendment draft is opened from that signed revision,
 * and the amendment draft is signed as an addendum. Every transition appends a
 * new immutable revision, so the original remains in the readable history and
 * the addendum links to the amendment draft it supersedes. The negative tests
 * assert the rule refuses what it must refuse: a role without sign permission,
 * a stale version, an attempt to sign an amendment draft as an original, a
 * signature without attestation, and a caller in another tenant.</p>
 *
 * <p>All requests go through the real {@code /api/v1/clinical-notes} entry point
 * against a throwaway database created from the production Flyway migrations.
 * Fixtures are synthetic: fake organizations, subjects and patient; no real
 * identity or patient data is used. Forced row-level security confines every
 * row to the active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_c01_it")
@ActiveProfiles("synthetic-auth")
class DraftSignedSeparateAmendmentDraftsSignedAddendaTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_c01_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONTENT = "Chief concern: chest pain";
    private static final String ATTESTATION = "I attest I reviewed this note";

    RestClient rest;

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_c01_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln05_c01_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician", PATIENT);
            seed(statement, ORG_A, "clerk-a", "reception", null);
            seed(statement, ORG_B, "clinician-b", "clinician", PATIENT);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_c01_it WITH (FORCE)");
        }
    }

    /**
     * Seed one active organization member and, optionally, one synthetic patient.
     * The actor is set to the member being seeded before its patient is inserted,
     * so the patient registration trigger finds an authenticated user of that tenant.
     *
     * @param statement  statement on the migrated synthetic database
     * @param org        organization identifier
     * @param subject    synthetic user subject
     * @param role       role granted to the member
     * @param patientId  patient to insert, or {@code null} for a member-only seed
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
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-CLN05-C01', 'CLINIC-A', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    /** Bind the HTTP client to the randomly assigned application port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int localPort) {
        rest = RestClient.builder().baseUrl("http://localhost:" + localPort).build();
    }

    /**
     * Send a request through the real HTTP entry point with synthetic identity headers.
     *
     * @param method   HTTP method
     * @param uri      request path
     * @param body     JSON body, or {@code null} for a bodyless request
     * @param subject  synthetic subject, or {@code null} to omit the header
     * @param org      active organization, or {@code null} to omit the header
     * @return the response with its status and body, whatever the status
     */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        return request(method, uri, body, subject, org, null);
    }

    /**
     * Send a request carrying a version precondition.
     *
     * @param method   HTTP method
     * @param uri      request path
     * @param body     JSON body, or {@code null} for a bodyless request
     * @param subject  synthetic subject
     * @param org      active organization
     * @param ifMatch  {@code If-Match} value, or {@code null} to omit it
     * @return the response with its status and body, whatever the status
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
     * Build a synthetic draft creation body.
     *
     * @param patientId patient whose chart owns the note
     * @return JSON body with only client-owned fields
     */
    private static String createBody(String patientId) {
        return "{\"patientId\":\"" + patientId + "\",\"title\":\"Visit note\","
                + "\"content\":\"" + CONTENT + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"clinician-a\"}";
    }

    /**
     * Create an original draft as a synthetic subject in an organization.
     *
     * @param subject synthetic clinician subject
     * @param org     active organization
     * @return the create response
     */
    private ResponseEntity<String> create(String subject, String org) {
        return request(HttpMethod.POST, "/api/v1/clinical-notes", createBody(PATIENT), subject, org);
    }

    /**
     * Read a scalar field out of the flat JSON responses returned by these endpoints.
     *
     * @param json complete response body
     * @param key  field name
     * @return the field value, or the empty string when absent
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
     * Compute the expected SHA-256 content checksum for the synthetic content.
     *
     * @param content UTF-8 content bytes
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

    @Test
    void draftSignsThenSeparateAmendmentDraftSignsAsAddendum() {
        ResponseEntity<String> created = create("clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals("draft", field(created.getBody(), "state"));
        assertEquals("1", field(created.getBody(), "version"));

        ResponseEntity<String> signed = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("1", field(signed.getBody(), "supersedesVersion"));
        assertEquals("clinician-a", field(signed.getBody(), "signerSubject"));
        assertEquals(ATTESTATION, field(signed.getBody(), "attestation"));
        assertFalse(field(signed.getBody(), "signedAt").isEmpty());
        assertFalse(field(signed.getBody(), "effectiveAt").isEmpty());
        assertEquals("1", field(signed.getBody(), "templateVersion"));
        assertEquals(sha256(CONTENT), field(signed.getBody(), "contentChecksum"));

        ResponseEntity<String> amendment = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/amend", null, "clinician-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.OK, amendment.getStatusCode(), amendment.getBody());
        assertEquals("amendment_draft", field(amendment.getBody(), "state"));
        assertEquals("3", field(amendment.getBody(), "version"));
        assertEquals("2", field(amendment.getBody(), "supersedesVersion"));

        ResponseEntity<String> addendum = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign-addendum",
                "{\"attestation\":\"addendum attested\"}", "clinician-a", ORG_A, "\"3\"");
        assertEquals(HttpStatus.OK, addendum.getStatusCode(), addendum.getBody());
        assertEquals("addendum", field(addendum.getBody(), "state"));
        assertEquals("4", field(addendum.getBody(), "version"));
        assertEquals("3", field(addendum.getBody(), "supersedesVersion"),
                "the addendum must link to the amendment draft it signs");
        assertEquals("clinician-a", field(addendum.getBody(), "signerSubject"));

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(view.getBody().contains("\"draft\""), "the original draft must remain readable");
        assertTrue(view.getBody().contains("\"signed\""));
        assertTrue(view.getBody().contains("\"amendment_draft\""));
        assertTrue(view.getBody().contains("\"addendum\""));
    }

    @Test
    void receptionCannotSignADraft() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clerk-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertEquals("draft", field(request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A).getBody(), "state"));
    }

    @Test
    void staleVersionCannotSignADraft() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        ResponseEntity<String> stale = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clinician-a", ORG_A, "\"9\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals("draft", field(request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A).getBody(), "state"));
    }

    @Test
    void signingAmendmentDraftAsOriginalIsRefused() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clinician-a", ORG_A, "\"1\"").getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/amend", null, "clinician-a", ORG_A, "\"2\"").getStatusCode());

        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clinician-a", ORG_A, "\"3\"");
        assertEquals(HttpStatus.CONFLICT, refused.getStatusCode(), refused.getBody());
        assertEquals("INVALID_TRANSITION", field(refused.getBody(), "code"));
    }

    @Test
    void signingWithoutAttestationIsRefused() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        ResponseEntity<String> refused = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", "{}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_ATTESTATION", field(refused.getBody(), "code"));
        assertEquals("draft", field(request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A).getBody(), "state"));
    }

    @Test
    void otherTenantCannotReadOrSignTheNote() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"" + ATTESTATION + "\"}", "clinician-b", ORG_B, "\"1\"").getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A).getStatusCode());
    }
}
