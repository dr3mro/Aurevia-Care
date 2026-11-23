package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * CLN-05 clause 6 (API evidence): signing a clinical note is a separate act from
 * patient release and PDF rendering.
 *
 * <p>Every case drives the real {@code POST /api/v1/clinical-notes/{id}/sign}
 * entry point on a fresh PostgreSQL database built from the production Flyway
 * migrations. The proof is a database assertion, not a response-body pattern:
 * a tenant-bound control count first shows that the same JDBC connection sees
 * committed rows, then the release and rendering ledgers are asserted empty on
 * that same connection. Without the control row the zero counts would be
 * vacuous under forced row-level security, which also covers the migration
 * owner.</p>
 *
 * <p>The negative cases show that the separation still holds when the command
 * is refused: a non-clinician, a clinician from another tenant, a stale version
 * and a missing attestation all fail without leaving a release or a render.
 * Attempts to reach a note release or render route return 404 because no such
 * route exists on the clinical-note aggregate.</p>
 *
 * <p>All fixtures are synthetic; no real identity or patient data is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_signing_it")
@ActiveProfiles("synthetic-auth")
class SigningDiffersPatientReleasePdfRenderingTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_signing_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String SIGN_BODY = "{\"attestation\":\"I attest I reviewed this note\"}";

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create an isolated, migrated database and seed two synthetic tenants.
     *
     * @throws Exception when the database cannot be reset, migrated or seeded
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_signing_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln05_signing_it OWNER smartclinic_migrate");
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
     * Drop the temporary database so a rerun starts from a known empty state.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln05_signing_it WITH (FORCE)");
        }
    }

    /**
     * Seed one synthetic tenant member and, optionally, one synthetic patient.
     *
     * @param statement  open statement whose organization context is bound by the caller
     * @param org        tenant identifier
     * @param subject    synthetic authenticated subject
     * @param role       role granted to that subject
     * @param patientId  synthetic patient identifier, or {@code null} for no patient
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
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-CLN05-SIGN', 'CLINIC-A', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    /**
     * Perform an HTTP request with the synthetic identity headers of one tenant.
     *
     * @param method  HTTP method
     * @param uri     request path
     * @param body    JSON body, or {@code null} for no body
     * @param subject synthetic subject, or {@code null} for no identity
     * @param org     active tenant, or {@code null} for no tenant header
     * @param ifMatch version precondition, or {@code null} for none
     * @return the response with an unconsumed error body so statuses stay assertable
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
     * Create a synthetic draft note in one tenant.
     *
     * @param subject synthetic clinician subject
     * @param org     active tenant
     * @return the create response
     */
    private ResponseEntity<String> create(String subject, String org) {
        String body = "{\"patientId\":\"" + PATIENT_ID + "\",\"title\":\"Visit note\","
                + "\"content\":\"Chief concern: chest pain\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"" + subject + "\"}";
        return request(HttpMethod.POST, "/api/v1/clinical-notes", body, subject, org, null);
    }

    /**
     * Read a scalar JSON field from a compact response body.
     *
     * @param json compact JSON object
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
     * Read one scalar count from an already tenant-bound statement.
     *
     * @param statement statement with {@code app.current_org} set
     * @param sql       count query
     * @return the count
     * @throws Exception when the query fails
     */
    private static long count(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    /**
     * Assert that no patient-release or PDF-rendering state exists in one tenant.
     *
     * <p>The migration owner is subject to forced row-level security too, so the
     * tenant is bound on this connection before every count; otherwise all zero
     * counts are vacuous. A positive control on the same connection proves that
     * committed rows are visible. In the owning tenant the control is the note's
     * revision count. In the other tenant the note is concealed by design, so
     * its seeded identity row is the visibility control instead.</p>
     *
     * @param org               tenant whose rows are visible
     * @param noteId            synthetic note identifier
     * @param expectedRevisions revision count expected in the owning tenant
     * @throws Exception        when a database assertion fails
     */
    private static void assertNoReleaseOrRender(String org, String noteId,
                                                long expectedRevisions) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            long revisions = count(statement, "SELECT count(*) FROM public.clinical_note_revision "
                    + "WHERE id = '" + noteId + "'");
            if (ORG_A.equals(org)) {
                assertEquals(expectedRevisions, revisions,
                        "CONTROL: committed note revisions must be visible in the owning tenant");
            } else {
                assertEquals(0L, revisions, "the note must stay concealed in the other tenant");
                assertTrue(count(statement, "SELECT count(*) FROM public.user_identity") > 0,
                        "CONTROL: the other tenant connection must see its seeded rows");
            }
            assertEquals(0L, count(statement, "SELECT count(*) FROM public.patient_release"),
                    org + " patient_release must stay empty");
            assertEquals(0L, count(statement, "SELECT count(*) FROM public.release_event"),
                    org + " release_event must stay empty");
            assertEquals(0L, count(statement, "SELECT count(*) FROM public.report_release_revision"),
                    org + " report_release_revision must stay empty");
            assertEquals(0L, count(statement, "SELECT count(*) FROM public.document_pdf_job"),
                    org + " document_pdf_job must stay empty");
            assertEquals(0L, count(statement, "SELECT count(*) FROM public.rendered_document"),
                    org + " rendered_document must stay empty");
        }
    }

    /**
     * Signing appends a signed revision and leaves every release and render ledger empty.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void signingAppendsSignedRevisionWithoutPatientReleaseOrPdfRender() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        ResponseEntity<String> signed = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", SIGN_BODY, "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertNoReleaseOrRender(ORG_A, id, 2L);
    }

    /**
     * A clinical note has neither a release nor a render endpoint.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void thereIsNoReleaseOrRenderEndpointOnAClinicalNote() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", SIGN_BODY, "clinician-a", ORG_A, "\"1\"")
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/release", "{}", "clinician-a", ORG_A, null)
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/render", "{}", "clinician-a", ORG_A, null)
                .getStatusCode());
        assertNoReleaseOrRender(ORG_A, id, 2L);
    }

    /**
     * A user without sign permission can neither sign nor produce release or render state.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void aNonClinicianCannotSignAndCannotCreateARelease() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", SIGN_BODY, "clerk-a", ORG_A, "\"1\"")
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/release", "{}", "clerk-a", ORG_A, null)
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/render", "{}", "clerk-a", ORG_A, null)
                .getStatusCode());
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A, null);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertEquals("draft", field(view.getBody(), "state"));
        assertNoReleaseOrRender(ORG_A, id, 1L);
    }

    /**
     * A clinician in another tenant can neither sign the note nor create release state.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void aClinicianFromAnotherTenantCannotSignAndCannotCreateARelease() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-b", ORG_B, null).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", SIGN_BODY, "clinician-b", ORG_B, "\"1\"")
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/release", "{}", "clinician-b", ORG_B, null)
                .getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/render", "{}", "clinician-b", ORG_B, null)
                .getStatusCode());
        assertNoReleaseOrRender(ORG_A, id, 1L);
        assertNoReleaseOrRender(ORG_B, id, 0L);
    }

    /**
     * A stale version precondition cannot sign or create release or render state.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void aStaleVersionCannotSignAndCannotCreateARelease() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.PUT,
                "/api/v1/clinical-notes/" + id,
                "{\"title\":\"Updated\",\"content\":\"Updated content\"}",
                "clinician-a", ORG_A, "\"1\"").getStatusCode());
        assertEquals(HttpStatus.PRECONDITION_FAILED, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", SIGN_BODY, "clinician-a", ORG_A, "\"1\"")
                .getStatusCode());
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A, null);
        assertEquals("draft", field(view.getBody(), "state"));
        assertNoReleaseOrRender(ORG_A, id, 2L);
    }

    /**
     * A sign without attestation is refused and leaves no release or render state.
     *
     * @throws Exception when a database assertion fails
     */
    @Test
    void aSignWithoutAttestationIsRefusedAndLeavesNoReleaseOrRender() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(422, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", "{}", "clinician-a", ORG_A, "\"1\"")
                .getStatusCode().value());
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A, null);
        assertEquals("draft", field(view.getBody(), "state"));
        assertNoReleaseOrRender(ORG_A, id, 1L);
    }
}
