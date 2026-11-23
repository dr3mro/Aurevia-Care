package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * CLN-03 clause 5 acceptance: mapping a chart entry to a diagnosis catalog
 * never overwrites the clinician's submitted original code or text.
 *
 * <p>The behavior is exercised through the real HTTP entry points against
 * PostgreSQL: catalog drafts and approvals go through the configuration
 * domain, a chart PROBLEM is recorded through
 * {@code POST /api/v1/chart/records/entries}, and the snapshot is read back
 * through the verify, single-entry and patient-chart routes. The submitted
 * {@code originalText}, {@code originalCode} and {@code codeSystem} must remain
 * byte-identical at every step whether a catalog hit exists, is concealed by
 * tenant isolation, is still a draft, or is later verified. All fixtures are
 * synthetic; no patient data or credential is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln_retain_it")
@ActiveProfiles("synthetic-auth")
class RetainOriginalCodeTextAfterMappingTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln_retain_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final Pattern FLAT_OBJECT = Pattern.compile("\\{[^{}]*}");

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln_retain_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln_retain_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "clinician-a", "clinician");
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_B, "clinician-b", "clinician");
            seedPatient(statement, ORG_A, "Synthetic Patient A");
            seedPatient(statement, ORG_B, "Synthetic Patient B");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln_retain_it WITH (FORCE)");
        }
    }

    /** Create one active organization member with a single role. */
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

    /** Seed one synthetic patient in the active organization. */
    private static void seedPatient(Statement statement, String org, String name) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        // The patient_record author trigger requires an existing user of the tenant.
        statement.execute("SET app.actor = 'clinician-" + (ORG_A.equals(org) ? "a" : "b") + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-CLN-RETAIN', 'CLINIC', '"
                + name + "', '" + name.toLowerCase() + "', DATE '1990-05-20')");
    }

    /** Send an HTTP request with the synthetic identity headers of one tenant. */
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

    /** Define one catalog draft version and return its allocated version number. */
    private String define(String family, String code, String value, String correlation) {
        ResponseEntity<String> defined = request(HttpMethod.POST,
                "/api/v1/catalogs/" + family + "/entries",
                "{\"code\":\"" + code + "\",\"value\":\"" + value + "\"}",
                "admin-a", ORG_A, correlation);
        assertEquals(HttpStatus.CREATED, defined.getStatusCode(), defined.getBody());
        return field(defined.getBody(), "version");
    }

    /** Approve one catalog draft version so it becomes the effective snapshot. */
    private void approve(String family, String code, int version, String correlation) {
        ResponseEntity<String> approved = request(HttpMethod.POST,
                "/api/v1/catalogs/" + family + "/entries/" + code + "/versions/" + version + "/approve",
                "{\"note\":\"synthetic governance approval\"}", "admin-a", ORG_A, correlation);
        assertEquals(HttpStatus.OK, approved.getStatusCode(), approved.getBody());
    }

    /** Record a chart PROBLEM with optional submitted code and code system. */
    private ResponseEntity<String> recordProblem(String text, String code, String system,
                                                  String actor, String org, String correlation) {
        String body = "{\"patientId\":\"" + PATIENT + "\",\"type\":\"PROBLEM\""
                + ",\"originalText\":\"" + text + "\""
                + (code == null ? "" : ",\"originalCode\":\"" + code + "\"")
                + (system == null ? "" : ",\"codeSystem\":\"" + system + "\"")
                + ",\"source\":\"CLINICIAN_OBSERVED\",\"presence\":\"PRESENT\""
                + ",\"clinicalAuthor\":\"" + actor + "\"}";
        return request(HttpMethod.POST, "/api/v1/chart/records/entries", body, actor, org, correlation);
    }

    /** Verify an entry at the version the clinician read through the real HTTP route. */
    private ResponseEntity<String> verifyAs(String subject, String org, String id, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", org);
        headers.set("If-Match", "\"" + version + "\"");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.post().uri("/api/v1/chart/records/entries/" + id + "/verify")
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Read a scalar field out of the flat JSON responses used by these tests. */
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
        return json.substring(start, end).trim();
    }

    /** Locate one flat chart-entry object by id inside a patient chart response. */
    private static String objectWithId(String json, String id) {
        Matcher matcher = FLAT_OBJECT.matcher(json);
        while (matcher.find()) {
            String object = matcher.group();
            if (id.equals(field(object, "id"))) {
                return object;
            }
        }
        return "";
    }

    /** Treat an absent or JSON-null field as no value. */
    private static boolean isAbsent(String value) {
        return value.isEmpty() || "null".equals(value);
    }

    @Test
    void retainsSubmittedOriginalCodeAndTextWhenAnApprovedCatalogMapsThem() {
        assertEquals("1", define("diagnosis", "R41", "Migraine (market v1)", "retain-r41-v1"));
        approve("diagnosis", "R41", 1, "retain-r41-v1-approval");

        ResponseEntity<String> created = recordProblem("Headache", "R41", "diagnosis",
                "clinician-a", ORG_A, "retain-mapped-originals");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        // The mapping snapshot is present, but it is distinct from what the
        // clinician submitted: neither original may be replaced by the hit.
        assertEquals("Migraine (market v1)", field(created.getBody(), "mappedText"));
        assertEquals("R41", field(created.getBody(), "mappedCode"));
        assertEquals("1", field(created.getBody(), "catalogVersion"));
        assertEquals("Headache", field(created.getBody(), "originalText"));
        assertEquals("R41", field(created.getBody(), "originalCode"));
        assertEquals("diagnosis", field(created.getBody(), "codeSystem"));
    }

    @Test
    void doesNotReplaceOriginalCodeOrTextWithAnotherTenantsCatalogMapping() {
        assertEquals("1", define("diagnosis", "R42", "Org A market text", "retain-r42-v1"));
        approve("diagnosis", "R42", 1, "retain-r42-v1-approval");

        // Org B records the same code; forced RLS hides Org A's catalog hit.
        ResponseEntity<String> created = recordProblem("Headache", "R42", "diagnosis",
                "clinician-b", ORG_B, "retain-other-tenant");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(isAbsent(field(created.getBody(), "mappedCode")));
        assertTrue(isAbsent(field(created.getBody(), "mappedText")));
        assertTrue(isAbsent(field(created.getBody(), "catalogVersion")));
        assertEquals("Headache", field(created.getBody(), "originalText"));
        assertEquals("R42", field(created.getBody(), "originalCode"));
        assertEquals("diagnosis", field(created.getBody(), "codeSystem"));
    }

    @Test
    void doesNotApplyAnUnapprovedDraftMappingToTheRetainedOriginal() {
        assertEquals("1", define("diagnosis", "R43", "Approved market text", "retain-r43-v1"));
        approve("diagnosis", "R43", 1, "retain-r43-v1-approval");
        assertEquals("2", define("diagnosis", "R43", "Draft market text", "retain-r43-v2"));

        ResponseEntity<String> created = recordProblem("Headache", "R43", "diagnosis",
                "clinician-a", ORG_A, "retain-draft-originals");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("Approved market text", field(created.getBody(), "mappedText"));
        assertEquals("1", field(created.getBody(), "catalogVersion"));
        assertEquals("Headache", field(created.getBody(), "originalText"));
        assertEquals("R43", field(created.getBody(), "originalCode"));
        assertEquals("diagnosis", field(created.getBody(), "codeSystem"));
    }

    @Test
    void retainedOriginalCodeAndTextSurviveVerificationAndReread() throws Exception {
        assertEquals("1", define("diagnosis", "R44", "Migraine (approved market text)", "retain-r44-v1"));
        approve("diagnosis", "R44", 1, "retain-r44-v1-approval");

        ResponseEntity<String> created = recordProblem("Headache", "R44", "diagnosis",
                "clinician-a", ORG_A, "retain-verify-originals");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals("Headache", field(created.getBody(), "originalText"));
        assertEquals("R44", field(created.getBody(), "originalCode"));
        assertEquals("diagnosis", field(created.getBody(), "codeSystem"));
        assertEquals("Migraine (approved market text)", field(created.getBody(), "mappedText"));

        ResponseEntity<String> verified = verifyAs("clinician-a", ORG_A, id, "1");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("VERIFIED", field(verified.getBody(), "verification"));
        assertEquals("Headache", field(verified.getBody(), "originalText"));
        assertEquals("R44", field(verified.getBody(), "originalCode"));
        assertEquals("diagnosis", field(verified.getBody(), "codeSystem"));
        assertEquals("Migraine (approved market text)", field(verified.getBody(), "mappedText"));
        assertEquals("R44", field(verified.getBody(), "mappedCode"));
        assertEquals("1", field(verified.getBody(), "catalogVersion"));

        ResponseEntity<String> reread = request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + id, null, "clinician-a", ORG_A, "retain-reread");
        assertEquals(HttpStatus.OK, reread.getStatusCode(), reread.getBody());
        assertEquals("Headache", field(reread.getBody(), "originalText"));
        assertEquals("R44", field(reread.getBody(), "originalCode"));
        assertEquals("diagnosis", field(reread.getBody(), "codeSystem"));
        assertEquals("Migraine (approved market text)", field(reread.getBody(), "mappedText"));

        // The list route uses a separate repository read path; the same
        // entry must carry unchanged originals there too.
        ResponseEntity<String> chart = request(HttpMethod.GET,
                "/api/v1/chart/records/patients/" + PATIENT + "/entries",
                null, "clinician-a", ORG_A, "retain-chart-reread");
        assertEquals(HttpStatus.OK, chart.getStatusCode(), chart.getBody());
        String listed = objectWithId(chart.getBody(), id);
        assertTrue(!listed.isEmpty(), "the verified entry must appear in the patient chart");
        assertEquals("Headache", field(listed, "originalText"));
        assertEquals("R44", field(listed, "originalCode"));
        assertEquals("diagnosis", field(listed, "codeSystem"));
        assertEquals("Migraine (approved market text)", field(listed, "mappedText"));
        assertEquals("R44", field(listed, "mappedCode"));
        assertEquals("1", field(listed, "catalogVersion"));

        // Under FORCE RLS the scoped connection sees the stored row; assert the
        // originals and the mapping snapshot as they were actually persisted.
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT original_text, original_code, code_system, mapped_code, mapped_text, "
                            + "catalog_version, verification FROM public.chart_entry "
                            + "WHERE id = CAST('" + id + "' AS uuid)")) {
                assertTrue(result.next(), "the verified entry must be stored");
                assertEquals("Headache", result.getString("original_text"));
                assertEquals("R44", result.getString("original_code"));
                assertEquals("diagnosis", result.getString("code_system"));
                assertEquals("R44", result.getString("mapped_code"));
                assertEquals("Migraine (approved market text)", result.getString("mapped_text"));
                assertEquals(1, result.getInt("catalog_version"));
                assertEquals("VERIFIED", result.getString("verification"));
            }
        }
    }

    @Test
    void retainsOriginalCodeAndTextWithoutMappingThroughVerificationAndReread() {
        ResponseEntity<String> created = recordProblem("Local excision", "PROC-77", "local-procedure",
                "clinician-a", ORG_A, "retain-unmapped-originals");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertEquals("Local excision", field(created.getBody(), "originalText"));
        assertEquals("PROC-77", field(created.getBody(), "originalCode"));
        assertEquals("local-procedure", field(created.getBody(), "codeSystem"));
        assertTrue(isAbsent(field(created.getBody(), "mappedCode")));
        assertTrue(isAbsent(field(created.getBody(), "mappedText")));
        assertTrue(isAbsent(field(created.getBody(), "catalogVersion")));

        ResponseEntity<String> verified = verifyAs("clinician-a", ORG_A, id, "1");
        assertEquals(HttpStatus.OK, verified.getStatusCode(), verified.getBody());
        assertEquals("Local excision", field(verified.getBody(), "originalText"));
        assertEquals("PROC-77", field(verified.getBody(), "originalCode"));
        assertEquals("local-procedure", field(verified.getBody(), "codeSystem"));
        assertTrue(isAbsent(field(verified.getBody(), "mappedCode")));
        assertTrue(isAbsent(field(verified.getBody(), "mappedText")));
        assertTrue(isAbsent(field(verified.getBody(), "catalogVersion")));

        ResponseEntity<String> reread = request(HttpMethod.GET,
                "/api/v1/chart/records/entries/" + id, null, "clinician-a", ORG_A, "retain-unmapped-reread");
        assertEquals(HttpStatus.OK, reread.getStatusCode(), reread.getBody());
        assertEquals("Local excision", field(reread.getBody(), "originalText"));
        assertEquals("PROC-77", field(reread.getBody(), "originalCode"));
        assertEquals("local-procedure", field(reread.getBody(), "codeSystem"));
        assertTrue(isAbsent(field(reread.getBody(), "mappedCode")));
        assertTrue(isAbsent(field(reread.getBody(), "mappedText")));
        assertTrue(isAbsent(field(reread.getBody(), "catalogVersion")));

        // With no submitted code the catalog lookup is skipped entirely; the
        // free-text original must still survive unchanged.
        ResponseEntity<String> withoutCode = recordProblem("Headache without a code", null, null,
                "clinician-a", ORG_A, "retain-no-code");
        assertEquals(HttpStatus.CREATED, withoutCode.getStatusCode(), withoutCode.getBody());
        assertEquals("Headache without a code", field(withoutCode.getBody(), "originalText"));
        assertTrue(isAbsent(field(withoutCode.getBody(), "originalCode")));
        assertTrue(isAbsent(field(withoutCode.getBody(), "mappedCode")));
        assertTrue(isAbsent(field(withoutCode.getBody(), "mappedText")));
    }
}
