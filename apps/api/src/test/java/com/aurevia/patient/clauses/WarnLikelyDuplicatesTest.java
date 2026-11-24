package com.aurevia.patient.clauses;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * IAM-03 clause 4 proof through the normal HTTP entry point: reading a patient
 * returns likely-duplicate candidates computed from the normalized name and civil
 * date of birth. A warning only lists candidates; it never links or merges records.
 * Cross-tenant, unknown-date-of-birth and unauthorized reads must not produce a
 * warning. Synthetic fixtures only; no patient data or credentials are used.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_warn_duplicates_it")
@ActiveProfiles("synthetic-auth")
class WarnLikelyDuplicatesTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_warn_duplicates_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Point the HTTP client at the random port chosen for this test context. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Recreate the synthetic database from the authoritative migrations and seed bounded roles. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_warn_duplicates_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_warn_duplicates_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "reception-b", "reception");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
        }
    }

    /** Drop the synthetic database so each run starts from a clean state. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_warn_duplicates_it WITH (FORCE)");
        }
    }

    /** Seed one synthetic tenant member with a bounded role, mirroring the neighbouring fixtures. */
    private static void seed(Statement statement, String org, String subject, String role) throws Exception {
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

    /** Send a synthetic request, capturing any status instead of throwing on an error response. */
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

    /** Register a synthetic patient through the documented registration endpoint. */
    private ResponseEntity<String> register(String name, String mrn, String issuer, String dob,
                                            String unknown, String actor, String org) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer + "\""
                + (dob == null ? "" : ",\"dob\":\"" + dob + "\"")
                + (unknown == null ? "" : ",\"dobUnknownReason\":\"" + unknown + "\"")
                + ",\"language\":\"en\",\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        return request(HttpMethod.POST, "/api/v1/patients", body, actor, org);
    }

    /** Read a patient view as JSON, asserting the read succeeded and parsing the body. */
    private JsonNode view(String id, String actor, String org) {
        ResponseEntity<String> response = request(HttpMethod.GET, "/api/v1/patients/" + id,
                null, actor, org);
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        try {
            return new ObjectMapper().readTree(response.getBody());
        } catch (Exception invalid) {
            throw new AssertionError("patient view must be valid JSON", invalid);
        }
    }

    /** Read a simple string field from a JSON body, matching the neighbouring fixtures. */
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
    void warnsWhenAnotherPatientSharesNormalizedNameAndDob() {
        register("Amina Khan", "MRN-WD-1", "CLINIC-A", "1988-03-03", null, "reception-a", ORG_A);
        ResponseEntity<String> second = register("Amína  Khan", "MRN-WD-2", "CLINIC-A",
                "1988-03-03", null, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, second.getStatusCode(), second.getBody());
        String id = field(second.getBody(), "id");

        JsonNode view = view(id, "reception-a", ORG_A);

        assertEquals("MRN-WD-2", view.get("patient").get("mrn").asString());
        JsonNode duplicates = view.get("duplicates");
        assertEquals(1, duplicates.size());
        assertEquals("MRN-WD-1", duplicates.get(0).get("mrn").asString());
        assertEquals("Amina Khan", duplicates.get(0).get("displayName").asString());
        assertFalse(view.toString().contains("linked"), "a warning must not link records");
    }

    @Test
    void doesNotWarnAboutAPeerTenantsDuplicate() {
        register("Shared Person", "MRN-WD-3", "CLINIC-A", "1977-07-07", null, "reception-b", ORG_B);
        ResponseEntity<String> local = register("Shared Person", "MRN-WD-4", "CLINIC-A",
                "1977-07-07", null, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, local.getStatusCode(), local.getBody());

        JsonNode view = view(field(local.getBody(), "id"), "reception-a", ORG_A);

        assertEquals(0, view.get("duplicates").size());
        assertFalse(view.toString().contains("MRN-WD-3"), "another tenant's MRN must not leak into a warning");
    }

    @Test
    void doesNotWarnWhenDateOfBirthIsUnknown() {
        register("No Dob Person", "MRN-WD-5", "CLINIC-A", null, "not_disclosed", "reception-a", ORG_A);
        ResponseEntity<String> second = register("No Dob Person", "MRN-WD-6", "CLINIC-A",
                null, "not_disclosed", "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, second.getStatusCode(), second.getBody());

        JsonNode view = view(field(second.getBody(), "id"), "reception-a", ORG_A);

        assertEquals(0, view.get("duplicates").size());
        assertTrue(view.get("patient").get("dob").isNull());
    }

    @Test
    void doesNotWarnWhenNameOrDateOfBirthDiffers() {
        register("Different Person", "MRN-WD-7", "CLINIC-A", "1970-01-01", null, "reception-a", ORG_A);
        ResponseEntity<String> differentName = register("Other Person", "MRN-WD-8", "CLINIC-A",
                "1970-01-01", null, "reception-a", ORG_A);
        ResponseEntity<String> differentDob = register("Different Person", "MRN-WD-9", "CLINIC-A",
                "1970-01-02", null, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, differentName.getStatusCode(), differentName.getBody());
        assertEquals(HttpStatus.CREATED, differentDob.getStatusCode(), differentDob.getBody());

        assertEquals(0, view(field(differentName.getBody(), "id"), "reception-a", ORG_A)
                .get("duplicates").size());
        assertEquals(0, view(field(differentDob.getBody(), "id"), "reception-a", ORG_A)
                .get("duplicates").size());
    }

    @Test
    void deniesDuplicateWarningToRoleWithoutPatientRead() {
        ResponseEntity<String> created = register("Guarded Person", "MRN-WD-10", "CLINIC-A",
                "1991-01-01", null, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());

        ResponseEntity<String> denied = request(HttpMethod.GET,
                "/api/v1/patients/" + field(created.getBody(), "id"), null, "auditor-a", ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertFalse(denied.getBody().contains("duplicates"), "a denied read must not disclose candidates");
    }
}
