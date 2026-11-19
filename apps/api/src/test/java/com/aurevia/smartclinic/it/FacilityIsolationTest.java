package com.aurevia.smartclinic.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.smartclinic.tenancy.FacilityService;

import org.flywaydb.core.Flyway;

/** G0 acceptance N1-N4, E1-E8 against a real PostgreSQL with two synthetic tenants. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("synthetic-auth")
class FacilityIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @Autowired TestRestTemplate rest;
    @Autowired FacilityService service;

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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin", "active", "MAIN", "Alpha Main");
            seed(statement, ORG_B, "admin-b", "tenant_admin", "active", "MAIN", "Beta Main");
            seed(statement, ORG_A, "reception-a", "reception", "active", null, null);
            seed(statement, ORG_A, "revoked-a", "tenant_admin", "revoked", null, null);
        }
    }

    private static void seed(Statement statement, String org, String subject, String role,
                             String membershipStatus, String facilityCode, String facilityName)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
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
        return rest.exchange("/api/v1/facilities/MAIN", HttpMethod.GET,
                new HttpEntity<>(headers(subject, organization)), String.class);
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
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, get("admin-a", null).getStatusCode());
    }

    @Test
    void duplicateCodeInSameTenantConflicts() {
        String body = "{\"code\":\"MAIN\",\"name\":\"Duplicate\"}";
        ResponseEntity<String> response = rest.exchange("/api/v1/facilities", HttpMethod.POST,
                new HttpEntity<>(body, headers("admin-a", ORG_A)), String.class);
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void createThenReadWithinTenant() throws Exception {
        String body = "{\"code\":\"WARD\",\"name\":\"Ward\"}";
        ResponseEntity<String> created = rest.exchange("/api/v1/facilities", HttpMethod.POST,
                new HttpEntity<>(body, headers("admin-a", ORG_A)), String.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertTrue(created.getHeaders().getLocation().toString().endsWith("/WARD"));

        ResponseEntity<String> read = rest.exchange("/api/v1/facilities/WARD", HttpMethod.GET,
                new HttpEntity<>(headers("admin-a", ORG_A)), String.class);
        assertEquals(HttpStatus.OK, read.getStatusCode());
        assertTrue(read.getBody().contains(ORG_A));
    }

    @Test
    void missingContextFailsClosed() {
        com.aurevia.smartclinic.tenant.TenantContext.clear();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.get("MAIN"));
    }

    @Test
    void auditChainIsValid() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT count(*) FROM public.audit_verify_chain('" + ORG_A + "') WHERE NOT ok")) {
            result.next();
            assertEquals(0, result.getInt(1));
        }
    }
}
