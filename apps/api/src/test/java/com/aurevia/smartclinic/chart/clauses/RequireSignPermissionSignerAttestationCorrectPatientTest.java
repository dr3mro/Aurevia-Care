package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HexFormat;

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
 * CLN-05 clause 2 (API evidence): require sign permission, signer attestation,
 * correct patient context, draft version and required fields.
 *
 * <p>The clause is implemented by the {@code clinical.notes} module, not by
 * {@code chart.record}: {@code ChartController} exposes only create, verify,
 * list and get under {@code /api/v1/chart/records}. These tests therefore drive
 * the real HTTP contract {@code POST /api/v1/clinical-notes/{id}/sign} against
 * PostgreSQL. The positive test asserts signer attribution, signed instant,
 * checksum, template version and the immutable signed revision; each negative
 * test asserts that one of the five guards refuses what the rule forbids. All
 * fixtures are synthetic and forced row-level security confines each row to its
 * active organization.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_sign_rules_it")
@ActiveProfiles("synthetic-auth")
class RequireSignPermissionSignerAttestationCorrectPatientTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String UNKNOWN_PATIENT = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String URL =
            "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln05_sign_rules_it";
    private static final String DATABASE = "smartclinic_cln05_sign_rules_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String CONTENT = "Chief concern: chest pain";

    @LocalServerPort
    int port;

    RestClient rest;

    /** Create the isolated synthetic database, migrate it and seed both tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician", PATIENT_A);
            seed(statement, ORG_A, "signer-a", "clinician", null);
            seed(statement, ORG_A, "clerk-a", "reception", null);
            seed(statement, ORG_A, "assistant-a", "clinical_assistant", null);
            seed(statement, ORG_A, "inactive-a", "clinician", null);
            seed(statement, ORG_B, "clinician-b", "clinician", PATIENT_B);
            // A later revoked membership supersedes the active one, so this
            // subject fails the active-membership branch of authorize().
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status, valid_from) "
                    + "SELECT '" + ORG_A + "', id, 'revoked', now() FROM public.user_identity "
                    + "WHERE subject = 'inactive-a'");
        }
    }

    /** Drop the isolated synthetic database so parallel workers never share state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** Seed one active organization member and, when supplied, one synthetic patient. */
    private static void seed(Statement statement, String org, String subject, String role,
                             String patientId) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Synthetic org " + subject + "' "
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
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-" + subject + "', 'SYNTHETIC', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    /** Bind a fresh HTTP client for each test. */
    @BeforeEach
    void initRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void signsDraftWithAttestationAndStoresSignerChecksumAndTemplateVersion() {
        // The signer may differ from the clinical author; the ledger must keep
        // both identities and derive the signature metadata from the request.
        String id = create(PATIENT_A, "clinician-a", ORG_A);
        ResponseEntity<String> signed = sign(id, "signer-a", ORG_A, "\"1\"",
                "{\"attestation\":\"I attest I reviewed this note\"}");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("1", field(signed.getBody(), "supersedesVersion"));
        assertEquals("signer-a", field(signed.getBody(), "signerSubject"));
        assertEquals("signer-a", field(signed.getBody(), "dataEntrySubject"));
        assertEquals("clinician-a", field(signed.getBody(), "clinicalAuthorSubject"));
        assertEquals("I attest I reviewed this note", field(signed.getBody(), "attestation"));
        String signedAt = field(signed.getBody(), "signedAt");
        assertFalse(signedAt.isEmpty(), "signedAt must be recorded");
        // Both instants are read back from timestamptz columns in the same UTC
        // rendering; the migration's CHECK also enforces signed_at >= effective_at.
        assertFalse(field(signed.getBody(), "effectiveAt").isEmpty());
        assertTrue(signedAt.compareTo(field(signed.getBody(), "effectiveAt")) >= 0);
        assertEquals("1", field(signed.getBody(), "templateVersion"));
        String checksum = field(signed.getBody(), "contentChecksum");
        assertTrue(checksum.matches("[0-9a-f]{64}"), "checksum must be a 64-hex SHA-256");
        assertEquals(sha256(CONTENT), checksum);

        ResponseEntity<String> reread = read(id, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, reread.getStatusCode(), reread.getBody());
        assertEquals("signed", field(reread.getBody(), "state"));
        assertEquals("signer-a", field(reread.getBody(), "signerSubject"));
        assertTrue(reread.getBody().contains("\"draft\""), "the original draft stays in history");
    }

    @Test
    void roleWithoutSignPermissionCannotSign() {
        // Active membership alone is not enough: NOTE_SIGN is reserved for
        // clinicians, and both a revoked membership and no identity fail closed.
        String id = create(PATIENT_A, "clinician-a", ORG_A);
        ResponseEntity<String> clerk = sign(id, "clerk-a", ORG_A, "\"1\"",
                "{\"attestation\":\"clerk attestation\"}");
        assertEquals(HttpStatus.FORBIDDEN, clerk.getStatusCode(), clerk.getBody());
        assertEquals("FORBIDDEN", field(clerk.getBody(), "code"));
        ResponseEntity<String> assistant = sign(id, "assistant-a", ORG_A, "\"1\"",
                "{\"attestation\":\"assistant attestation\"}");
        assertEquals(HttpStatus.FORBIDDEN, assistant.getStatusCode(), assistant.getBody());
        ResponseEntity<String> revoked = sign(id, "inactive-a", ORG_A, "\"1\"",
                "{\"attestation\":\"revoked attestation\"}");
        assertEquals(HttpStatus.FORBIDDEN, revoked.getStatusCode(), revoked.getBody());
        assertEquals(HttpStatus.UNAUTHORIZED, sign(id, null, ORG_A, "\"1\"",
                "{\"attestation\":\"anonymous attestation\"}").getStatusCode());
        assertEquals("draft", field(read(id, "signer-a", ORG_A).getBody(), "state"));
    }

    @Test
    void signWithMissingOrBlankRequiredFieldIsRefused() {
        // An attestation is a required sign input; blank and over-long values
        // are refused exactly like a missing one.
        String id = create(PATIENT_A, "clinician-a", ORG_A);
        ResponseEntity<String> missing = sign(id, "clinician-a", ORG_A, "\"1\"", "{}");
        assertEquals(422, missing.getStatusCode().value(), missing.getBody());
        assertEquals("INVALID_ATTESTATION", field(missing.getBody(), "code"));
        ResponseEntity<String> blank = sign(id, "clinician-a", ORG_A, "\"1\"",
                "{\"attestation\":\"   \"}");
        assertEquals(422, blank.getStatusCode().value(), blank.getBody());
        assertEquals("INVALID_ATTESTATION", field(blank.getBody(), "code"));
        ResponseEntity<String> tooLong = sign(id, "clinician-a", ORG_A, "\"1\"",
                "{\"attestation\":\"" + "x".repeat(501) + "\"}");
        assertEquals(422, tooLong.getStatusCode().value());
        assertEquals("INVALID_ATTESTATION", field(tooLong.getBody(), "code"));

        // Required clinical fields are validated before a draft can be created,
        // so a field-incomplete note never reaches the signing boundary.
        ResponseEntity<String> blankContent = request(HttpMethod.POST, "/api/v1/clinical-notes",
                "{\"patientId\":\"" + PATIENT_A + "\",\"title\":\"Visit note\",\"content\":\"   \","
                        + "\"language\":\"en\",\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                        + "\"clinicalAuthorSubject\":\"clinician-a\"}",
                "clinician-a", ORG_A, null);
        assertEquals(422, blankContent.getStatusCode().value(), blankContent.getBody());
        assertEquals("INVALID_CONTENT", field(blankContent.getBody(), "code"));
        assertEquals("draft", field(read(id, "clinician-a", ORG_A).getBody(), "state"));
    }

    @Test
    void staleDraftVersionCannotSign() {
        // Signing is version-bound: stale, missing and malformed preconditions
        // must not produce a signature, and only a draft may be signed.
        String id = create(PATIENT_A, "clinician-a", ORG_A);
        ResponseEntity<String> stale = sign(id, "clinician-a", ORG_A, "\"9\"",
                "{\"attestation\":\"attested\"}");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode(), stale.getBody());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        ResponseEntity<String> absent = sign(id, "clinician-a", ORG_A, null,
                "{\"attestation\":\"attested\"}");
        assertEquals(428, absent.getStatusCode().value(), absent.getBody());
        assertEquals("PRECONDITION_REQUIRED", field(absent.getBody(), "code"));
        ResponseEntity<String> malformed = sign(id, "clinician-a", ORG_A, "abc",
                "{\"attestation\":\"attested\"}");
        assertEquals(422, malformed.getStatusCode().value(), malformed.getBody());
        assertEquals("INVALID_IF_MATCH", field(malformed.getBody(), "code"));
        assertEquals("draft", field(read(id, "clinician-a", ORG_A).getBody(), "state"));

        assertEquals(HttpStatus.OK, sign(id, "clinician-a", ORG_A, "\"1\"",
                "{\"attestation\":\"attested\"}").getStatusCode());
        ResponseEntity<String> reSign = sign(id, "clinician-a", ORG_A, "\"2\"",
                "{\"attestation\":\"again\"}");
        assertEquals(HttpStatus.CONFLICT, reSign.getStatusCode(), reSign.getBody());
        assertEquals("INVALID_TRANSITION", field(reSign.getBody(), "code"));
    }

    @Test
    void noteForAnotherTenantPatientCannotBeSigned() {
        // Row-level security conceals another organization's note entirely, so
        // the wrong tenant cannot even address its sign route.
        String foreignId = create(PATIENT_B, "clinician-b", ORG_B);
        ResponseEntity<String> concealed = sign(foreignId, "clinician-a", ORG_A, "\"1\"",
                "{\"attestation\":\"attested\"}");
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());
        assertEquals(HttpStatus.NOT_FOUND, read(foreignId, "clinician-a", ORG_A).getStatusCode());
        ResponseEntity<String> ownerReread = read(foreignId, "clinician-b", ORG_B);
        assertEquals(HttpStatus.OK, ownerReread.getStatusCode(), ownerReread.getBody());
        assertEquals("draft", field(ownerReread.getBody(), "state"));
        assertFalse(ownerReread.getBody().contains("\"signed\""),
                "the concealed note must not carry a signed revision");

        // A patient that is not in the active organization is refused at draft
        // creation, keeping the note-to-patient binding inside the caller's tenant.
        ResponseEntity<String> wrongPatient = request(HttpMethod.POST, "/api/v1/clinical-notes",
                createBody(UNKNOWN_PATIENT, "clinician-a"), "clinician-a", ORG_A, null);
        assertEquals(422, wrongPatient.getStatusCode().value(), wrongPatient.getBody());
        assertEquals("INVALID_PATIENT_CONTEXT", field(wrongPatient.getBody(), "code"));
    }

    /** Create an original draft for the supplied synthetic patient and author. */
    private String create(String patientId, String author, String org) {
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/clinical-notes",
                createBody(patientId, author), author, org, null);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        assertEquals("draft", field(created.getBody(), "state"));
        return field(created.getBody(), "id");
    }

    /** Build a draft creation body with only synthetic identifiers and content. */
    private static String createBody(String patientId, String author) {
        return "{\"patientId\":\"" + patientId + "\",\"title\":\"Visit note\","
                + "\"content\":\"" + CONTENT + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"" + author + "\"}";
    }

    /** Read a note through the HTTP entry point as the supplied synthetic subject. */
    private ResponseEntity<String> read(String id, String subject, String org) {
        return request(HttpMethod.GET, "/api/v1/clinical-notes/" + id, null, subject, org, null);
    }

    /** Sign a note at the supplied version precondition through the HTTP entry point. */
    private ResponseEntity<String> sign(String id, String subject, String org, String ifMatch,
                                        String body) {
        return request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                body, subject, org, ifMatch);
    }

    /** Send one request with the synthetic identity, tenant and optional precondition. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        headers.set("X-Active-Organization", org);
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

    /** Read a scalar field out of the flat JSON responses used by this test. */
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

    /** Compute the SHA-256 the server derives from the synthetic note content. */
    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
