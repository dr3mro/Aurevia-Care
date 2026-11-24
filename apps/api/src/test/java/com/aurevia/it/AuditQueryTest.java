package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

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

/** S-G0-03b acceptance: tenant-scoped audit query with cursor paging and audit-of-read (OPS-02/ARC-03). */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_audit_it")
@ActiveProfiles("synthetic-auth")
class AuditQueryTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_audit_it";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_audit_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_audit_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        Instant now = Instant.now();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin");
            seed(statement, ORG_B, "admin-b", "tenant_admin");
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_A, "reception-a", "reception");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            for (int i = 0; i < 5; i++) {
                String when = now.minus(Duration.ofMinutes(10 - i)).toString();
                statement.execute("INSERT INTO public.audit_event "
                        + "(organization_id, occurred_at, action, resource_type, resource_id, "
                        + "purpose, scope, correlation_id, outcome) VALUES ('" + ORG_A + "', '" + when
                        + "', 'FACILITY_CREATE', 'FACILITY', 'F" + i + "', 'FACILITY_ADMIN', "
                        + "'FACILITY', 'seed-" + i + "', 'SUCCESS')");
            }
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.audit_event (organization_id, action, outcome, correlation_id) "
                    + "VALUES ('" + ORG_B + "', 'FACILITY_CREATE', 'SUCCESS', 'seed-b')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_audit_it WITH (FORCE)");
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

    private ResponseEntity<String> query(String subject, String org, String queryString) {
        String uri = "/api/v1/audit-events" + (queryString == null ? "" : "?" + queryString);
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (org != null) {
            headers.set("X-Active-Organization", org);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.method(HttpMethod.GET).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .retrieve()
                .onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    private static int count(String body, String token) {
        int count = 0;
        int index = 0;
        while ((index = body.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    @Test
    void tenantAdminReadsOnlyItsOwnAuditTrail() {
        ResponseEntity<String> response = query("admin-a", ORG_A, "limit=50");
        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("seed-4"));
        assertTrue(response.getBody().contains("seed-0"));
        assertFalse(response.getBody().contains("seed-b"), "tenant B rows must be concealed");
    }

    @Test
    void privacyAuditorCanReadTheTrail() {
        assertEquals(HttpStatus.OK, query("auditor-a", ORG_A, "limit=10").getStatusCode());
    }

    @Test
    void receptionAndCrossTenantAreDenied() {
        assertEquals(HttpStatus.FORBIDDEN, query("reception-a", ORG_A, null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, query(null, ORG_A, null).getStatusCode());
        assertEquals(422, query("admin-a", "bad-org", null).getStatusCode().value());
        assertEquals(HttpStatus.FORBIDDEN, query("admin-a", ORG_B, null).getStatusCode());
    }

    @Test
    void cursorPagingIsStableAndBounded() {
        ResponseEntity<String> first = query("admin-a", ORG_A, "limit=2");
        assertEquals(HttpStatus.OK, first.getStatusCode());
        String body = first.getBody();
        assertTrue(body.contains("\"nextCursor\":\""));
        String cursor = body.substring(body.indexOf("\"nextCursor\":\"") + 14);
        cursor = cursor.substring(0, cursor.indexOf('"'));
        ResponseEntity<String> second = query("admin-a", ORG_A, "limit=2&cursor=" + cursor);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertTrue(second.getBody().contains("\"seq\":"));
        assertFalse(second.getBody().contains("\"nextCursor\":\"" + cursor + "\""),
                "cursor must advance to a different page");
    }

    @Test
    void filtersAreApplied() throws Exception {
        ResponseEntity<String> response = query("admin-a", ORG_A,
                "action=FACILITY_CREATE&outcome=SUCCESS&resourceType=FACILITY&limit=50"
                + "&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("FACILITY_CREATE"));
        assertFalse(response.getBody().contains("STAFF_INVITE"));
    }

    @Test
    void invalidInputsAreRejected() {
        assertEquals(422, query("admin-a", ORG_A, "limit=0").getStatusCode().value());
        assertEquals(422, query("admin-a", ORG_A, "limit=201").getStatusCode().value());
        assertEquals(422, query("admin-a", ORG_A, "cursor=abc").getStatusCode().value());
        assertEquals(422, query("admin-a", ORG_A, "cursor=0").getStatusCode().value());
        assertEquals(422, query("admin-a", ORG_A, "from=nope").getStatusCode().value());
        assertEquals(422, query("admin-a", ORG_A, "action=bad%20action").getStatusCode().value());
    }

    @Test
    void everyQueryAppendsAnAuditEvent() throws Exception {
        query("admin-a", ORG_A, "correlation=unused");
        // The controller does not accept a correlation parameter; the interceptor
        // generates one. Assert that an AUDIT_READ row exists in this tenant.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_event WHERE action = 'AUDIT_READ' AND outcome = 'SUCCESS'")) {
                assertTrue(result.next());
                assertTrue(result.getInt(1) >= 1, "each successful query must be audited");
            }
        }
    }
}
