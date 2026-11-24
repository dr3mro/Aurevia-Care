package com.aurevia.it.clinical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
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
 * S-G1-05 acceptance: draft to signed, amendments, addenda and entered-in-error
 * with preserved originals (CLN-05). All records are synthetic.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_cln05_it")
@ActiveProfiles("synthetic-auth")
class ClinicalNoteRevisionIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_cln05_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln05_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_cln05_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician", PATIENT_ID);
            seed(statement, ORG_A, "clerk-a", "reception", null);
            seed(statement, ORG_A, "inactive-a", "clinician", null);
            seed(statement, ORG_B, "clinician-b", "clinician", PATIENT_ID);
            seed(statement, ORG_B, "clerk-b", "reception", null);
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            statement.execute("INSERT INTO public.membership (organization_id, user_id, status, valid_from) "
                    + "SELECT '" + ORG_A + "', id, 'revoked', now() FROM public.user_identity "
                    + "WHERE subject = 'inactive-a'");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln05_it WITH (FORCE)");
        }
    }

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
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-CLN05', 'CLINIC-A', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
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

    private String createBody(String patientId) {
        return "{\"patientId\":\"" + patientId + "\",\"title\":\"Visit note\","
                + "\"content\":\"Chief concern: chest pain\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"clinician-a\"}";
    }

    private ResponseEntity<String> create(String subject, String org) {
        return request(HttpMethod.POST, "/api/v1/clinical-notes", createBody(PATIENT_ID), subject, org);
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

    private long auditCount(String action, String outcome, String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            var results = statement.executeQuery("SELECT count(*) FROM public.audit_event "
                    + "WHERE action = '" + action + "' AND outcome = '" + outcome + "'");
            results.next();
            return results.getLong(1);
        }
    }

    @Test
    void createDraftAndReadFullHistory() throws Exception {
        long before = auditCount("NOTE_CREATE", "SUCCESS", ORG_A);
        ResponseEntity<String> created = create("clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        assertEquals("draft", field(created.getBody(), "state"));
        assertEquals("1", field(created.getBody(), "version"));
        assertEquals(sha256("Chief concern: chest pain"), field(created.getBody(), "contentChecksum"));
        assertEquals("clinician-a", field(created.getBody(), "clinicalAuthorSubject"));
        String id = field(created.getBody(), "id");
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(view.getBody().contains("\"history\""));
        assertTrue(view.getBody().contains("\"current\""));
        assertEquals(before + 1, auditCount("NOTE_CREATE", "SUCCESS", ORG_A));
    }

    @Test
    void editDraftUsesVersionPreconditionAndKeepsOldRevision() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        String edit = "{\"title\":\"Updated\",\"content\":\"Updated content\"}";
        ResponseEntity<String> stale = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clinician-a", ORG_A, "\"9\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        ResponseEntity<String> missing = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clinician-a", ORG_A);
        assertEquals(428, missing.getStatusCode().value());
        ResponseEntity<String> updated = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, updated.getStatusCode(), updated.getBody());
        assertEquals("2", field(updated.getBody(), "version"));
        assertEquals(sha256("Updated content"), field(updated.getBody(), "contentChecksum"));
        assertNotEquals(sha256("Chief concern: chest pain"),
                field(updated.getBody(), "contentChecksum"));
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertTrue(view.getBody().contains("Chief concern: chest pain"),
                "the prior draft must remain in the append-only history");
    }

    @Test
    void signingRecordsMetadataAndPreservesTheDraft() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        String sign = "{\"attestation\":\"I attest I reviewed this note\"}";
        ResponseEntity<String> signed = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", sign, "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("clinician-a", field(signed.getBody(), "signerSubject"));
        assertEquals("I attest I reviewed this note", field(signed.getBody(), "attestation"));
        assertFalse(field(signed.getBody(), "signedAt").isEmpty());
        assertFalse(field(signed.getBody(), "effectiveAt").isEmpty());
        assertEquals("1", field(signed.getBody(), "templateVersion"));
        assertEquals(sha256("Chief concern: chest pain"), field(signed.getBody(), "contentChecksum"));
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertTrue(view.getBody().contains("\"draft\""), "the original draft must remain readable");
        assertTrue(view.getBody().contains("\"signed\""));
    }

    @Test
    void signedRevisionsCannotBeEditedOrReSigned() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-a", ORG_A, "\"1\"").getStatusCode());
        String edit = "{\"title\":\"Late edit\",\"content\":\"Late content\"}";
        ResponseEntity<String> editResult = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clinician-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.CONFLICT, editResult.getStatusCode(), editResult.getBody());
        assertEquals("IMMUTABLE_REVISION", field(editResult.getBody(), "code"));
        ResponseEntity<String> reSign = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"again\"}", "clinician-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.CONFLICT, reSign.getStatusCode());
    }

    @Test
    void amendmentDraftAndAddendumPreserveTheOriginal() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-a", ORG_A, "\"1\"").getStatusCode());
        ResponseEntity<String> amendment = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/amend", null, "clinician-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.OK, amendment.getStatusCode(), amendment.getBody());
        assertEquals("amendment_draft", field(amendment.getBody(), "state"));
        assertEquals("2", field(amendment.getBody(), "supersedesVersion"));
        ResponseEntity<String> addendum = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign-addendum",
                "{\"attestation\":\"addendum attested\"}", "clinician-a", ORG_A, "\"3\"");
        assertEquals(HttpStatus.OK, addendum.getStatusCode(), addendum.getBody());
        assertEquals("addendum", field(addendum.getBody(), "state"));
        assertEquals("3", field(addendum.getBody(), "supersedesVersion"),
                "the addendum must link to the amendment draft it signs");
        assertEquals("clinician-a", field(addendum.getBody(), "signerSubject"));
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertTrue(view.getBody().contains("\"signed\""));
        assertTrue(view.getBody().contains("\"amendment_draft\""));
        assertTrue(view.getBody().contains("\"addendum\""));
    }

    @Test
    void enteredInErrorPreservesPriorAndRecordsActorReasonAndRecipients() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-a", ORG_A, "\"1\"").getStatusCode());
        String body = "{\"reason\":\"wrong patient\",\"affectedRecipients\":\"[\\\"lab-a\\\"]\"}";
        ResponseEntity<String> result = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                body, "clinician-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.OK, result.getStatusCode(), result.getBody());
        assertEquals("entered_in_error", field(result.getBody(), "state"));
        assertEquals("wrong patient", field(result.getBody(), "enteredErrorReason"));
        assertEquals("clinician-a", field(result.getBody(), "enteredErrorActor"));
        assertTrue(result.getBody().contains("lab-a"));
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertTrue(view.getBody().contains("\"signed\""), "the signed original must remain readable");
        ResponseEntity<String> missingReason = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/entered-in-error",
                "{\"reason\":\"  \",\"affectedRecipients\":\"[]\"}", "clinician-a", ORG_A, "\"3\"");
        assertEquals(422, missingReason.getStatusCode().value());
    }

    @Test
    void signValidatesAttestationPatientAndVersion() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(422, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{}", "clinician-a", ORG_A, "\"1\"").getStatusCode().value());
        assertEquals(HttpStatus.UNAUTHORIZED, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", null, ORG_A, "\"1\"").getStatusCode());
        ResponseEntity<String> wrongPatient = request(HttpMethod.POST,
                "/api/v1/clinical-notes", createBody("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                "clinician-a", ORG_A);
        assertEquals(422, wrongPatient.getStatusCode().value());
        assertEquals("INVALID_PATIENT_CONTEXT", field(wrongPatient.getBody(), "code"));
    }

    @Test
    void rolesAndInactiveMembershipAreDenied() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        String sign = "{\"attestation\":\"attested\"}";
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", sign, "clerk-a", ORG_A, "\"1\"").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign", sign, "inactive-a", ORG_A, "\"1\"").getStatusCode());
        assertTrue(auditCount("NOTE_SIGN", "DENIED", ORG_A) >= 2);
    }

    @Test
    void crossTenantNoteIdsAreConcealed() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-b", ORG_B, "\"1\"").getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.GET,
                "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A).getStatusCode());
    }

    @Test
    void failedCommandAuditIsCommittedAfterRollback() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        long before = auditCount("NOTE_SIGN", "NOT_FOUND", ORG_B);
        // The sign of an invisible note fails with 404 inside the guarded
        // transaction. Its NOT_FOUND audit must survive that rollback and be
        // committed in the caller's tenant (OPS-02).
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-b", ORG_B, "\"1\"").getStatusCode());
        assertEquals(before + 1, auditCount("NOTE_SIGN", "NOT_FOUND", ORG_B));
    }

    @Test
    void staleVersionAuditIsCommittedAfterRollback() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-a", ORG_A, "\"1\"").getStatusCode());
        long before = auditCount("NOTE_EDIT", "CONFLICT", ORG_A);
        // A stale edit of a signed note is a rejected clinical write. Its
        // CONFLICT audit must survive the command rollback and be committed in
        // the caller's tenant (OPS-02), naming the requested note.
        ResponseEntity<String> stale = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                "{\"title\":\"Late\",\"content\":\"Late content\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.PRECONDITION_FAILED, stale.getStatusCode());
        assertEquals("STALE_VERSION", field(stale.getBody(), "code"));
        assertEquals(before + 1, auditCount("NOTE_EDIT", "CONFLICT", ORG_A));
    }

    @Test
    void illegalTransitionAuditIsCommittedAfterRollback() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-a", ORG_A, "\"1\"").getStatusCode());
        long before = auditCount("NOTE_SIGN", "CONFLICT", ORG_A);
        // Re-signing a signed note is an illegal transition (409). Its CONFLICT
        // audit must also survive the command rollback (OPS-02).
        ResponseEntity<String> reSign = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"again\"}", "clinician-a", ORG_A, "\"2\"");
        assertEquals(HttpStatus.CONFLICT, reSign.getStatusCode());
        assertEquals("INVALID_TRANSITION", field(reSign.getBody(), "code"));
        assertEquals(before + 1, auditCount("NOTE_SIGN", "CONFLICT", ORG_A));
    }

    @Test
    void revisionRowsAreAppendOnlyForEveryRole() throws Exception {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        assertMutationRejected("aurevia_migrate", "UPDATE public.clinical_note_revision "
                + "SET content = 'tampered' WHERE id = '" + id + "'");
        assertMutationRejected("aurevia_app", "UPDATE public.clinical_note_revision "
                + "SET content = 'tampered' WHERE id = '" + id + "'");
        assertMutationRejected("aurevia_migrate", "DELETE FROM public.clinical_note_revision "
                + "WHERE id = '" + id + "'");
        assertMutationRejected("aurevia_migrate", "TRUNCATE public.clinical_note_revision");
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id,
                null, "clinician-a", ORG_A);
        assertEquals("Chief concern: chest pain", field(view.getBody(), "content"));
    }

    private static void assertMutationRejected(String user, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, user, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'clinician-a'");
            statement.execute(sql);
            org.junit.jupiter.api.Assertions.fail("append-only revision must reject: " + sql);
        } catch (SQLException exception) {
            // 44000 is the append-only guard; 42501 is the missing write grant.
            assertTrue("44000".equals(exception.getSQLState())
                            || "42501".equals(exception.getSQLState()),
                    "unexpected SQL state " + exception.getSQLState() + " for " + sql);
        }
    }

    @Test
    void noReleaseOrRenderingStateIsIntroduced() {
        String id = field(create("clinician-a", ORG_A).getBody(), "id");
        ResponseEntity<String> signed = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode());
        assertFalse(signed.getBody().matches("(?i).*(release|pdf|render|disclos).*"));
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/release", "{}", "clinician-a", ORG_A).getStatusCode());
    }
}
