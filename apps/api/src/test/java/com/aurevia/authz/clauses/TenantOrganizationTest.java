package com.aurevia.authz.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

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

/**
 * IAM-01 clause 2: the tenant is the organization. The active organization is
 * the isolation boundary: a request sees only the data of the organization it
 * selected, even when two organizations use the same tenant-local identifiers.
 *
 * <p>Synthetic fixtures only; real PostgreSQL with forced row-level security.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_tenant_org_it")
@ActiveProfiles("synthetic-auth")
class TenantOrganizationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_tenant_org_it";
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_tenant_org_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_tenant_org_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "Alpha Main");
            seed(statement, ORG_B, "admin-b", "Beta Main");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_tenant_org_it WITH (FORCE)");
        }
    }

    /** Create an organization, an active tenant_admin membership and one tenant-local facility. */
    private static void seed(Statement statement, String org, String subject, String facilityName)
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
                + "WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'tenant_admin', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
        statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                + "VALUES ('" + org + "', 'MAIN', '" + facilityName + "')");
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

    @Test
    void activeOrganizationDeterminesWhichFacilityIsVisible() {
        ResponseEntity<String> response = get("admin-a", ORG_A);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(ORG_A));
        assertTrue(response.getBody().contains("Alpha Main"));
    }

    @Test
    void identicalLocalFacilityCodeIsScopedToItsOwningOrganization() {
        ResponseEntity<String> fromA = get("admin-a", ORG_A);
        ResponseEntity<String> fromB = get("admin-b", ORG_B);

        assertEquals(HttpStatus.OK, fromA.getStatusCode());
        assertEquals(HttpStatus.OK, fromB.getStatusCode());
        assertTrue(fromA.getBody().contains(ORG_A));
        assertTrue(fromA.getBody().contains("Alpha Main"));
        assertFalse(fromA.getBody().contains("Beta Main"));
        assertTrue(fromB.getBody().contains(ORG_B));
        assertTrue(fromB.getBody().contains("Beta Main"));
        assertFalse(fromB.getBody().contains("Alpha Main"));
    }

    @Test
    void subjectCannotSelectAnotherOrganizationAsItsTenant() {
        assertEquals(HttpStatus.FORBIDDEN, get("admin-a", ORG_B).getStatusCode());
    }

    @Test
    void missingActiveOrganizationSelectionIsRejected() {
        assertEquals(422, get("admin-a", null).getStatusCode().value());
    }
}
