package com.aurevia.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * CLN-03 clause 5 acceptance: mapping a local code to the versioned diagnosis
 * catalog must not overwrite the code and free text the clinician submitted.
 *
 * <p>The submitted values are retained verbatim on the chart entry; the catalog
 * result is kept as a separate write-time snapshot (mapped code, mapped text and
 * catalog version). Every assertion is made through the real HTTP entry point
 * and, once, against the stored row. Only synthetic tenants and codes are used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_retain_original_it")
@ActiveProfiles("synthetic-auth")
class RetainOriginalCodeTextAfterMappingTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_retain_original_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @LocalServerPort
    int port;

    RestClient rest;

    @BeforeEach
    void initRest() {
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_retain_original_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_retain_original_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedPatient(statement);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_retain_original_it WITH (FORCE)");
        }
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
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void seedPatient(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'reception-a'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + ORG_A + "', '" + PATIENT + "', 1, 'MRN-RETAIN', 'CLINIC', "
                + "'Synthetic Retain Patient', 'synthetic retain patient', DATE '1990-05-20')");
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String org, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
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
     * Record a PROBLEM through the HTTP entry point. The code and code system are
     * included only when supplied so the missing-input boundary can be exercised.
     */
    private ResponseEntity<String> recordProblem(String text, String code, String codeSystem,
                                                  String actor, String org, String correlation) {
        String body = "{\"patientId\":\"" + PATIENT + "\",\"type\":\"PROBLEM\""
                + ",\"originalText\":\"" + text + "\""
                + (code == null ? "" : ",\"originalCode\":\"" + code + "\"")
                + (codeSystem == null ? "" : ",\"codeSystem\":\"" + codeSystem + "\"")
                + ",\"source\":\"CLINICIAN_OBSERVED\",\"presence\":\"PRESENT\""
                + ",\"clinicalAuthor\":\"" + actor + "\"}";
        return request(HttpMethod.POST, "/api/v1/chart/records/entries", body, actor, org, correlation);
    }

    /** Define and approve one effective diagnosis-catalog code for the mapping lookup. */
    private void seedCatalog(String code, String value) {
        ResponseEntity<String> defined = request(HttpMethod.POST,
                "/api/v1/catalogs/diagnosis/entries",
                "{\"code\":\"" + code + "\",\"value\":\"" + value + "\"}",
                "admin-a", ORG_A, "retain-catalog-define-" + code);
        assertEquals(HttpStatus.CREATED, defined.getStatusCode(), defined.getBody());
        ResponseEntity<String> approved = request(HttpMethod.POST,
                "/api/v1/catalogs/diagnosis/entries/" + code + "/versions/1/approve",
                "{\"note\":\"synthetic\"}", "admin-a", ORG_A, "retain-catalog-approve-" + code);
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
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

    @Test
    void mappingRetainsSubmittedOriginalCodeAndTextVerbatim() throws Exception {
        String originalCode = "LOC-77";
        String originalText = "patient words: burning sensation";
        String catalogText = "Approved catalog label";
        seedCatalog(originalCode, catalogText);

        ResponseEntity<String> created = recordProblem(originalText, originalCode, "diagnosis",
                "clinician-a", ORG_A, "retain-mapped-record");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        // The original code and free text survive the mapping unchanged, while the
        // catalog result is carried in its own separate snapshot fields.
        assertEquals(originalCode, field(created.getBody(), "originalCode"));
        assertEquals(originalText, field(created.getBody(), "originalText"));
        assertEquals("diagnosis", field(created.getBody(), "codeSystem"));
        assertEquals(originalCode, field(created.getBody(), "mappedCode"));
        assertEquals(catalogText, field(created.getBody(), "mappedText"));
        assertEquals("1", field(created.getBody(), "catalogVersion"));
        assertNotEquals(originalText, field(created.getBody(), "mappedText"),
                "the approved catalog label must not replace the clinician's free text");

        // The same retained values are returned by the read entry point.
        String id = field(created.getBody(), "id");
        ResponseEntity<String> read = request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + id, null, "clinician-a", ORG_A, "retain-mapped-read");
        assertEquals(HttpStatus.OK, read.getStatusCode(), read.getBody());
        assertEquals(originalCode, field(read.getBody(), "originalCode"));
        assertEquals(originalText, field(read.getBody(), "originalText"));
        assertEquals(catalogText, field(read.getBody(), "mappedText"));

        // And they are what was actually persisted, not just shaped in the response.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT original_code, original_text, mapped_code, mapped_text, catalog_version "
                            + "FROM public.chart_entry WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next(), "the mapped entry must be persisted");
                assertEquals(originalCode, result.getString("original_code"));
                assertEquals(originalText, result.getString("original_text"));
                assertEquals(originalCode, result.getString("mapped_code"));
                assertEquals(catalogText, result.getString("mapped_text"));
                assertEquals(1, result.getInt("catalog_version"));
            }
        }
    }

    @Test
    void verificationOfMappedEntryRetainsOriginalCodeAndText() {
        String originalCode = "LOC-88";
        String originalText = "patient words: intermittent dizziness";
        seedCatalog(originalCode, "Approved vertigo label");

        ResponseEntity<String> created = recordProblem(originalText, originalCode, "diagnosis",
                "clinician-a", ORG_A, "retain-verify-record");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", "clinician-a");
        headers.set("X-Active-Organization", ORG_A);
        headers.set("If-Match", "\"1\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> verified = rest.post().uri("/api/v1/chart/records/entries/" + id + "/verify")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("VERIFIED", field(verified.getBody(), "verification"));
        assertEquals("2", field(verified.getBody(), "version"));

        // A new version is created by verification, but the originally submitted
        // code and text are still retained verbatim on it.
        assertEquals(originalCode, field(verified.getBody(), "originalCode"));
        assertEquals(originalText, field(verified.getBody(), "originalText"));
        assertEquals("Approved vertigo label", field(verified.getBody(), "mappedText"));
    }

    @Test
    void recordingProblemWithOriginalCodeButNoCodeSystemIsRefused() {
        ResponseEntity<String> refused = recordProblem("unpaired code", "R51", null,
                "clinician-a", ORG_A, "retain-missing-system");

        // An original code without its system cannot be reliably mapped or
        // retained, so the rule refuses to create the entry at all.
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_CODE", field(refused.getBody(), "code"));
    }

    @Test
    void recordingEntryWithBlankOriginalTextIsRefused() {
        ResponseEntity<String> refused = recordProblem("   ", null, null,
                "clinician-a", ORG_A, "retain-blank-original");

        // There is no original free text to retain, so the entry is refused.
        assertEquals(422, refused.getStatusCode().value(), refused.getBody());
        assertEquals("INVALID_TEXT", field(refused.getBody(), "code"));
    }

    @Test
    void anotherTenantCannotReadRetainedOriginalCodeAndText() {
        String originalCode = "LOC-99";
        String originalText = "patient words: private retained note";
        seedCatalog(originalCode, "Approved private label");

        ResponseEntity<String> created = recordProblem(originalText, originalCode, "diagnosis",
                "clinician-a", ORG_A, "retain-private-record");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");

        ResponseEntity<String> crossTenant = request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + id, null, "clinician-b", ORG_B, "retain-private-cross");
        assertEquals(HttpStatus.NOT_FOUND, crossTenant.getStatusCode(), crossTenant.getBody());
        assertFalse(crossTenant.getBody().contains(originalText),
                "the retained original text must not leak across tenants");
    }
}
