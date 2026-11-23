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
 * CLN-05 clause 4 acceptance: a signed clinical note revision cannot be edited.
 *
 * <p>This class proves only that clause through the real HTTP edit entry point
 * ({@code PUT /api/v1/clinical-notes/{id}}) on PostgreSQL. A signed revision is
 * immutable: the command service rejects the edit with 409 IMMUTABLE_REVISION and
 * the append-only ledger keeps the signed content and version unchanged. The
 * negative tests show the same refusal for a caller with the wrong role, a caller
 * in another tenant and a stale version precondition. Fixtures are synthetic.
 * Draft editing, signing metadata, amendments and entered-in-error belong to
 * their own acceptance tests and are not re-proved here.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_signedrev_it")
@ActiveProfiles("synthetic-auth")
class SignedRevisionsCannotEditedTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_signedrev_it";
    private static final String ORIGINAL_CONTENT = "Chief concern: chest pain";
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_signedrev_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_signedrev_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "clinician-a", "clinician", PATIENT_ID);
            seed(statement, ORG_A, "clerk-a", "reception", null);
            seed(statement, ORG_B, "clinician-b", "clinician", PATIENT_ID);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_signedrev_it WITH (FORCE)");
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
                    + "VALUES ('" + org + "', '" + patientId + "', 1, 'MRN-SIGNEDREV', 'CLINIC-A', "
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

    private String createAndSign() {
        String body = "{\"patientId\":\"" + PATIENT_ID + "\",\"title\":\"Visit note\","
                + "\"content\":\"" + ORIGINAL_CONTENT + "\",\"language\":\"en\","
                + "\"templateCode\":\"SOAP\",\"templateVersion\":1,"
                + "\"clinicalAuthorSubject\":\"clinician-a\"}";
        ResponseEntity<String> created = request(HttpMethod.POST, "/api/v1/clinical-notes",
                body, "clinician-a", ORG_A);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        ResponseEntity<String> signed = request(HttpMethod.POST, "/api/v1/clinical-notes/" + id + "/sign",
                "{\"attestation\":\"I attest I reviewed this note\"}", "clinician-a", ORG_A, "\"1\"");
        assertEquals(HttpStatus.OK, signed.getStatusCode(), signed.getBody());
        assertEquals("signed", field(signed.getBody(), "state"));
        assertEquals("2", field(signed.getBody(), "version"));
        return id;
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

    private ResponseEntity<String> view(String id) {
        return request(HttpMethod.GET, "/api/v1/clinical-notes/" + id, null, "clinician-a", ORG_A);
    }

    private void assertSignedContentSurvives(String id) {
        ResponseEntity<String> current = view(id);
        assertEquals(HttpStatus.OK, current.getStatusCode(), current.getBody());
        assertEquals(ORIGINAL_CONTENT, field(current.getBody(), "content"));
        assertEquals("2", field(current.getBody(), "version"));
        assertEquals("signed", field(current.getBody(), "state"));
        assertFalse(current.getBody().contains("Late content"),
                "a refused edit must never append a revision to the signed note");
    }

    @Test
    void editingSignedRevisionIsRefusedAndSignedContentSurvives() {
        String id = createAndSign();
        String edit = "{\"title\":\"Late edit\",\"content\":\"Late content\"}";

        ResponseEntity<String> denied = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clinician-a", ORG_A, "\"2\"");

        assertEquals(HttpStatus.CONFLICT, denied.getStatusCode(), denied.getBody());
        assertEquals("IMMUTABLE_REVISION", field(denied.getBody(), "code"));
        assertTrue(currentBodyContains(denied, "a signed revision cannot be edited"));
        assertSignedContentSurvives(id);
    }

    @Test
    void editingSignedRevisionByWrongRoleIsRefused() {
        String id = createAndSign();
        String edit = "{\"title\":\"Late edit\",\"content\":\"Late content\"}";

        ResponseEntity<String> denied = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clerk-a", ORG_A, "\"2\"");

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertSignedContentSurvives(id);
    }

    @Test
    void editingSignedRevisionFromAnotherTenantIsRefused() {
        String id = createAndSign();
        String edit = "{\"title\":\"Late edit\",\"content\":\"Late content\"}";

        ResponseEntity<String> denied = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clinician-b", ORG_B, "\"2\"");

        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), denied.getBody());
        assertSignedContentSurvives(id);
    }

    @Test
    void editingSignedRevisionWithStaleVersionIsRefused() {
        String id = createAndSign();
        String edit = "{\"title\":\"Late edit\",\"content\":\"Late content\"}";

        ResponseEntity<String> denied = request(HttpMethod.PUT, "/api/v1/clinical-notes/" + id,
                edit, "clinician-a", ORG_A, "\"1\"");

        assertEquals(HttpStatus.PRECONDITION_FAILED, denied.getStatusCode(), denied.getBody());
        assertEquals("STALE_VERSION", field(denied.getBody(), "code"));
        assertSignedContentSurvives(id);
    }

    private static boolean currentBodyContains(ResponseEntity<String> response, String text) {
        String body = response.getBody();
        return body != null && body.contains(text);
    }
}
