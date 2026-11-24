package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.tenancy.FacilityService;

import org.flywaydb.core.Flyway;

/** G0 acceptance N1-N4, E1-E8 against a real PostgreSQL with two synthetic tenants. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("synthetic-auth")
class FacilityIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;
    @Autowired FacilityService service;

    @org.junit.jupiter.api.BeforeEach
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
            statement.execute("DROP DATABASE IF EXISTS orevia_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin", "active", "MAIN", "Alpha Main");
            seed(statement, ORG_B, "admin-b", "tenant_admin", "active", "MAIN", "Beta Main");
            seed(statement, ORG_A, "reception-a", "reception", "active", null, null);
            seed(statement, ORG_A, "revoked-a", "tenant_admin", "revoked", null, null);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_it WITH (FORCE)");
        }
    }

    private static void seed(Statement statement, String org, String subject, String role,
                             String membershipStatus, String facilityCode, String facilityName)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + membershipStatus + "' FROM public.user_identity "
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
        if (facilityCode != null) {
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + org + "', '" + facilityCode + "', '" + facilityName + "')");
        }
    }

    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> get(String subject, String organization) {
        return rest.method(HttpMethod.GET).uri("/api/v1/facilities/MAIN")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String body, String subject, String organization) {
        return rest.method(HttpMethod.POST).uri("/api/v1/facilities")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    @Test
    void tenantAReadsOnlyItsOwnFacility() {
        ResponseEntity<String> response = get("admin-a", ORG_A);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(ORG_A));
        assertNotNull(response.getHeaders().getFirst("X-Correlation-Id"));
    }

    @Test
    void tenantBReadsOnlyItsOwnFacilityWithSameLocalCode() {
        ResponseEntity<String> response = get("admin-b", ORG_B);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(ORG_B));
    }

    @Test
    void wrongRoleIsDenied() {
        assertEquals(HttpStatus.FORBIDDEN, get("reception-a", ORG_A).getStatusCode());
    }

    @Test
    void revokedMembershipIsDeniedOnNextRequest() {
        assertEquals(HttpStatus.FORBIDDEN, get("revoked-a", ORG_A).getStatusCode());
    }

    @Test
    void missingSubjectIsUnauthenticated() {
        assertEquals(HttpStatus.UNAUTHORIZED, get(null, ORG_A).getStatusCode());
    }

    @Test
    void missingOrganizationSelectionIsInvalid() {
        assertEquals(422, get("admin-a", null).getStatusCode().value());
    }

    @Test
    void duplicateCodeInSameTenantConflicts() {
        String body = "{\"code\":\"MAIN\",\"name\":\"Duplicate\"}";
        ResponseEntity<String> response = post(body, "admin-a", ORG_A);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void createThenReadWithinTenant() throws Exception {
        String body = "{\"code\":\"WARD\",\"name\":\"Ward\"}";
        ResponseEntity<String> created = post(body, "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertTrue(created.getHeaders().getLocation().toString().endsWith("/WARD"));

        ResponseEntity<String> read = rest.method(HttpMethod.GET).uri("/api/v1/facilities/WARD")
                .headers(httpHeaders -> httpHeaders.addAll(headers("admin-a", ORG_A)))
                .retrieve().toEntity(String.class);
        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertTrue(read.getBody().contains(ORG_A));
    }

    @Test
    void missingContextFailsClosed() {
        com.aurevia.tenant.TenantContext.clear();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.get("MAIN"));
    }

    @Test
    void auditRecordsTheActingUser() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT actor_id FROM public.audit_event WHERE correlation_id IS NOT NULL "
                    + "AND actor_id IS NOT NULL LIMIT 1")) {
                assertTrue(result.next(), "audit rows must carry actor_id (OPS-02)");
            }
        }
    }

    @Test
    void auditVerifierDoesNotCrossTenants() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                    () -> statement.executeQuery(
                            "SELECT * FROM public.audit_verify_chain('" + ORG_B + "')"));
            try (ResultSet result = statement.executeQuery(
                    "SELECT organization_id::text FROM public.facility WHERE code = 'MAIN'")) {
                assertTrue(result.next());
                assertEquals(ORG_A, result.getString(1), "caller context must be unchanged");
            }
        }
    }

    @Test
    void auditChainIsValid() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.audit_verify_chain('" + ORG_A + "') WHERE NOT ok")) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
        }
    }
}
