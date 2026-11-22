package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * CLN-03 clause 4 acceptance: a chart entry accepts a tenant-local procedure
 * code and maps a diagnosis only through the effective approved version of a
 * versioned market diagnosis catalog.
 *
 * <p>The behavior is exercised through the real HTTP entry points: catalog
 * drafts and approvals go through the configuration domain, a chart PROBLEM is
 * recorded through {@code POST /api/v1/chart/records/entries}, and the mapped
 * snapshot is read back from that response. A draft version, an unmapped local
 * code and another tenant's catalog must all leave the entry's original code
 * and text intact and must not supply a market mapping. All fixtures are
 * synthetic; no patient data or credential is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_cln_catalog_it")
@ActiveProfiles("synthetic-auth")
class SupportLocalProcedureCodesVersionedMarketDiagnosisTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_cln_catalog_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln_catalog_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_cln_catalog_it OWNER smartclinic_migrate");
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_cln_catalog_it WITH (FORCE)");
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

    private static void seedPatient(Statement statement, String org, String name) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        // The patient_record author trigger requires an existing user of the tenant.
        statement.execute("SET app.actor = 'clinician-" + (ORG_A.equals(org) ? "a" : "b") + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + PATIENT + "', 1, 'MRN-CLN-CATALOG', 'CLINIC', '"
                + name + "', '" + name.toLowerCase() + "', DATE '1990-05-20')");
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

    /** Define one catalog draft version; returns its allocated version number. */
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

    /** Record a chart PROBLEM carrying a local code through the real HTTP entry point. */
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

    private static boolean isAbsent(String value) {
        return value.isEmpty() || "null".equals(value);
    }

    @Test
    void mapsTheLatestApprovedDiagnosisCatalogVersionAndRetainsTheLocalCodeAndText() {
        assertEquals("1", define("diagnosis", "R51", "Headache (market v1)", "cat-r51-v1"));
        approve("diagnosis", "R51", 1, "cat-r51-v1-approval");
        assertEquals("2", define("diagnosis", "R51", "Migraine (market v2)", "cat-r51-v2"));
        approve("diagnosis", "R51", 2, "cat-r51-v2-approval");

        ResponseEntity<String> created = recordProblem("Headache", "R51", "diagnosis",
                "clinician-a", ORG_A, "chart-versioned-diagnosis");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("Migraine (market v2)", field(created.getBody(), "mappedText"));
        assertEquals("R51", field(created.getBody(), "mappedCode"));
        assertEquals("2", field(created.getBody(), "catalogVersion"));
        assertEquals("R51", field(created.getBody(), "originalCode"));
        assertEquals("Headache", field(created.getBody(), "originalText"));
    }

    @Test
    void doesNotMapAnUnapprovedDraftCatalogVersion() {
        assertEquals("1", define("diagnosis", "R52", "Approved market text", "cat-r52-v1"));
        approve("diagnosis", "R52", 1, "cat-r52-v1-approval");
        assertEquals("2", define("diagnosis", "R52", "Draft market text", "cat-r52-v2"));

        ResponseEntity<String> created = recordProblem("Dizziness", "R52", "diagnosis",
                "clinician-a", ORG_A, "chart-draft-diagnosis");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("Approved market text", field(created.getBody(), "mappedText"));
        assertEquals("1", field(created.getBody(), "catalogVersion"));
    }

    @Test
    void retainsALocalProcedureCodeVerbatimWithoutSupplyingAMarketMapping() {
        ResponseEntity<String> created = recordProblem("Local excision performed", "PROC-42",
                "local-procedure", "clinician-a", ORG_A, "chart-local-procedure");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("PROC-42", field(created.getBody(), "originalCode"));
        assertEquals("local-procedure", field(created.getBody(), "codeSystem"));
        assertTrue(isAbsent(field(created.getBody(), "mappedCode")));
        assertTrue(isAbsent(field(created.getBody(), "mappedText")));
        assertTrue(isAbsent(field(created.getBody(), "catalogVersion")));
    }

    @Test
    void doesNotMapACatalogEntryOwnedByAnotherTenant() {
        assertEquals("1", define("diagnosis", "R54", "Org A market text", "cat-r54-v1"));
        approve("diagnosis", "R54", 1, "cat-r54-v1-approval");

        ResponseEntity<String> created = recordProblem("Headache", "R54", "diagnosis",
                "clinician-b", ORG_B, "chart-other-tenant");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(isAbsent(field(created.getBody(), "mappedCode")));
        assertTrue(isAbsent(field(created.getBody(), "mappedText")));
        assertTrue(isAbsent(field(created.getBody(), "catalogVersion")));
        assertEquals("R54", field(created.getBody(), "originalCode"));
    }
}
