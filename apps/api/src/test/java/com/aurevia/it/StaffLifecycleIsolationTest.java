package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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

/** S-G0-02 acceptance: invite, deactivate, immutable history, next-request denial, concealment (IAM-01/IAM-02/OPS-02). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_staff_it")
@ActiveProfiles("synthetic-auth")
class StaffLifecycleIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_staff_it";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_staff_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_staff_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_A, "revoked-a", "revoked");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', 'MAIN', 'Alpha Main')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_staff_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject, String status) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        String role = "revoked".equals(status) ? "tenant_admin" : status;
        String membershipStatus = "revoked".equals(status) ? "revoked" : "active";
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + membershipStatus + "' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private HttpHeaders headers(String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        if (correlation != null) {
            headers.set("X-Correlation-Id", correlation);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization, String correlation) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> invite(String subject, String displayName, String role,
                                          String actor, String organization, String correlation) {
        String body = "{\"subject\":\"" + subject + "\",\"displayName\":\"" + displayName
                + "\",\"role\":\"" + role + "\"}";
        return request(HttpMethod.POST, "/api/v1/staff", body, actor, organization, correlation);
    }

    private ResponseEntity<String> deactivate(String subject, String actor, String organization, String correlation) {
        return request(HttpMethod.POST, "/api/v1/staff/" + subject + "/deactivate",
                "{}", actor, organization, correlation);
    }

    private ResponseEntity<String> getStaff(String subject, String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/staff/" + subject, null, actor, organization, "read-corr");
    }

    private ResponseEntity<String> history(String subject, String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/staff/" + subject + "/history",
                null, actor, organization, "history-corr");
    }

    private ResponseEntity<String> getFacility(String actor, String organization) {
        return request(HttpMethod.GET, "/api/v1/facilities/MAIN", null, actor, organization, "facility-corr");
    }

    private String adminId(String organization, String subject) throws Exception {
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

    @Test
    void inviteRecordsGrantorAndEffectiveDates() throws Exception {
        ResponseEntity<String> created = invite("nurse-a", "Nurse A", "reception",
                "admin-a", ORG_A, "invite-corr-1");
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals("nurse-a", field(created.getBody(), "subject"));
        assertEquals("active", field(created.getBody(), "status"));
        assertTrue(created.getBody().contains("reception"));

        String historyJson = history("nurse-a", "admin-a", ORG_A).getBody();
        assertEquals(1, arraySize(historyJson, "memberships"));
        assertEquals(1, arraySize(historyJson, "roleGrants"));
        String grants = arrayBody(historyJson, "roleGrants");
        assertEquals("reception", field(grants, "roleKey"));
        assertEquals(adminId(ORG_A, "admin-a"), field(grants, "grantedBy"));
        assertFalse(field(grants, "validFrom").isBlank());
        assertTrue(grants.contains("\"validTo\":null"));
    }

    @Test
    void deactivationDeniesTheNextProtectedRequest() {
        assertEquals(HttpStatus.CREATED,
                invite("clinician-a", "Clinician A", "tenant_admin", "admin-a", ORG_A, "invite-corr-2")
                        .getStatusCode());
        assertEquals(HttpStatus.OK, getFacility("clinician-a", ORG_A).getStatusCode());

        assertEquals(HttpStatus.OK,
                deactivate("clinician-a", "admin-a", ORG_A, "deactivate-corr-2").getStatusCode());

        assertEquals(HttpStatus.FORBIDDEN, getFacility("clinician-a", ORG_A).getStatusCode());
        assertEquals("deactivated", field(getStaff("clinician-a", "admin-a", ORG_A).getBody(), "status"));
    }

    @Test
    void membershipAndRoleHistoryAreAppendOnly() throws Exception {
        invite("append-a", "Append A", "tenant_admin", "admin-a", ORG_A, "invite-corr-3");
        deactivate("append-a", "admin-a", ORG_A, "deactivate-corr-3");

        String historyJson = history("append-a", "admin-a", ORG_A).getBody();
        assertEquals(2, arraySize(historyJson, "memberships"));
        String memberships = arrayBody(historyJson, "memberships");
        assertTrue(memberships.contains("\"active\""));
        assertTrue(memberships.contains("\"revoked\""));
        assertEquals(1, arraySize(historyJson, "roleGrants"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.membership SET status = 'active'"));
            assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.role_assignment"));
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.user_identity SET display_name = 'x'"));
        }
    }

    @Test
    void crossTenantStaffRecordsAreConcealed() {
        assertEquals(HttpStatus.CREATED, invite("secret-a", "Secret A", "reception",
                "admin-a", ORG_A, "invite-corr-4").getStatusCode());

        assertEquals(HttpStatus.NOT_FOUND, getStaff("secret-a", "admin-b", ORG_B).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, history("secret-a", "admin-b", ORG_B).getStatusCode());
        // An actor with no membership in the selected org is unauthorized before any target lookup.
        assertEquals(HttpStatus.FORBIDDEN, getStaff("secret-a", "admin-a", ORG_B).getStatusCode());
    }

    @Test
    void unauthorizedAndInvalidRequestsAreDenied() {
        assertEquals(HttpStatus.FORBIDDEN, invite("x-a", "X A", "reception",
                "reception-a", ORG_A, "deny-corr-1").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, invite("x-b", "X B", "reception",
                null, ORG_A, "deny-corr-2").getStatusCode());
        assertEquals(422, invite("x-c", "X C", "reception", "admin-a", "bad-org", "deny-corr-3")
                .getStatusCode().value());
        assertEquals(422, invite("x", "X D", "reception", "admin-a", ORG_A, "deny-corr-4")
                .getStatusCode().value());
        assertEquals(422, invite("x-e", " ", "reception", "admin-a", ORG_A, "deny-corr-5")
                .getStatusCode().value());
        assertEquals(422, invite("x-f", "X F", "unknown_role", "admin-a", ORG_A, "deny-corr-6")
                .getStatusCode().value());
        assertEquals(HttpStatus.FORBIDDEN, getStaff("nurse-a", "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void duplicateAndUnknownSubjectsAreHandled() {
        assertEquals(HttpStatus.CREATED,
                invite("dupe-a", "Dupe A", "reception", "admin-a", ORG_A, "dupe-corr-1").getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                invite("dupe-a", "Dupe A", "reception", "admin-a", ORG_A, "dupe-corr-2").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                deactivate("missing-a", "admin-a", ORG_A, "dupe-corr-3").getStatusCode());
        assertEquals(HttpStatus.CREATED,
                invite("twice-a", "Twice A", "reception", "admin-a", ORG_A, "dupe-corr-4").getStatusCode());
        assertEquals(HttpStatus.OK,
                deactivate("twice-a", "admin-a", ORG_A, "dupe-corr-5").getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                deactivate("twice-a", "admin-a", ORG_A, "dupe-corr-6").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                getStaff("missing-a", "admin-a", ORG_A).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                history("missing-a", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void staffMutationsAreAudited() throws Exception {
        invite("audit-a", "Audit A", "reception", "admin-a", ORG_A, "audit-corr-1");
        deactivate("audit-a", "admin-a", ORG_A, "audit-corr-2");
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            List<String> actions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery(
                    "SELECT action FROM public.audit_event WHERE correlation_id IN "
                    + "('audit-corr-1','audit-corr-2') ORDER BY seq")) {
                while (result.next()) {
                    actions.add(result.getString(1));
                }
            }
            assertEquals(List.of("STAFF_INVITE", "STAFF_DEACTIVATE"), actions);
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE correlation_id IN "
                    + "('audit-corr-1','audit-corr-2') AND actor_id = '" + adminId(ORG_A, "admin-a")
                    + "' AND purpose = 'STAFF_ADMIN' AND outcome = 'SUCCESS'")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
    }
}
