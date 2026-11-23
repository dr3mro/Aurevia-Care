package com.aurevia.smartclinic.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * ARC-02 clause 3 evidence against a real PostgreSQL database.
 *
 * <p>The clause has three parts: forced row-level security on tenant tables, a
 * non-owner runtime role without bypass, and transaction-local tenant context.
 * This suite asserts each part with facts rather than call counts: the catalog
 * flags and policy of {@code public.facility}, the role attributes of
 * {@code smartclinic_app}, a seeded cross-tenant row that is visible under its
 * owner and concealed under a peer organization, a refused cross-tenant write,
 * and the bind/revert behavior of the production guarded transaction manager.</p>
 *
 * <p>Every connection to {@code smartclinic_app} is a fresh non-bypass connection;
 * every catalog probe asserts that a row was actually returned. No production
 * code is added by this suite and all identifiers are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_rls_forced_it")
@ActiveProfiles("synthetic-auth")
class UsePostgresqlRowLevelSecurityForcedEnforcementTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String MIGRATE = "smartclinic_migrate";
    static final String APP = "smartclinic_app";
    static final String DATABASE = "smartclinic_rls_forced_it";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    static final String CORRELATION = "rls-forced-it";
    static final String MAIN = "MAIN";
    static final String CROSS_TENANT_CODE = "CROSS-TENANT-WRITE";
    static final String SAME_TENANT_CODE = "SAME-TENANT-WRITE";
    static final String WRITTEN_CODE = "RLS-WRITE-A";

    /** Production transaction template, built on the tenant-guarded manager. */
    @Autowired
    TransactionTemplate transactions;

    /** Production JDBC template, sharing the guarded datasource. */
    @Autowired
    JdbcTemplate jdbc;

    RestClient rest;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create a private database, migrate it with the production migrations and
     * seed two synthetic organizations with one organization-A-only facility.
     *
     * @throws Exception when PostgreSQL is unavailable or the fixture cannot be built
     */
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
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, MIGRATE, "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, MIGRATE, "");
             Statement statement = connection.createStatement()) {
            seedUser(statement, ORG_A, "admin-a", "tenant_admin");
            seedUser(statement, ORG_B, "admin-b", "tenant_admin");
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', '" + MAIN + "', 'Alpha Main')");
        }
    }

    /**
     * Drop the private database so a rerun starts from a clean catalog.
     *
     * @throws Exception when the administrative connection fails
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Seed an organization, a subject, an active membership and one role grant.
     *
     * <p>The migrate role is itself subject to forced RLS, so context is switched
     * before every insert, exactly as the production migration role must be.</p>
     *
     * @param statement open statement on the migrated database
     * @param org       synthetic organization UUID as text
     * @param subject   synthetic subject
     * @param role      role key to grant
     * @throws Exception when a seed statement fails
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
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Build synthetic-auth headers for one subject and organization. */
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

    /** Send a request without treating an error status as a client exception. */
    private ResponseEntity<String> request(HttpMethod method, String uri, String body,
                                           String subject, String organization) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)));
        RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
        return headerSpec.retrieve().onStatus(status -> true, (req, response) -> { })
                .toEntity(String.class);
    }

    /** Perform a synthetic-auth GET. */
    private ResponseEntity<String> get(String uri, String subject, String organization) {
        return request(HttpMethod.GET, uri, null, subject, organization);
    }

    /** Perform a synthetic-auth POST. */
    private ResponseEntity<String> post(String uri, String body, String subject, String organization) {
        return request(HttpMethod.POST, uri, body, subject, organization);
    }

    /** Read one top-level JSON string field; returns an empty string when absent. */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json == null ? -1 : json.indexOf(needle);
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

    /** Count rows visible to the non-bypass role under one transaction-local organization. */
    private static long countAsApp(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, APP, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet rows = statement.executeQuery(sql)) {
                assertTrue(rows.next(), "count query must return a row");
                return rows.getLong(1);
            }
        }
    }

    /** Return the organization of the visible facility row, or null when concealed. */
    private static String visibleFacilityOrg(String org) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, APP, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT organization_id::text FROM public.facility WHERE code = '" + MAIN + "'")) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
     * The three catalog guarantees hold and a real tenant row is concealed across
     * organizations and through the HTTP entry point.
     *
     * @throws Exception when a PostgreSQL probe fails
     */
    @Test
    void forcedRlsAndNoBypassRoleConcealCrossTenantFacilityRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, MIGRATE, "");
             Statement statement = connection.createStatement()) {
            for (String table : new String[] {"organization", "user_identity", "facility",
                    "membership", "role_assignment", "audit_event"}) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                                + "WHERE oid = 'public." + table + "'::regclass")) {
                    assertTrue(rows.next(), table + " must exist");
                    assertTrue(rows.getBoolean("relrowsecurity"), table + " must enable row level security");
                    assertTrue(rows.getBoolean("relforcerowsecurity"), table + " must force row level security");
                }
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname = 'smartclinic_app'")) {
                assertTrue(rows.next(), "smartclinic_app must be provisioned");
                assertFalse(rows.getBoolean("rolbypassrls"), "runtime role must not bypass RLS");
                assertFalse(rows.getBoolean("rolsuper"), "superusers bypass RLS regardless of the flag");
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner "
                            + "WHERE r.rolname = 'smartclinic_app' "
                            + "AND c.relname IN ('organization','user_identity','facility',"
                            + "'membership','role_assignment','audit_event')")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "runtime role must own no tenant table");
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' "
                            + "AND tablename = 'facility' AND policyname = 'tenant_isolation' "
                            + "AND qual IS NOT NULL AND with_check IS NOT NULL")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "facility must have USING and WITH CHECK policy");
            }
        }

        // A real row exists and is visible to its owner, never to the peer tenant.
        assertEquals(ORG_A, visibleFacilityOrg(ORG_A));
        assertNull(visibleFacilityOrg(ORG_B), "forced RLS must conceal the ORG_A row from ORG_B");

        // A WITH CHECK insert owned by ORG_A is likewise invisible under ORG_B.
        ResponseEntity<String> created = post("/api/v1/facilities",
                "{\"code\":\"" + WRITTEN_CODE + "\",\"name\":\"RLS written\"}", "admin-a", ORG_A);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        assertEquals(ORG_A, field(created.getBody(), "organizationId"));
        assertEquals(1, countAsApp(ORG_A,
                "SELECT count(*) FROM public.facility WHERE code = '" + WRITTEN_CODE + "'"));
        assertEquals(0, countAsApp(ORG_B,
                "SELECT count(*) FROM public.facility WHERE code = '" + WRITTEN_CODE + "'"));

        // The same concealment reaches the documented HTTP entry point. The full
        // request matrix lives in IsolationAcrossSurfacesTest; these two confirm
        // this fixture exercises the real controller path.
        ResponseEntity<String> alpha = get("/api/v1/facilities/" + MAIN, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, alpha.getStatusCode());
        assertTrue(alpha.getBody().contains(ORG_A));
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/facilities/" + MAIN, "admin-b", ORG_B).getStatusCode());
    }

    /**
     * Negative: the non-owner runtime role cannot use its role to bypass forced RLS.
     *
     * @throws Exception when a PostgreSQL probe fails
     */
    @Test
    void nonOwnerRoleCannotBypassForcedRls() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, MIGRATE, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.facility WHERE code = '" + MAIN + "'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "the owner with matching context sees the seeded row");
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT rolbypassrls FROM pg_roles WHERE rolname = 'smartclinic_app'")) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1));
            }
        }
        // Same role, wrong tenant context: a no-bypass role under FORCE RLS sees nothing.
        assertEquals(1, countAsApp(ORG_A,
                "SELECT count(*) FROM public.facility WHERE code = '" + MAIN + "'"));
        assertEquals(0, countAsApp(ORG_B,
                "SELECT count(*) FROM public.facility WHERE code = '" + MAIN + "'"));
    }

    /**
     * Negative: an empty transaction context exposes no rows at SQL and the HTTP
     * entry point refuses a request with no subject or no organization selection.
     *
     * @throws Exception when a PostgreSQL probe fails
     */
    @Test
    void missingTenantContextFailsClosed() throws Exception {
        assertEquals(1, countAsApp(ORG_A,
                "SELECT count(*) FROM public.facility WHERE code = '" + MAIN + "'"));
        try (Connection connection = DriverManager.getConnection(URL, APP, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = ''");
            try (ResultSet org = statement.executeQuery("SELECT public.app_org() IS NULL")) {
                assertTrue(org.next());
                assertTrue(org.getBoolean(1), "empty context must resolve to NULL");
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.facility WHERE code = '" + MAIN + "'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "empty context must expose no rows");
            }
        }
        // HTTP fail-closed matrix is also covered by IsolationAcrossSurfacesTest.
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/facilities/" + MAIN, null, ORG_A).getStatusCode());
        assertEquals(422,
                get("/api/v1/facilities/" + MAIN, "admin-a", null).getStatusCode().value());
    }

    /**
     * Negative: a write whose organization differs from the transaction context is
     * refused by the WITH CHECK policy and leaves no row behind.
     *
     * @throws Exception when a PostgreSQL probe fails
     */
    @Test
    void foreignTenantWriteIsRefused() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, APP, "");
             Statement settings = connection.createStatement()) {
            // A valid competing context is essential: with no context at all a
            // 42501 would only prove the fail-closed case, not WITH CHECK's
            // organization equality. The same-tenant insert below is the control.
            settings.execute("SET app.current_org = '" + ORG_A + "'");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO public.facility (organization_id, code, name) "
                            + "VALUES (CAST(? AS uuid), ?, ?)")) {
                insert.setString(1, ORG_A);
                insert.setString(2, SAME_TENANT_CODE);
                insert.setString(3, "Same tenant");
                assertEquals(1, insert.executeUpdate(),
                        "an insert owned by the active tenant must succeed on this connection");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO public.facility (organization_id, code, name) "
                            + "VALUES (CAST(? AS uuid), ?, ?)")) {
                insert.setString(1, ORG_B);
                insert.setString(2, CROSS_TENANT_CODE);
                insert.setString(3, "Cross tenant");
                SQLException refused = assertThrows(SQLException.class, insert::executeUpdate,
                        "a foreign-tenant insert must be refused by WITH CHECK");
                assertEquals("42501", refused.getSQLState());
                assertTrue(refused.getMessage().contains("row-level security"), refused.getMessage());
            }
        }
        // FORCE RLS also binds the migrate role: probe with the target tenant's
        // context or a zero count would be vacuous.
        try (Connection connection = DriverManager.getConnection(URL, MIGRATE, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.facility WHERE code = '" + CROSS_TENANT_CODE + "'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "the refused write must not persist");
            }
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.facility WHERE code = '" + SAME_TENANT_CODE + "'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "the same-tenant control must persist");
            }
        }
    }

    /**
     * The production guarded transaction manager binds organization context only
     * for the transaction it opens, and the set_config(...,true) idiom it uses
     * reverts on commit.
     *
     * @throws Exception when a PostgreSQL probe fails
     */
    @Test
    void tenantGuardBindsTenantContextOnlyInsideEachTransaction() throws Exception {
        TenantContext.set(new TenantContext(ORG_A, "admin-a", TenantContext.PURPOSE, CORRELATION));
        try {
            String boundA = transactions.execute(status -> jdbc.queryForObject(
                    "SELECT current_setting('app.current_org', true)", String.class));
            assertEquals(ORG_A, boundA, "production guard must bind the active organization");
            Long visibleInA = transactions.execute(status -> jdbc.queryForObject(
                    "SELECT count(*) FROM public.facility WHERE code = '" + MAIN
                            + "' AND organization_id = CAST(? AS uuid)", Long.class, ORG_A));
            assertEquals(1L, visibleInA);
        } finally {
            TenantContext.clear();
        }

        // Without a bound context the production guard refuses to open a transaction.
        assertThrows(IllegalStateException.class, () -> transactions.execute(
                status -> jdbc.queryForObject("SELECT 1", Integer.class)));

        TenantContext.set(new TenantContext(ORG_B, "admin-b", TenantContext.PURPOSE, CORRELATION));
        try {
            String boundB = transactions.execute(status -> jdbc.queryForObject(
                    "SELECT current_setting('app.current_org', true)", String.class));
            assertEquals(ORG_B, boundB);
            Long leakedA = transactions.execute(status -> jdbc.queryForObject(
                    "SELECT count(*) FROM public.facility WHERE code = '" + MAIN
                            + "' AND organization_id = CAST(? AS uuid)", Long.class, ORG_A));
            assertEquals(0L, leakedA, "ORG_A context must not leak into an ORG_B transaction");
        } finally {
            TenantContext.clear();
        }

        // Same idiom as TenantGuardTransactionManager on a fresh non-bypass
        // connection: set_config(..., true) is transaction-local and reverts on
        // commit, so a pooled connection cannot carry the previous tenant onward.
        try (Connection connection = DriverManager.getConnection(URL, APP, "")) {
            connection.setAutoCommit(false);
            try {
                String applied;
                try (PreparedStatement bind = connection.prepareStatement(
                        "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                    bind.setString(1, ORG_A);
                    try (ResultSet rows = bind.executeQuery()) {
                        assertTrue(rows.next(), "set_config must return a value");
                        applied = rows.getString(1);
                    }
                }
                assertEquals(ORG_A, applied, "the transaction-local bind must take effect");
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT count(*) FROM public.facility WHERE code = '" + MAIN + "'")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getLong(1));
                }
                connection.commit();
                try (Statement statement = connection.createStatement();
                     ResultSet setting = statement.executeQuery(
                             "SELECT current_setting('app.current_org', true)")) {
                    assertTrue(setting.next());
                    String afterCommit = setting.getString(1);
                    assertTrue(afterCommit == null || afterCommit.isEmpty(),
                            "the transaction-local organization must revert after commit");
                }
                try (Statement statement = connection.createStatement();
                     ResultSet rows = statement.executeQuery(
                             "SELECT count(*) FROM public.facility WHERE code = '" + MAIN + "'")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1), "reverted context must expose no row");
                }
            } finally {
                connection.rollback();
            }
        }
    }
}
