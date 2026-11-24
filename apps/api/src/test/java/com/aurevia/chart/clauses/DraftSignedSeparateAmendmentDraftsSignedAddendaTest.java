package com.aurevia.chart.clauses;

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

import com.aurevia.clinical.notes.ClinicalNoteController;
import com.aurevia.clinical.notes.ClinicalNoteService;
import com.aurevia.clinical.notes.NoteState;

/**
 * CLN-05 clause 1 acceptance through the real HTTP controller and PostgreSQL ledger.
 *
 * <p>Proves that an original draft becomes an immutable signed revision, that a
 * separate amendment draft is opened from that signed revision, and that signing
 * the amendment draft produces an immutable addendum linked to it. Negative
 * cases show that the forbidden state transitions are refused. All records are
 * synthetic and this suite owns its own database so parallel clause suites do
 * not share state.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_cln05_c1_it")
@ActiveProfiles("synthetic-auth")
class DraftSignedSeparateAmendmentDraftsSignedAddendaTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String SUBJECT = "clinician-a";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_cln05_c1_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create an isolated database, apply the production migrations and seed one
     * clinician, one non-clinical user and one synthetic patient.
     *
     * @throws Exception when database setup fails
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln05_c1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_cln05_c1_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, SUBJECT, "clinician", PATIENT_ID);
            seed(statement, ORG_A, "clerk-a", "reception", null);
        }
    }

    /**
     * Remove the suite database after all tests.
     *
     * @throws Exception when cleanup fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_cln05_c1_it WITH (FORCE)");
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
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-CLN05-C1', 'CLINIC-A', "
                    + "'Synthetic Patient', 'synthetic patient', '1990-05-20', 'en', "
                    + "'{}'::jsonb, '[]'::jsonb, '[]'::jsonb)");
        }
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body, String ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", SUBJECT);
        headers.set("X-Active-Organization", ORG_A);
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

    private ResponseEntity<String> create() {
        String body = "{\"patientId\":\"" + PATIENT_ID + "\",\"title\":\"Visit note\","
                + "\"content\":\"Chief concern: chest pain\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"" + SUBJECT + "\"}";
        return request(HttpMethod.POST, "/api/v1/clinical-notes", body, null);
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

    /**
     * An original draft signs into an immutable revision; a separate amendment
     * draft is opened from it and signs as an addendum linked to that draft.
     */
    @Test
    void draftSignsThenSeparateAmendmentDraftSignsAsAddendum() {
        ResponseEntity<String> draft = create();
        assertEquals(HttpStatus.OK, draft.getStatusCode(), draft.getBody());
        assertEquals(NoteState.DRAFT.wire(), field(draft.getBody(), "state"));
        assertEquals("1", field(draft.getBody(), "version"));
        String id = field(draft.getBody(), "id");

        ResponseEntity<String> signed = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"I attest I reviewed this note\"}", "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals(NoteState.SIGNED.wire(), field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        assertEquals("1", field(signed.getBody(), "supersedesVersion"));
        assertEquals(SUBJECT, field(signed.getBody(), "signerSubject"));
        assertEquals("I attest I reviewed this note", field(signed.getBody(), "attestation"));
        assertFalse(field(signed.getBody(), "signedAt").isEmpty());

        ResponseEntity<String> amendment = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/amend", null, "\"2\"");
        assertEquals(HttpStatus.OK, amendment.getStatusCode(), amendment.getBody());
        assertEquals(NoteState.AMENDMENT_DRAFT.wire(), field(amendment.getBody(), "state"));
        assertEquals("3", field(amendment.getBody(), "version"));
        assertEquals("2", field(amendment.getBody(), "supersedesVersion"));
        assertEquals("null", field(amendment.getBody(), "signedAt"),
                "an amendment draft is editable and carries no signature");
        assertEquals("null", field(amendment.getBody(), "signerSubject"));

        ResponseEntity<String> addendum = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/sign-addendum",
                "{\"attestation\":\"I attest this addendum\"}", "\"3\"");
        assertEquals(HttpStatus.OK, addendum.getStatusCode(), addendum.getBody());
        assertEquals(NoteState.ADDENDUM.wire(), field(addendum.getBody(), "state"));
        assertEquals("4", field(addendum.getBody(), "version"));
        assertEquals("3", field(addendum.getBody(), "supersedesVersion"),
                "the addendum must link to the amendment draft it signs");
        assertEquals(SUBJECT, field(addendum.getBody(), "signerSubject"));
        assertFalse(field(addendum.getBody(), "signedAt").isEmpty());

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id, null, null);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertTrue(view.getBody().contains("\"" + NoteState.DRAFT.wire() + "\""));
        assertTrue(view.getBody().contains("\"" + NoteState.SIGNED.wire() + "\""),
                "the original signed revision must remain in the append-only history");
        assertTrue(view.getBody().contains("\"" + NoteState.AMENDMENT_DRAFT.wire() + "\""));
        assertTrue(view.getBody().contains("\"" + NoteState.ADDENDUM.wire() + "\""));
        assertTrue(view.getBody().contains("Chief concern: chest pain"),
                "the original content must survive the amendment and addendum");
    }

    /**
     * An unsigned draft cannot be amended: only a signed revision may open a
     * separate amendment draft.
     */
    @Test
    void aDraftCannotBeAmendedBeforeItIsSigned() {
        String id = field(create().getBody(), "id");
        ResponseEntity<String> amendment = request(HttpMethod.POST,
                "/api/v1/clinical-notes/" + id + "/amend", null, "\"1\"");
        assertEquals(HttpStatus.CONFLICT, amendment.getStatusCode(), amendment.getBody());
        assertEquals("INVALID_TRANSITION", field(amendment.getBody(), "code"));

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id, null, null);
        assertEquals(NoteState.DRAFT.wire(), field(view.getBody(), "state"));
        assertEquals("1", field(view.getBody(), "version"));
    }

    /**
     * An amendment draft cannot be signed as an original draft: signing it must
     * go through the addendum command, leaving the amendment draft unchanged.
     */
    @Test
    void anAmendmentDraftCannotBeSignedAsAnOriginalDraft() {
        String id = field(create().getBody(), "id");
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"attested\"}", "\"1\"").getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/amend",
                null, "\"2\"").getStatusCode());

        ResponseEntity<String> reSign = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"again\"}", "\"3\"");
        assertEquals(HttpStatus.CONFLICT, reSign.getStatusCode(), reSign.getBody());
        assertEquals("INVALID_TRANSITION", field(reSign.getBody(), "code"));

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id, null, null);
        assertEquals(NoteState.AMENDMENT_DRAFT.wire(), field(view.getBody(), "state"));
        assertEquals("3", field(view.getBody(), "version"));
    }

    /**
     * A non-clinical user may draft but cannot advance the note to signed.
     */
    @Test
    void aNonClinicianCannotSignADraft() {
        String body = "{\"patientId\":\"" + PATIENT_ID + "\",\"title\":\"Visit note\","
                + "\"content\":\"Chief concern: chest pain\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"" + SUBJECT + "\"}";
        String id = field(request(HttpMethod.POST, "/api/v1/clinical-notes", body, null).getBody(), "id");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", "clerk-a");
        headers.set("X-Active-Organization", ORG_A);
        headers.set("If-Match", "\"1\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> denied = rest.method(HttpMethod.POST)
                .uri("/api/v1/clinical-notes/" + id + "/sign")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body("{\"attestation\":\"attested\"}")
                .retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());

        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/clinical-notes/" + id, null, null);
        assertEquals(NoteState.DRAFT.wire(), field(view.getBody(), "state"));
    }
}
