package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * IAM-02 clause 1 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>A tenant admin invites and deactivates staff, assigns one of the bounded
 * G0 role keys, configures a location (facility) and retains the append-only
 * membership and role history. Every administration path is exercised through
 * the normal HTTP entry points and the durable, tenant-scoped rows are read
 * back under forced row-level security. Negative tests show a non-admin role
 * refused, unbounded input rejected, a forbidden state refused and the history
 * protected from mutation.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_iam02_clause1_it")
@ActiveProfiles("synthetic-auth")
class InviteDeactivateStaffAssignBoundedRolesConfigureTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_iam02_clause1_it";
    static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam02_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_iam02_clause1_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, "admin-a", "tenant_admin");
            seedStaff(statement, ORG_A, "reception-a", "reception");
            seedStaff(statement, ORG_B, "admin-b", "tenant_admin");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam02_clause1_it WITH (FORCE)");
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
        headers.set("X-Correlation-Id", "iam02-clause1-corr");
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

    private static String arrayBody(String json, String key) {
        String needle = "\"" + key + "\":[";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        int from = start + needle.length();
        int depth = 1;
        int i = from;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i++);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            }
        }
        return json.substring(from, i - 1);
    }

    private static int arraySize(String json, String key) {
        String body = arrayBody(json, key);
        int count = 0;
        boolean inString = false;
        for (char c : body.toCharArray()) {
            if (c == '"') {
                inString = !inString;
            } else if (c == '{' && !inString) {
                count++;
            }
        }
        return count;
    }

    private String adminId(String organization, String subject) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT id::text FROM public.user_identity WHERE subject = '" + subject + "'")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private int countWithOrg(String organization, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            try (ResultSet result = statement.executeQuery(sql)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    @Test
    void tenantAdminInvitesDeactivatesAssignsBoundedRolesAndConfiguresLocationsWithImmutableHistory()
            throws Exception {
        ResponseEntity<String> location = request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"IAM02-LOC\",\"name\":\"IAM02 Location\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, location.getStatusCode(), location.getBody());
        assertTrue(location.getBody().contains(ORG_A), location.getBody());
        assertTrue(location.getBody().contains("\"code\":\"IAM02-LOC\""), location.getBody());
        assertEquals(HttpStatus.OK,
                request(HttpMethod.GET, "/api/v1/facilities/IAM02-LOC", null, "admin-a", ORG_A).getStatusCode());

        ResponseEntity<String> invited = request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"iam02-clause-user\",\"displayName\":\"Clause User\",\"role\":\"reception\"}",
                "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, invited.getStatusCode(), invited.getBody());
        assertEquals("iam02-clause-user", field(invited.getBody(), "subject"));
        assertEquals("active", field(invited.getBody(), "status"));
        assertTrue(invited.getBody().contains("reception"), invited.getBody());

        String before = request(HttpMethod.GET, "/api/v1/staff/iam02-clause-user/history",
                null, "admin-a", ORG_A).getBody();
        assertEquals(1, arraySize(before, "memberships"), before);
        assertEquals(1, arraySize(before, "roleGrants"), before);
        String grants = arrayBody(before, "roleGrants");
        assertEquals("reception", field(grants, "roleKey"));
        assertEquals(adminId(ORG_A, "admin-a"), field(grants, "grantedBy"));
        assertFalse(field(grants, "validFrom").isBlank());
        assertTrue(grants.contains("\"validTo\":null"), grants);

        ResponseEntity<String> deactivated = request(HttpMethod.POST,
                "/api/v1/staff/iam02-clause-user/deactivate", "{}", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, deactivated.getStatusCode(), deactivated.getBody());
        assertEquals("deactivated", field(deactivated.getBody(), "status"));

        String after = request(HttpMethod.GET, "/api/v1/staff/iam02-clause-user/history",
                null, "admin-a", ORG_A).getBody();
        assertEquals(2, arraySize(after, "memberships"), after);
        String memberships = arrayBody(after, "memberships");
        assertTrue(memberships.contains("\"active\""), memberships);
        assertTrue(memberships.contains("\"revoked\""), memberships);
        assertEquals(1, arraySize(after, "roleGrants"), after);

        String actorId = adminId(ORG_A, "admin-a");
        assertEquals(1, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.user_identity WHERE subject = 'iam02-clause-user'"));
        assertEquals(2, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.membership m JOIN public.user_identity u "
                        + "ON u.organization_id = m.organization_id AND u.id = m.user_id "
                        + "WHERE u.subject = 'iam02-clause-user'"));
        assertEquals(1, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.role_assignment ra "
                        + "JOIN public.membership m ON m.organization_id = ra.organization_id "
                        + "AND m.id = ra.membership_id "
                        + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                        + "AND u.id = m.user_id "
                        + "WHERE u.subject = 'iam02-clause-user' AND ra.role_key = 'reception' "
                        + "AND ra.granted_by = '" + actorId + "'"));
        assertEquals(1, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.facility WHERE code = 'IAM02-LOC'"));
        assertEquals(0, countWithOrg(ORG_B,
                "SELECT count(*) FROM public.user_identity WHERE subject = 'iam02-clause-user'"));
    }

    @Test
    void receptionIsRefusedStaffAndLocationAdministration() {
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"DENIED-LOC\",\"name\":\"Denied Location\"}", "reception-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.CREATED, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"deny-target-a\",\"displayName\":\"Deny Target\",\"role\":\"reception\"}",
                "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"denied-user-a\",\"displayName\":\"Denied User\",\"role\":\"reception\"}",
                "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST,
                "/api/v1/staff/deny-target-a/deactivate", "{}", "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET,
                "/api/v1/staff/deny-target-a/history", null, "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void unboundedRoleDuplicateAndForbiddenStateAreRefused() {
        assertEquals(422, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"unbounded-a\",\"displayName\":\"Unbounded\",\"role\":\"superuser\"}",
                "admin-a", ORG_A).getStatusCode().value());
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"admin-a\",\"displayName\":\"Duplicate\",\"role\":\"reception\"}",
                "admin-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.NOT_FOUND, request(HttpMethod.POST,
                "/api/v1/staff/no-such-a/deactivate", "{}", "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CREATED, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"twice-a\",\"displayName\":\"Twice\",\"role\":\"reception\"}",
                "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, request(HttpMethod.POST,
                "/api/v1/staff/twice-a/deactivate", "{}", "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST,
                "/api/v1/staff/twice-a/deactivate", "{}", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void membershipAndRoleHistoryRejectMutation() throws Exception {
        // The application role has no UPDATE/DELETE grant, so this path proves the
        // privilege boundary. The owner path below proves the append-only trigger:
        // the owner keeps its privileges but forced RLS still scopes it to the tenant.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.membership SET status = 'active'"));
            assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.role_assignment"));
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            SQLException update = assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.membership SET status = 'active'"));
            assertEquals("44000", update.getSQLState(), update.getMessage());
            SQLException delete = assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.role_assignment"));
            assertEquals("44000", delete.getSQLState(), delete.getMessage());
        }
    }
}
