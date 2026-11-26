package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Production entry-point proof for the OPS-02 command gate (ARC-04).
 *
 * <p>A POST reaches the real controller through the registered interceptor, the injected
 * authorizer re-reads the tenant's membership folds and the durable sink appends a row to the
 * V65 ledger. Two synthetic tenants share one subject to prove that the decision row belongs
 * to the active organization and stays invisible under forced RLS. No patient value is used.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_command_gate_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class CommandAuthorizationWiringHttpTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_command_gate_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    /**
     * Build a synthetic facility body with a unique tenant-local code.
     */
    private static String body(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Synthetic gate\",\"timeZone\":\"UTC\"}";
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /**
     * Build MockMvc from the real Spring MVC context so the registered interceptors run.
     */
    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_command_gate_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_command_gate_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String organization : new String[] {ORG_A, ORG_B}) {
                statement.execute("SET app.current_org = '" + organization + "'");
                statement.execute("SET app.actor = 'gate-bootstrap'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                        + "VALUES ('" + organization + "', 'Synthetic gate tenant')");
                statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                        + "VALUES ('" + organization + "', 'gate-bootstrap', 'Gate bootstrap')");
            }
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_A, "shared-a", "tenant_admin");
            // The same subject has no membership in the second organization.
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG_B + "', 'shared-a', 'No membership')");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_command_gate_it WITH (FORCE)");
        }
    }

    /**
     * Insert a user, one active membership event and one role grant in one synthetic tenant.
     */
    private static void seedMember(Statement statement, String organization, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + organization + "'");
        statement.execute("SET app.actor = 'gate-bootstrap'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + organization + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + organization + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + organization + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /**
     * Count ledger rows visible under one organization for a correlation id.
     */
    private static int decisions(String organization, String correlation) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM public.authorization_decision WHERE correlation_id = ?")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, organization);
                bind.setString(2, "gate-probe");
                bind.execute();
            }
            statement.setString(1, correlation);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Read one ledger row's coded columns under the owning tenant.
     */
    private static String[] decision(String organization, String correlation) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT decision, reason_code, action, subject FROM public.authorization_decision "
                             + "WHERE correlation_id = ?")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, organization);
                bind.setString(2, "gate-probe");
                bind.execute();
            }
            statement.setString(1, correlation);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "expected a decision row for " + correlation);
                String[] row = new String[] {result.getString(1), result.getString(2),
                        result.getString(3), result.getString(4)};
                assertFalse(result.next(), "one decision per correlation");
                return row;
            } finally {
                connection.rollback();
            }
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder command(
            String organization, String subject, String correlation, String code) {
        var request = post("/api/v1/facilities").contentType(MediaType.APPLICATION_JSON).content(body(code));
        if (organization != null) {
            request.header("X-Active-Organization", organization);
        }
        if (subject != null) {
            request.header("X-Synthetic-Subject", subject);
        }
        if (correlation != null) {
            request.header("X-Correlation-Id", correlation);
        }
        return request;
    }

    @Test
    void allowedCommandReachesTheControllerAndAppendsAnAllowRow() throws Exception {
        mvc.perform(command(ORG_A, "admin-a", "corr-http-allow", "GATE-ALLOW"))
                .andExpect(status().isCreated());

        assertEquals(1, decisions(ORG_A, "corr-http-allow"));
        String[] row = decision(ORG_A, "corr-http-allow");
        assertEquals("allow", row[0]);
        assertEquals("ALLOWED", row[1]);
        assertEquals("FACILITY_WRITE", row[2]);
        assertEquals("admin-a", row[3]);
    }

    @Test
    void forbiddenRoleIsDeniedAndAppendsADenyRow() throws Exception {
        mvc.perform(command(ORG_A, "reception-a", "corr-http-deny", "GATE-DENY"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertEquals(1, decisions(ORG_A, "corr-http-deny"));
        String[] row = decision(ORG_A, "corr-http-deny");
        assertEquals("deny", row[0]);
        assertEquals("COMMAND_FORBIDDEN", row[1]);
        assertEquals("FACILITY_WRITE", row[2]);
        assertEquals("reception-a", row[3]);
    }

    @Test
    void sameSubjectInTwoTenantsProducesOnlyItsOwnLedgerRow() throws Exception {
        mvc.perform(command(ORG_A, "shared-a", "corr-http-org-a", "GATE-ORG-A"))
                .andExpect(status().isCreated());
        mvc.perform(command(ORG_B, "shared-a", "corr-http-org-b", "GATE-ORG-B"))
                .andExpect(status().isForbidden());

        assertEquals(1, decisions(ORG_A, "corr-http-org-a"));
        assertEquals(0, decisions(ORG_A, "corr-http-org-b"), "forced RLS hides the other tenant's row");
        assertEquals(1, decisions(ORG_B, "corr-http-org-b"));
        assertEquals(0, decisions(ORG_B, "corr-http-org-a"));
        assertEquals("ALLOWED", decision(ORG_A, "corr-http-org-a")[1]);
        assertEquals("MEMBERSHIP_INACTIVE", decision(ORG_B, "corr-http-org-b")[1]);
    }

    @Test
    void anonymousPostIsRejectedBeforeTheGateAndWritesNoDecisionRow() throws Exception {
        mvc.perform(command(ORG_A, null, "corr-http-anonymous", "GATE-ANON"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        // The tenant interceptor denies before the gate, so this proves only that no
        // unauthenticated command can produce an allow row. Null-identity gate behavior is
        // asserted with a deterministic direct call in CommandAuthorizationInterceptorHttpTest.
        assertEquals(0, decisions(ORG_A, "corr-http-anonymous"));
    }
}
