package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
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

/** S-G0-05 acceptance: issuer-scoped MRN, normalized search and non-linking duplicate warnings (IAM-03). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_patient_it")
@ActiveProfiles("synthetic-auth")
class PatientRegistrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_patient_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

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
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_patient_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_B, "reception-b", "reception");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_it WITH (FORCE)");
        }
    }

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

    private ResponseEntity<String> register(String name, String mrn, String issuer, String dob,
                                            String unknown, String actor, String org) {
        String body = "{\"displayName\":\"" + name + "\",\"mrn\":\"" + mrn + "\",\"issuer\":\"" + issuer + "\""
                + (dob == null ? "" : ",\"dob\":\"" + dob + "\"")
                + (unknown == null ? "" : ",\"dobUnknownReason\":\"" + unknown + "\"")
                + ",\"language\":\"en\",\"contacts\":\"{}\",\"identifiers\":\"[]\",\"consents\":\"[]\"}";
        return request(HttpMethod.POST, "/api/v1/patients", body, actor, org);
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
    void registerAndReadPatient() {
        ResponseEntity<String> created = register("José Álvarez", "MRN-1", "CLINIC-A", "1990-05-20",
                null, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("José Álvarez", field(created.getBody(), "displayName"));
        assertEquals("jose alvarez", field(created.getBody(), "normalizedName"));

        String id = field(created.getBody(), "id");
        assertEquals("/api/v1/patients/" + id, created.getHeaders().getFirst(HttpHeaders.LOCATION));
        ResponseEntity<String> view = request(HttpMethod.GET, "/api/v1/patients/" + id,
                null, "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        assertEquals("José Álvarez", field(view.getBody(), "displayName"));
        assertTrue(view.getBody().contains("\"duplicates\""));
    }

    @Test
    void unknownDobReasonIsAcceptedAndCoded() {
        ResponseEntity<String> created = register("Unknown Name", "MRN-2", "CLINIC-A", null,
                "not_disclosed", "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("not_disclosed", field(created.getBody(), "dobUnknownReason"));
    }

    @Test
    void mrnIsIssuerScopedAndDuplicateWithinIssuerConflicts() {
        assertEquals(HttpStatus.CREATED,
                register("Same Mrn", "MRN-3", "CLINIC-A", "1980-01-01", null, "reception-a", ORG_A)
                        .getStatusCode());
        // Same MRN under a different issuer is a different patient.
        assertEquals(HttpStatus.CREATED,
                register("Same Mrn", "MRN-3", "CLINIC-B", "1980-01-01", null, "reception-a", ORG_A)
                        .getStatusCode());
        // Same MRN and issuer again conflicts.
        assertEquals(HttpStatus.CONFLICT,
                register("Again", "MRN-3", "CLINIC-A", "1980-01-01", null, "reception-a", ORG_A)
                        .getStatusCode());
        // Peer tenant can reuse the same local MRN and issuer.
        assertEquals(HttpStatus.CREATED,
                register("Peer", "MRN-3", "CLINIC-A", "1980-01-01", null, "reception-b", ORG_B)
                        .getStatusCode());
    }

    @Test
    void searchNormalizesWithoutLosingOriginalSpelling() {
        register("Fatima El-Amin", "MRN-4", "CLINIC-A", "1992-02-02", null, "reception-a", ORG_A);
        ResponseEntity<String> results = request(HttpMethod.GET,
                "/api/v1/patients?name=fatima", null, "reception-a", ORG_A);
        assertEquals(HttpStatus.OK, results.getStatusCode());
        assertTrue(results.getBody().contains("Fatima El-Amin"));
        assertFalse(results.getBody().contains("José Álvarez"));
    }

    @Test
    void duplicateWarningDoesNotLinkRecords() {
        register("Amina Khan", "MRN-5", "CLINIC-A", "1988-03-03", null, "reception-a", ORG_A);
        ResponseEntity<String> second = register("Amina Khan", "MRN-6", "CLINIC-A", "1988-03-03",
                null, "reception-a", ORG_A);
        assertEquals(HttpStatus.CREATED, second.getStatusCode());
        String id = field(second.getBody(), "id");
        String body = request(HttpMethod.GET, "/api/v1/patients/" + id, null, "reception-a", ORG_A).getBody();
        assertTrue(body.contains("\"duplicates\""));
        assertTrue(body.contains("MRN-5"), "likely duplicate should be listed as a candidate");
        assertFalse(body.contains("linked"), "warnings must not link records");
    }

    @Test
    void invalidInputsAndRolesAreDenied() {
        assertEquals(HttpStatus.FORBIDDEN,
                register("Bad Role", "MRN-7", "CLINIC-A", "1990-01-01", null, "auditor-a", ORG_A)
                        .getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                register("No Subject", "MRN-8", "CLINIC-A", "1990-01-01", null, null, ORG_A)
                        .getStatusCode());
        assertEquals(422, register("X", "MRN-9", "CLINIC-A", "1990-01-01", null, "reception-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, register("Valid Name", "MRN-9", "CLINIC-A", null, null, "reception-a", ORG_A)
                .getStatusCode().value());
        assertEquals(422, register("Valid Name", "MRN-9", "CLINIC-A", "1990-01-01", "known",
                "reception-a", ORG_A).getStatusCode().value());
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.GET, "/api/v1/patients/11111111-1111-1111-1111-111111111111",
                        null, "reception-a", ORG_A).getStatusCode());
    }
}
