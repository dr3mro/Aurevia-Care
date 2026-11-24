package com.aurevia.chart.clauses;

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
 * CLN-05 clause 6 (API test): signing differs from patient release and PDF rendering.
 *
 * <p>Signing is a clinical-documentation decision in {@code clinical.notes}: it appends
 * an immutable signed revision carrying the signer, attestation, effective and signed
 * instants and a content checksum. It is deliberately not a patient-release decision
 * ({@code exchange.release}, XCH-06) and not a PDF-rendering operation
 * ({@code documents.PdfRenderer}, CLN-07). Those workflows have their own controllers,
 * authorization policies and storage; a signature must not publish content to a
 * patient or produce a rendered document as a side effect.</p>
 *
 * <p>Proof goes through the real HTTP entry points against a fresh database built
 * from the production Flyway migrations. The positive test signs a draft and shows
 * that the patient-released-revision list and the rendered-document store stay empty.
 * Negative tests show that the note controller exposes no release or PDF route and
 * that a non-signer or a user from another tenant is refused. All fixtures are
 * synthetic; no production code is added by this suite.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url="
                + "jdbc:postgresql://127.0.0.1:5432/aurevia_cln05_signing_diff_it")
@ActiveProfiles("synthetic-auth")
class SigningDiffersPatientReleasePdfRenderingTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/aurevia_cln05_signing_diff_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln05_signing_diff_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_cln05_signing_diff_it "
                    + "OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "assistant-a", "clinical_assistant");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            // V7 requires one of dob / dob_unknown_reason; a synthetic civil date
            // satisfies that append-only invariant before any command runs.
            statement.execute("INSERT INTO public.patient_record "
                    + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                    + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-SIGN-DIFF', 'CLINIC-A', "
                    + "'Synthetic Signing Patient', 'synthetic signing patient', DATE '1990-05-20')");
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln05_signing_diff_it WITH (FORCE)");
        }
    }

    /**
     * Bind the HTTP client to the randomly assigned server port.
     */
    @BeforeEach
    void initRest(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    private static void seedUser(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
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

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private static String createBody() {
        return "{\"patientId\":\"" + PATIENT + "\",\"title\":\"Visit note\","
                + "\"content\":\"Chief concern: chest pain\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"clinician-a\"}";
    }

    private ResponseEntity<String> createDraft() {
        return request(HttpMethod.POST, "/api/v1/clinical-notes", createBody(),
                "clinician-a", ORG_A);
    }

    private ResponseEntity<String> sign(String id, String version, String subject, String org) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("If-Match", "\"" + version + "\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.post().uri("/api/v1/clinical-notes/" + id + "/sign")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body("{\"attestation\":\"I attest I reviewed this note\"}")
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
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

    /** Count tenant-scoped rows in a release or rendering ledger after signing. */
    private static long count(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            // The migration owner is subject to FORCE RLS too, so bind the tenant.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM public." + table)) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    @Test
    void signingRecordsSignatureWithoutReleasingToPatientOrRenderingPdf() throws Exception {
        ResponseEntity<String> created = createDraft();
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        assertEquals("draft", field(created.getBody(), "state"));
        String id = field(created.getBody(), "id");

        ResponseEntity<String> signed = sign(id, "1", "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("clinician-a", field(signed.getBody(), "signerSubject"));
        assertEquals("I attest I reviewed this note", field(signed.getBody(), "attestation"));
        assertFalse(field(signed.getBody(), "signedAt").isEmpty());
        assertFalse(field(signed.getBody(), "contentChecksum").isEmpty());
        assertEquals("1", field(signed.getBody(), "templateVersion"));

        // The signature carries only documentation metadata: no release decision and
        // no rendering artifact are embedded in the signed revision.
        assertFalse(signed.getBody().matches("(?i).*(release|pdf|render|disclos).*"),
                "a signed note must not embed patient-release or PDF-rendering state");

        // Signing did not authorize patient release: the patient's released-revision
        // list stays empty until the separate XCH-06 workflow runs, and no release
        // row was appended.
        ResponseEntity<String> released = request(HttpMethod.GET,
                "/api/v1/patients/" + PATIENT + "/released-revisions", null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, released.getStatusCode(), released.getBody());
        assertEquals("[]", released.getBody().trim(),
                "signing must not publish the note to the patient release channel");
        assertEquals(0, count("patient_release"));

        // Signing did not render a PDF either: the rendered-document ledger is empty.
        assertEquals(0, count("rendered_document"));

        // The draft is preserved in the append-only history alongside the signature.
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(view.getBody().contains("\"draft\""));
        assertTrue(view.getBody().contains("\"signed\""));
    }

    @Test
    void noteControllerDoesNotExposeReleaseOrPdfRoutes() {
        String id = field(createDraft().getBody(), "id");
        assertEquals(HttpStatus.OK, sign(id, "1", "clinician-a", ORG_A).getStatusCode());

        // Release and rendering live on other controllers; signing is not a shortcut
        // to either. A note identifier cannot be used to release or render a note.
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/release", "{}", "clinician-a", ORG_A).getStatusCode(),
                "patient release is a separate workflow, not a clinical-note route");
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/pdf", "{}", "clinician-a", ORG_A).getStatusCode(),
                "PDF rendering is a separate workflow, not a clinical-note route");
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/render", "{}", "clinician-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id + "/pdf", null, "clinician-a", ORG_A).getStatusCode());
    }

    @Test
    void receptionCannotSignBecauseSignPermissionIsDistinctFromReleaseRoles() {
        ResponseEntity<String> created = createDraft();
        String id = field(created.getBody(), "id");
        assertEquals("draft", field(created.getBody(), "state"));

        ResponseEntity<String> denied = sign(id, "1", "reception-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());

        // The refused attempt must leave the note as an editable draft: it neither
        // signed the note nor triggered any release or rendering side effect.
        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertEquals("draft", field(view.getBody(), "state"));
    }

    @Test
    void crossTenantClinicianCannotSignNote() {
        ResponseEntity<String> created = createDraft();
        String id = field(created.getBody(), "id");

        ResponseEntity<String> denied = sign(id, "1", "clinician-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());

        ResponseEntity<String> view = request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
        assertEquals("draft", field(view.getBody(), "state"));
    }
}
