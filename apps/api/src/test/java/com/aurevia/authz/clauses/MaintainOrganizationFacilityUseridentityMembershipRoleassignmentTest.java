package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * IAM-01 clause 1 acceptance against real PostgreSQL with synthetic fixtures.
 *
 * <p>An authenticated tenant admin maintains the active Organization scope, a
 * Facility, a UserIdentity, a Membership and a RoleAssignment. Every write is
 * refused without active membership, the required action permission, a matching
 * tenant scope or valid input. The rule is exercised through the normal HTTP
 * entry points and the durable rows are read back under forced row-level
 * security, so a result proves end-to-end behavior rather than unit coverage.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_iam01_clause1_it")
@ActiveProfiles("synthetic-auth")
class MaintainOrganizationFacilityUseridentityMembershipRoleassignmentTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_iam01_clause1_it";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam01_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_iam01_clause1_it OWNER aurevia_migrate");
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
            // A facility that exists only in organization B, used for tenant-concealment checks.
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', 'BONLY-B', 'Org B Only')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_iam01_clause1_it WITH (FORCE)");
        }
    }

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
        headers.set("X-Correlation-Id", "iam01-clause1-corr");
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
    void tenantAdminMaintainsAllFiveEntitiesInsideTheActiveOrganization() throws Exception {
        ResponseEntity<String> facility = request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"CLAUSE-A\",\"name\":\"Clause Clinic\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, facility.getStatusCode(), facility.getBody());
        assertTrue(facility.getBody().contains(ORG_A), facility.getBody());
        assertTrue(facility.getBody().contains("\"code\":\"CLAUSE-A\""), facility.getBody());

        ResponseEntity<String> invited = request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"clause-user-a\",\"displayName\":\"Clause User\",\"role\":\"reception\"}",
                "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, invited.getStatusCode(), invited.getBody());
        assertEquals("clause-user-a", field(invited.getBody(), "subject"));
        assertEquals("active", field(invited.getBody(), "status"));

        ResponseEntity<String> member = request(HttpMethod.GET, "/api/v1/staff/clause-user-a",
                null, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, member.getStatusCode(), member.getBody());
        assertTrue(member.getBody().contains("reception"), member.getBody());

        String history = request(HttpMethod.GET, "/api/v1/staff/clause-user-a/history",
                null, "admin-a", ORG_A).getBody();
        assertEquals(1, arraySize(history, "memberships"), history);
        assertEquals(1, arraySize(history, "roleGrants"), history);
        String grants = arrayBody(history, "roleGrants");
        assertEquals("reception", field(grants, "roleKey"));
        assertEquals(adminId(ORG_A, "admin-a"), field(grants, "grantedBy"));

        String actorId = adminId(ORG_A, "admin-a");
        assertEquals(1, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.user_identity WHERE subject = 'clause-user-a'"));
        assertEquals(1, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.membership m JOIN public.user_identity u "
                        + "ON u.organization_id = m.organization_id AND u.id = m.user_id "
                        + "WHERE u.subject = 'clause-user-a' AND m.status = 'active'"));
        assertEquals(1, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.role_assignment ra "
                        + "JOIN public.membership m ON m.organization_id = ra.organization_id "
                        + "AND m.id = ra.membership_id "
                        + "JOIN public.user_identity u ON u.organization_id = m.organization_id "
                        + "AND u.id = m.user_id "
                        + "WHERE u.subject = 'clause-user-a' AND ra.role_key = 'reception' "
                        + "AND ra.granted_by = '" + actorId + "'"));
        assertEquals(1, countWithOrg(ORG_A,
                "SELECT count(*) FROM public.facility WHERE code = 'CLAUSE-A'"));
        assertEquals(0, countWithOrg(ORG_B,
                "SELECT count(*) FROM public.user_identity WHERE subject = 'clause-user-a'"));
    }

    @Test
    void receptionCannotMaintainFacilitiesOrStaff() {
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"NOPE-A\",\"name\":\"Not Allowed\"}", "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"nope-a\",\"displayName\":\"Not Allowed\",\"role\":\"reception\"}",
                "reception-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, request(HttpMethod.GET, "/api/v1/staff",
                null, "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void anotherTenantIsDeniedAndItsRecordsStayConcealed() {
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.GET, "/api/v1/staff/admin-b", null, "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                request(HttpMethod.GET, "/api/v1/facilities/BONLY-B", null, "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                request(HttpMethod.GET, "/api/v1/staff/admin-b", null, "admin-a", ORG_B).getStatusCode());
        assertEquals(422,
                request(HttpMethod.GET, "/api/v1/facilities/BONLY-B", null, "admin-a", null)
                        .getStatusCode().value());
    }

    @Test
    void invalidMaintenanceInputIsRejected() {
        assertEquals(422, request(HttpMethod.POST, "/api/v1/facilities",
                "{\"code\":\"lower\",\"name\":\"Lower\"}", "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"bad-role-a\",\"displayName\":\"Bad Role\",\"role\":\"unknown_role\"}",
                "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"x\",\"displayName\":\"Short\",\"role\":\"reception\"}",
                "admin-a", ORG_A).getStatusCode().value());
        assertEquals(422, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"bad-name-a\",\"displayName\":\" \",\"role\":\"reception\"}",
                "admin-a", ORG_A).getStatusCode().value());
    }

    @Test
    void invitingAnExistingSubjectIsRejected() {
        assertEquals(HttpStatus.CONFLICT, request(HttpMethod.POST, "/api/v1/staff",
                "{\"subject\":\"reception-a\",\"displayName\":\"Duplicate\",\"role\":\"reception\"}",
                "admin-a", ORG_A).getStatusCode());
    }
}
