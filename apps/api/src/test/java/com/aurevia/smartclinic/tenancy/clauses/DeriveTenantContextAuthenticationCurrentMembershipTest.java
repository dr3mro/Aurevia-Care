package com.aurevia.smartclinic.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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

import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-02 clause 1: the tenant context of a request comes from its authenticated
 * subject and that subject's current active-organization membership, not from
 * the resource being read. A foreign or missing selection is refused before any
 * tenant-scoped data can be returned.
 *
 * <p>Under the {@code synthetic-auth} profile the interceptor binds the selected
 * organization from the validated request headers; the membership half of the
 * rule is enforced inside the guarded transaction by
 * {@code FacilityService.authorize} using the row-level-security folds in
 * {@code V4__staff_lifecycle.sql}. The JWT/OAuth claim half is covered by
 * {@code ClaimsTenantResolverTest} and is not exercised by this suite.</p>
 *
 * <p>Synthetic fixtures only; real PostgreSQL with forced row-level security.
 * No production code is added by this class.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_derive_tenant_ctx_it")
@ActiveProfiles("synthetic-auth")
class DeriveTenantContextAuthenticationCurrentMembershipTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_derive_tenant_ctx_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Guard against a thread-local context surviving a failed request on a pooled
     * servlet thread and leaking tenant identity into the next test.
     */
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
            statement.execute("DROP DATABASE IF EXISTS smartclinic_derive_tenant_ctx_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_derive_tenant_ctx_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedActiveSubject(statement, ORG_A, "admin-a", "Alpha Main");
            seedActiveSubject(statement, ORG_B, "admin-b", "Beta Main");
            seedRevokedSubject(statement, ORG_A, "inactive-a");
            // Fixture precondition: the revoked fold must not derive a tenant, so a
            // 403 in the test cannot be caused by a missing role or an unset context.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT public.has_active_membership('inactive-a')")) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1), "a revoked membership must not be active");
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_derive_tenant_ctx_it WITH (FORCE)");
        }
    }

    /**
     * Seed an organization, an active membership, a tenant_admin grant and one
     * tenant-local facility with the shared code {@code MAIN}.
     */
    private static void seedActiveSubject(Statement statement, String org, String subject, String facilityName)
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
        grantTenantAdmin(statement, org, subject, "active");
        statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                + "VALUES ('" + org + "', 'MAIN', '" + facilityName + "')");
    }

    /**
     * Seed a subject whose current membership is revoked. Membership rows are
     * append-only, so an older active event is followed by a newer revoked event;
     * the explicit past {@code valid_from} values make the current-membership
     * fold deterministic instead of relying on insert order.
     */
    private static void seedRevokedSubject(Statement statement, String org, String subject) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status, valid_from) "
                + "SELECT '" + org + "', id, 'active', now() - interval '2 days' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status, valid_from) "
                + "SELECT '" + org + "', id, 'revoked', now() - interval '1 day' FROM public.user_identity "
                + "WHERE subject = '" + subject + "' AND organization_id = '" + org + "'");
        // Grant the role on the current (revoked) membership so the 403 can only
        // come from the membership test, never from a missing permission.
        grantTenantAdmin(statement, org, subject, "revoked");
    }

    /** Grant tenant_admin on the named membership state of a seeded subject. */
    private static void grantTenantAdmin(Statement statement, String org, String subject, String status)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment "
                + "(organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'tenant_admin', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "' AND u.organization_id = '" + org + "' "
                + "AND m.status = '" + status + "'");
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
    void authenticatedSubjectWithActiveMembershipDerivesItsOwnTenant() {
        ResponseEntity<String> response = get("admin-a", ORG_A);

        assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains(ORG_A));
        assertTrue(response.getBody().contains("Alpha Main"));
        assertFalse(response.getBody().contains(ORG_B));
        assertFalse(response.getBody().contains("Beta Main"));
    }

    @Test
    void subjectWithMembershipOnlyInOrganizationAIsRefusedOrganizationB() {
        ResponseEntity<String> response = get("admin-a", ORG_B);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"FORBIDDEN\""));
        assertTrue(response.getBody().contains("no active membership"));
        assertFalse(response.getBody().contains("Alpha Main"));
        assertFalse(response.getBody().contains("Beta Main"));
    }

    @Test
    void missingActiveOrganizationHeaderFailsClosed() {
        ResponseEntity<String> response = get("admin-a", null);

        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"INVALID_TENANT_SELECTION\""));
        assertFalse(response.getBody().contains("Alpha Main"));
    }

    @Test
    void missingSubjectFailsClosed() {
        ResponseEntity<String> response = get(null, ORG_A);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"UNAUTHENTICATED\""));
        assertFalse(response.getBody().contains("Alpha Main"));
    }

    @Test
    void blankSubjectFailsClosed() {
        ResponseEntity<String> response = get("", ORG_A);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"UNAUTHENTICATED\""));
        assertFalse(response.getBody().contains("Alpha Main"));
    }

    @Test
    void malformedActiveOrganizationHeaderFailsClosed() {
        ResponseEntity<String> response = get("admin-a", "not-a-uuid");

        assertEquals(422, response.getStatusCode().value(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"INVALID_TENANT_SELECTION\""));
        assertFalse(response.getBody().contains("Alpha Main"));
    }

    @Test
    void missingSubjectIsRefusedBeforeMissingOrganization() {
        ResponseEntity<String> response = get(null, null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"UNAUTHENTICATED\""));
        assertFalse(response.getBody().contains("Alpha Main"));
    }

    @Test
    void deactivatedMembershipCannotDeriveTenant() {
        ResponseEntity<String> response = get("inactive-a", ORG_A);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode(), response.getBody());
        assertTrue(response.getBody().contains("\"code\":\"FORBIDDEN\""));
        assertTrue(response.getBody().contains("no active membership"));
        assertFalse(response.getBody().contains("Alpha Main"));
    }
}
