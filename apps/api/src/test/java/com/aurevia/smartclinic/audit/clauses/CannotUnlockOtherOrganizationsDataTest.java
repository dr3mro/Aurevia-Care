package com.aurevia.smartclinic.audit.clauses;

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
 * IAM-06 clause 3: emergency access cannot unlock another organization's data.
 *
 * <p>A qualified clinician may open a reasoned, time-limited break-glass grant
 * only inside the active organization. The grant is stored with that
 * organization, is invisible under forced row-level security from every other
 * organization, and a subject with no active membership cannot open a grant by
 * selecting a foreign organization. Synthetic fixtures only; real PostgreSQL.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_audit_unlock_it")
@ActiveProfiles("synthetic-auth")
class CannotUnlockOtherOrganizationsDataTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_audit_unlock_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    /** Start the HTTP client for the random test port. */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** Apply every migration to a fresh synthetic database and seed two approved tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_audit_unlock_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_audit_unlock_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedClinician(statement, ORG_A, "clinician-own");
            seedClinician(statement, ORG_A, "clinician-foreign");
            seedClinician(statement, ORG_A, "clinician-tenant");
            seedSubject(statement, ORG_B, "auditor-b", "privacy_auditor");
            approveEmergencyAccess(statement, ORG_A, "MAIN", "Alpha Main", "clinician-own");
            // Organization B also has emergency access approved and a MAIN facility, so the
            // cross-organization denial is caused by isolation, not by a missing configuration.
            approveEmergencyAccess(statement, ORG_B, "MAIN", "Beta Main", "auditor-b");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_audit_unlock_it WITH (FORCE)");
        }
    }

    /** Create an organization with one active clinician membership. */
    private static void seedClinician(Statement statement, String organization, String subject)
            throws Exception {
        seedSubject(statement, organization, subject, "clinician");
    }

    /** Create an active membership and one role assignment for a synthetic subject. */
    private static void seedSubject(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + organization + "', 'Org " + subject + "' WHERE NOT EXISTS "
                + "(SELECT 1 FROM public.organization WHERE organization_id = '" + organization + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Seed an in-scope facility and an approved emergency-access configuration for a tenant. */
    private static void approveEmergencyAccess(Statement statement, String organization,
                                               String facilityCode, String facilityName, String actor)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                + "VALUES ('" + organization + "', '" + facilityCode + "', '" + facilityName + "')");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) "
                + "VALUES ('" + organization + "', 'access.emergency.enabled', 1, 'true')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + organization + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = 'access.emergency.enabled' AND version = 1");
    }

    /** Send an authenticated request carrying the selected active organization. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", "audit-unlock-it-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Open a short emergency grant at the named facility for a synthetic clinician. */
    private ResponseEntity<String> grant(String subject, String organization) {
        return request(HttpMethod.POST, "/api/v1/access/emergency",
                "{\"facilityCode\":\"MAIN\",\"reason\":\"patient in distress\",\"durationMinutes\":10}",
                subject, organization);
    }

    /** Read a scalar string field from a compact JSON response body. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        start += needle.length();
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end);
    }

    /** Count grants visible to one organization, optionally narrowed to a grant id. */
    private static int countGrants(Statement statement, String organization, String grantId) throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        String predicate = grantId == null ? "" : " AND id::text = '" + grantId + "'";
        try (ResultSet result = statement.executeQuery(
                "SELECT count(*) FROM public.emergency_access_grant WHERE 1 = 1" + predicate)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    /** A clinician can unlock only their own organization with an emergency grant. */
    @Test
    void emergencyGrantUnlocksOnlyTheCliniciansOwnOrganization() throws Exception {
        ResponseEntity<String> created = grant("clinician-own", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(id.isEmpty(), created.getBody());

        ResponseEntity<String> active = request(HttpMethod.GET, "/api/v1/access/emergency/active",
                null, "clinician-own", ORG_A);
        assertEquals(HttpStatus.OK, active.getStatusCode(), active.getBody());
        assertTrue(active.getBody().contains("\"allowed\":true"), active.getBody());
        assertTrue(active.getBody().contains("ACTIVE"), active.getBody());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            assertEquals(1, countGrants(statement, ORG_A, id));
            assertEquals(0, countGrants(statement, ORG_B, id));
        }
    }

    /** Selecting a foreign organization cannot open an emergency grant there (negative). */
    @Test
    void clinicianCannotUnlockAnotherOrganizationBySelectingIt() throws Exception {
        ResponseEntity<String> refused = grant("clinician-foreign", ORG_B);

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode(), refused.getBody());
        assertTrue(refused.getBody() == null || !refused.getBody().contains("\"id\""), refused.getBody());
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            assertEquals(0, countGrants(statement, ORG_B, null));
        }
    }

    /** An existing grant cannot be seen or reviewed from another organization (negative). */
    @Test
    void anotherOrganizationCannotSeeOrReviewTheGrant() throws Exception {
        ResponseEntity<String> created = grant("clinician-tenant", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        String id = field(created.getBody(), "id");
        assertFalse(id.isEmpty(), created.getBody());

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            assertEquals(0, countGrants(statement, ORG_B, id));
            assertEquals(1, countGrants(statement, ORG_A, id));
        }
        ResponseEntity<String> reviewed = request(HttpMethod.POST,
                "/api/v1/access/emergency/" + id + "/review",
                "{\"note\":\"checked\"}", "auditor-b", ORG_B);
        assertEquals(HttpStatus.NOT_FOUND, reviewed.getStatusCode(), reviewed.getBody());
    }
}
