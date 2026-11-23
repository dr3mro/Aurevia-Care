package com.aurevia.smartclinic.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.tenancy.Facility;
import com.aurevia.smartclinic.tenancy.FacilityService;

/**
 * ARC-02 clause 2 acceptance: application services enforce both the action
 * policy (may this role perform this action here?) and the object policy (may
 * this caller see this particular record?). Both decisions are exercised on a
 * real HTTP entry point and, once, directly on the application service so a
 * web-layer check alone could not make the suite pass.
 *
 * <p>Every caller below has a valid subject, an explicit active organization
 * and an active membership. What varies is the role-to-action matrix or the
 * owning organization of the object, so a denial can only come from the policy
 * under test. The asserted reason {@code missing action permission} pins the
 * action denial to the action guard, not the membership guard. Current-scope
 * binding is IAM-01 clause 6 ({@code WriteChecksCurrentScopeTest}) and the RLS
 * mechanism itself is ARC-02 clause 3 ({@code IsolationAcrossSurfacesTest});
 * neither is re-proved as the main assertion here. No production code is added
 * by this class; it only drives existing entry points with synthetic data.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_action_object_it")
@ActiveProfiles("synthetic-auth")
class EnforceActionObjectPolicyApplicationServicesTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String IN_SCOPE = "APOL-SERVICE";
    private static final String FOREIGN = "APOL-B";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_action_object_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final String ACTION_DENIED = "missing action permission";

    @Autowired
    FacilityService facilityService;

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_action_object_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_action_object_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_A, "reception-a", "reception");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");
            // Seed one object per tenant directly, so every test reads a row that
            // exists independently of any other test's HTTP writes.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', '" + IN_SCOPE + "', 'Alpha service')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_B + "', '" + FOREIGN + "', 'Beta foreign')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_action_object_it WITH (FORCE)");
        }
    }

    /**
     * Seed an active user with one role in one organization, mirroring the
     * synthetic identity fixtures of the neighbouring clause tests.
     */
    private static void seedUser(Statement statement, String org, String subject, String role)
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

    /** Build the synthetic authentication and explicit-organization headers. */
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

    /** Send a facility create through the real HTTP entry point, keeping error bodies. */
    private ResponseEntity<String> write(String code, String subject, String organization, String correlation) {
        String body = "{\"code\":\"" + code + "\",\"name\":\"Policy " + code + "\"}";
        return rest.method(HttpMethod.POST).uri("/api/v1/facilities")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)))
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Read one facility through the real HTTP entry point, keeping error bodies. */
    private ResponseEntity<String> read(String code, String subject, String organization) {
        return rest.method(HttpMethod.GET).uri("/api/v1/facilities/" + code)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, "read-" + code)))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    /** Bind tenant context on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String organization, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(organization, subject, "ACTION_OBJECT_POLICY_EVIDENCE", "apol-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void actionPolicyAllowsTenantAdminToWriteAndReadItsOwnObject() {
        ResponseEntity<String> created = write("APOL-A", "admin-a", ORG_A, "apol-create");

        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals(URI.create("/api/v1/facilities/APOL-A"), created.getHeaders().getLocation());
        assertTrue(created.getBody().contains(ORG_A), created.getBody());

        ResponseEntity<String> read = read("APOL-A", "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, read.getStatusCode(), read.getBody());
        assertTrue(read.getBody().contains(ORG_A), read.getBody());
    }

    @Test
    void writeByRoleWithoutActionPermissionIsDenied() {
        // Active membership and an explicit organization are valid here; only the
        // role-to-action matrix denies FACILITY_WRITE and FACILITY_READ.
        ResponseEntity<String> deniedWrite = write("APOL-DENY", "reception-a", ORG_A, "apol-no-action-write");

        assertEquals(HttpStatus.FORBIDDEN, deniedWrite.getStatusCode(), deniedWrite.getBody());
        assertTrue(deniedWrite.getBody().contains(ACTION_DENIED), deniedWrite.getBody());
        assertEquals(HttpStatus.NOT_FOUND, read("APOL-DENY", "admin-a", ORG_A).getStatusCode(),
                "a write denied by action policy must leave no row behind");

        ResponseEntity<String> deniedRead = read(IN_SCOPE, "reception-a", ORG_A);
        assertEquals(HttpStatus.FORBIDDEN, deniedRead.getStatusCode(), deniedRead.getBody());
        assertTrue(deniedRead.getBody().contains(ACTION_DENIED), deniedRead.getBody());
    }

    @Test
    void objectFromAnotherTenantIsConcealedEvenForAnAdmin() {
        // Controls: the same subject and action see their own object, and the
        // foreign row is readable by its owning tenant. The 404 below is therefore
        // an object decision, not a missing row or a failed action check.
        ResponseEntity<String> own = read(IN_SCOPE, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, own.getStatusCode(), own.getBody());
        assertTrue(own.getBody().contains(ORG_A), own.getBody());

        ResponseEntity<String> foreignOwned = read(FOREIGN, "admin-b", ORG_B);
        assertEquals(HttpStatus.OK, foreignOwned.getStatusCode(), foreignOwned.getBody());
        assertTrue(foreignOwned.getBody().contains(ORG_B), foreignOwned.getBody());

        ResponseEntity<String> concealed = read(FOREIGN, "admin-a", ORG_A);
        assertEquals(HttpStatus.NOT_FOUND, concealed.getStatusCode(), concealed.getBody());
    }

    @Test
    void applicationServiceEnforcesActionPolicyWithoutHttpClient() {
        ApiException denied = assertThrows(ApiException.class,
                () -> as(ORG_A, "reception-a", () -> facilityService.get(IN_SCOPE)));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
        assertEquals(ACTION_DENIED, denied.getMessage());

        Facility visible = as(ORG_A, "admin-a", () -> facilityService.get(IN_SCOPE));
        assertEquals(ORG_A, visible.organizationId());
        assertEquals(IN_SCOPE, visible.code());
    }
}
