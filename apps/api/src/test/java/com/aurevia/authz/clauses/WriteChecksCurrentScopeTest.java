package com.aurevia.authz.clauses;

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
 * IAM-01 clause 6 acceptance: every write re-checks active membership and role
 * against the organization selected for the current request, so authority is
 * not inherited from a different tenant or a stale membership. The write is a
 * facility create through its real HTTP entry point; a denied write is
 * confirmed to have left no row behind. Request-context binding and cleanup are
 * covered by {@code FacilityIsolationTest}; this class proves only that the
 * write re-checks the current scope.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_scope_it")
@ActiveProfiles("synthetic-auth")
class WriteChecksCurrentScopeTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_scope_it";
    private static final String ADMIN = System.getenv().getOrDefault(
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
            statement.execute("DROP DATABASE IF EXISTS orevia_scope_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_scope_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin", "active");
            seedUser(statement, ORG_A, "reception-a", "reception", "active");
            seedUser(statement, ORG_A, "revoked-a", "tenant_admin", "revoked");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin", "active");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_scope_it WITH (FORCE)");
        }
    }

    private static void seedUser(Statement statement, String org, String subject, String role,
                                 String membershipStatus) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + membershipStatus + "' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "'");
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

    private ResponseEntity<String> write(String code, String subject, String organization, String correlation) {
        String body = "{\"code\":\"" + code + "\",\"name\":\"Scope " + code + "\"}";
        return rest.method(HttpMethod.POST).uri("/api/v1/facilities")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)))
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> read(String code, String subject, String organization) {
        return rest.method(HttpMethod.GET).uri("/api/v1/facilities/" + code)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, "read-" + code)))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    @Test
    void writeSucceedsWhenActorHasActiveMembershipInCurrentScope() {
        ResponseEntity<String> created = write("SCOPEOK", "admin-a", ORG_A, "scope-ok-write");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertTrue(created.getBody().contains(ORG_A));
        ResponseEntity<String> read = read("SCOPEOK", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertTrue(read.getBody().contains(ORG_A));
    }

    @Test
    void writeByActorWithoutMembershipInCurrentScopeIsDenied() {
        // admin-b is a tenant_admin in ORG_B, but the current scope is ORG_A.
        ResponseEntity<String> denied = write("SCOPEB", "admin-b", ORG_A, "scope-other-tenant");

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals(HttpStatus.NOT_FOUND, read("SCOPEB", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void writeByActorWithRevokedMembershipInCurrentScopeIsDenied() {
        // The role grant still exists, but the membership is revoked in the current scope.
        ResponseEntity<String> denied = write("SCOPEREV", "revoked-a", ORG_A, "scope-revoked");

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals(HttpStatus.NOT_FOUND, read("SCOPEREV", "admin-a", ORG_A).getStatusCode());
    }

    @Test
    void writeWithoutRequiredRoleInCurrentScopeIsDenied() {
        // Active member in the current scope, but reception may not perform FACILITY_WRITE.
        ResponseEntity<String> denied = write("SCOPEROLE", "reception-a", ORG_A, "scope-wrong-role");

        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(), denied.getBody());
        assertEquals(HttpStatus.NOT_FOUND, read("SCOPEROLE", "admin-a", ORG_A).getStatusCode());
    }
}
