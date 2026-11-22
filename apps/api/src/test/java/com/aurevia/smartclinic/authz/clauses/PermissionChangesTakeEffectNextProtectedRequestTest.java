package com.aurevia.smartclinic.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * IAM-02 clause 3 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>The clause requires a permission change to take effect on the next protected
 * request. In the delivered G0 slice the in-scope permission change is staff
 * deactivation: it appends a revoked membership event, and every protected request
 * re-folds the authoritative membership and roles before it decides. The positive
 * test proves that a subject who can make a protected request while active is
 * refused on the very next request after deactivation, without any change to the
 * synthetic subject header or session. Negative tests prove that a wrong role and
 * another tenant cannot change the permission and that forbidden deactivation
 * states are refused. No production code, contract or migration is changed.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_iam02_clause3_it")
@ActiveProfiles("synthetic-auth")
class PermissionChangesTakeEffectNextProtectedRequestTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_iam02_clause3_it";
    static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam02_clause3_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_iam02_clause3_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "coadmin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_B, "admin-b", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_iam02_clause3_it WITH (FORCE)");
        }
    }

    /** Seed one synthetic member with an active membership and a role grant in the given organization. */
    private static void seedStaff(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
    }

    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.set("X-Correlation-Id", "iam02-clause3-corr");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> invite(String subject, String actor, String organization) {
        return request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"" + subject + "\",\"displayName\":\"Clause Three\","
                        + "\"role\":\"tenant_admin\"}", actor, organization);
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
     * Read the authoritative database fold for a subject in organization A.
     *
     * @param subject synthetic staff subject
     * @return {@code true} when the latest effective membership event is active
     */
    private boolean membershipActive(String subject) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = 'admin-a'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT public.has_active_membership('" + subject + "')")) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    @Test
    void deactivationDeniesTheNextProtectedRequestWithoutAnySessionChange() throws Exception {
        assertEquals(HttpStatus.CREATED, invite("clause3-target", "admin-a", ORG_A).getStatusCode());
        assertTrue(membershipActive("clause3-target"));

        // While active the subject may make a protected request through the normal HTTP entry point.
        ResponseEntity<String> before = request(HttpMethod.GET, "/api/v1/staff/clause3-target",
                null, "clause3-target", ORG_A);
        assertEquals(HttpStatus.OK, before.getStatusCode(), before.getBody());
        assertEquals("clause3-target", field(before.getBody(), "subject"));

        // An administrator changes the permission by appending a revoked membership event.
        ResponseEntity<String> deactivated = request(HttpMethod.POST,
                "/api/v1/staff/clause3-target/deactivate", "{}", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, deactivated.getStatusCode(), deactivated.getBody());
        assertEquals("deactivated", field(deactivated.getBody(), "status"));
        assertFalse(membershipActive("clause3-target"));

        // The very next protected request from the same subject is refused; no token or
        // session changed, so the denial comes from the re-folded authoritative membership.
        ResponseEntity<String> after = request(HttpMethod.GET, "/api/v1/staff/clause3-target",
                null, "clause3-target", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, after.getStatusCode(), after.getBody());
        assertTrue(after.getBody().contains("no active membership"), after.getBody());
    }

    @Test
    void wrongRoleCannotChangeAnotherSubjectsPermission() throws Exception {
        assertEquals(HttpStatus.CREATED, invite("clause3-victim", "admin-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/staff/clause3-victim/deactivate", "{}", "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET,
                "/api/v1/staff/clause3-victim", null, "reception-a", ORG_A).getStatusCode());

        // The refused caller did not change anything: the victim's permission is still active.
        assertTrue(membershipActive("clause3-victim"));
    }

    @Test
    void anotherTenantCannotChangeOrSeeTheSubject() throws Exception {
        assertEquals(HttpStatus.CREATED, invite("clause3-foreign", "admin-a", ORG_A).getStatusCode());

        // The other tenant's admin has no active membership in organization A, so a
        // deactivation attempted in that tenant is refused.
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/staff/clause3-foreign/deactivate", "{}", "admin-b", ORG_A).getStatusCode());
        // Reading the subject under the other tenant conceals its existence.
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.GET,
                "/api/v1/staff/clause3-foreign", null, "admin-b", ORG_B).getStatusCode());

        assertTrue(membershipActive("clause3-foreign"));
    }

    @Test
    void missingAndForbiddenDeactivationStatesAreRefused() {
        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/staff/no-such-clause3/deactivate", "{}", "admin-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.CREATED, invite("clause3-twice", "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/staff/clause3-twice/deactivate", "{}", "coadmin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST,
                "/api/v1/staff/clause3-twice/deactivate", "{}", "admin-a", ORG_A).getStatusCode());
    }
}
