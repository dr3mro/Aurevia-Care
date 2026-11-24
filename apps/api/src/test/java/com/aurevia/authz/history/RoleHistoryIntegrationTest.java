package com.aurevia.authz.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

/**
 * IAM-02 acceptance on real PostgreSQL: the ledger is auto-populated, immutable
 * even for the migration owner, tenant-isolated, and a permission change denies
 * the very next protected request (ARC-02, OPS-02).
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_rolehistory_it")
@ActiveProfiles("synthetic-auth")
class RoleHistoryIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_rolehistory_it";
    private static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_rolehistory_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_rolehistory_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin", "admin-a");
            seedUser(statement, ORG_A, "admin-rev", "tenant_admin", "admin-a");
            seedUser(statement, ORG_A, "reception-a", "reception", "admin-a");
            seedUser(statement, ORG_A, "employee-a", "reception", "admin-a");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin", "admin-b");
            seedUser(statement, ORG_B, "employee-b", "reception", "admin-b");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_rolehistory_it WITH (FORCE)");
        }
    }

    private static void seedUser(Statement statement, String org, String subject, String role,
                                 String inviter) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + inviter + "'");
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

    private ResponseEntity<String> history(String subject, String actor, String organization) {
        return rest.get()
                .uri("/api/v1/authz/role-history/" + subject)
                .header("X-Synthetic-Subject", actor)
                .header("X-Active-Organization", organization)
                .header("X-Correlation-Id", "role-history-corr")
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> deactivate(String subject) {
        return rest.post()
                .uri("/api/v1/staff/" + subject + "/deactivate")
                .header("X-Synthetic-Subject", "admin-a")
                .header("X-Active-Organization", ORG_A)
                .header("X-Correlation-Id", "role-history-deactivate")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    @Test
    void grantAndRevocationAreRecordedWithWhoWhenAndEffectiveWindow() {
        ResponseEntity<String> before = history("employee-a", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, before.getStatusCode());
        String body = before.getBody();
        assertTrue(body.contains("\"changeKind\":\"GRANT\""));
        assertTrue(body.contains("\"actor\":\"admin-a\""));
        assertTrue(body.contains("\"roleKey\":\"reception\""));
        assertTrue(body.contains("\"recordedAt\":"));
        assertTrue(body.contains("\"effectiveFrom\":"));

        assertEquals(HttpStatus.OK, deactivate("employee-a").getStatusCode());
        String after = history("employee-a", "admin-a", ORG_A).getBody();
        assertTrue(after.contains("\"changeKind\":\"REVOKE\""));
        assertTrue(after.contains("\"changeKind\":\"GRANT\""));
    }

    @Test
    void historyRowsCannotBeUpdatedOrDeleted() throws Exception {
        try (Connection migration = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = migration.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            int before = countRows(statement, ORG_A);
            assertTrue(before > 0);
            assertThrows(SQLException.class,
                    () -> statement.execute("UPDATE public.role_change_history SET actor = 'forged'"));
            assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.role_change_history"));
            assertThrows(SQLException.class,
                    () -> statement.execute("TRUNCATE public.role_change_history"));
            assertEquals(before, countRows(statement, ORG_A));
        }
        try (Connection app = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = app.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(SQLException.class,
                    () -> statement.execute("DELETE FROM public.role_change_history"));
        }
    }

    private static int countRows(Statement statement, String org) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT count(*) FROM public.role_change_history WHERE organization_id = '" + org + "'")) {
            result.next();
            return result.getInt(1);
        }
    }

    @Test
    void nonAdminCannotReadHistory() {
        assertEquals(HttpStatus.FORBIDDEN, history("employee-a", "reception-a", ORG_A).getStatusCode());
    }

    @Test
    void crossTenantSubjectIsConcealed() {
        assertEquals(HttpStatus.NOT_FOUND, history("admin-b", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void permissionChangeDeniesTheNextProtectedRequest() {
        assertEquals(HttpStatus.OK, history("employee-a", "admin-rev", ORG_A).getStatusCode());
        assertEquals(HttpStatus.OK, deactivate("admin-rev").getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, history("employee-a", "admin-rev", ORG_A).getStatusCode());
    }
}
