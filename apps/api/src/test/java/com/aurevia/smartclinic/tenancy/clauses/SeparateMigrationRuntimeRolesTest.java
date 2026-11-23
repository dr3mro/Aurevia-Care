package com.aurevia.smartclinic.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ARC-02 clause 4 evidence: schema migrations and application traffic use two
 * different PostgreSQL roles. The migration role owns the tenant tables and is
 * the only role allowed to apply DDL; the runtime role is a non-owner without
 * bypass that can only touch rows inside the caller's transaction-local tenant
 * context. Assertions are made against the real catalog and real JDBC
 * connections, because synthetic-auth HTTP headers bind only application
 * context and cannot prove which database role executed a statement.
 *
 * <p>No production code is added by this suite. It reproduces the role
 * provisioning of {@code infra/app/start.sh} and the Flyway settings of
 * {@code application.yml} on a synthetic database, then checks the powers of
 * each role using the same catalog invariants as {@code db/check.sh}.</p>
 */
@Tag("integration")
// Only the database name is overridden: the datasource role must come from the
// production default in application.yml for the current_user assertion to prove
// the deployment contract rather than this test's own configuration.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_separate_roles_it")
@ActiveProfiles("synthetic-auth")
class SeparateMigrationRuntimeRolesTest {

    static final String MIGRATE_ROLE = "smartclinic_migrate";
    static final String APP_ROLE = "smartclinic_app";
    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_separate_roles_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    DataSource applicationDataSource;

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
            // Roles are cluster-wide and shared with parallel suites. Force the
            // least-privilege attributes so an earlier run cannot poison them.
            statement.execute("ALTER ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE");
            statement.execute("ALTER ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_separate_roles_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_separate_roles_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, MIGRATE_ROLE, "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, MIGRATE_ROLE, "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "Alpha", "Alpha Main", "ONLY-A", "Alpha Only");
            seedTenant(statement, ORG_B, "Beta", "Beta Main", "ONLY-B", "Beta Only");
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            // FORCE closes connections the Spring context may still hold when teardown runs.
            statement.execute("DROP DATABASE IF EXISTS smartclinic_separate_roles_it WITH (FORCE)");
        }
    }

    /**
     * Seeds one synthetic organization and two facilities as the migration
     * owner. FORCED row level security constrains the owner too, so each write
     * must carry the tenant context and the organization must precede its
     * facilities to satisfy the foreign key.
     */
    private static void seedTenant(Statement statement, String org, String displayName,
                                   String mainName, String onlyCode, String onlyName) throws SQLException {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'role-seed'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', '" + displayName + "')");
        statement.execute("INSERT INTO public.facility (organization_id, code, name) VALUES "
                + "('" + org + "', 'MAIN', '" + mainName + "'), ('" + org + "', '" + onlyCode
                + "', '" + onlyName + "')");
    }

    /**
     * Opens a dedicated runtime-role connection with a transaction-local tenant
     * setting, matching the tenant guard used by the application. A new
     * connection avoids relying on the Spring pool's role or session state.
     */
    private static Connection runtimeConnectionBoundTo(String org) throws SQLException {
        Connection connection = DriverManager.getConnection(URL, APP_ROLE, "");
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.current_org', '" + org + "', true)");
        } catch (SQLException failure) {
            connection.close();
            throw failure;
        }
        return connection;
    }

    private static int scalarInt(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "expected a scalar row for " + sql);
            return result.getInt(1);
        }
    }

    private static long scalarLong(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "expected a scalar row for " + sql);
            return result.getLong(1);
        }
    }

    private static String scalarText(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "expected a scalar row for " + sql);
            return result.getString(1);
        }
    }

    private static boolean scalarFlag(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "expected a scalar row for " + sql);
            return result.getBoolean(1);
        }
    }

    /**
     * Asserts the role exists exactly once and carries no escalation flag. A
     * login role without BYPASSRLS, CREATEDB or CREATEROLE cannot widen its own
     * powers or escape a forced row level security policy.
     */
    private static void assertLeastPrivileged(Statement statement, String role) throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "SELECT rolcanlogin, rolbypassrls, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = '"
                        + role + "'")) {
            assertTrue(result.next(), role + " must exist");
            assertTrue(result.getBoolean("rolcanlogin"), role + " must be a login role");
            assertFalse(result.getBoolean("rolbypassrls"), role + " must not bypass row level security");
            assertFalse(result.getBoolean("rolcreatedb"), role + " must not create databases");
            assertFalse(result.getBoolean("rolcreaterole"), role + " must not create roles");
            assertFalse(result.next(), role + " must be a single role");
        }
    }

    private static List<String> visibleFacilityCodes(String org) throws SQLException {
        try (Connection connection = runtimeConnectionBoundTo(org);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT code FROM public.facility ORDER BY code")) {
            List<String> codes = new ArrayList<>();
            while (result.next()) {
                codes.add(result.getString(1));
            }
            return codes;
        }
    }

    /**
     * Positive proof that migration and runtime are two distinct, least
     * privileged roles and that the application's own datasource authenticates
     * as the runtime role rather than the migration owner.
     */
    @Test
    void migrationAndRuntimeRolesAreDistinctAndLeastPrivileged() throws Exception {
        assertNotEquals(MIGRATE_ROLE, APP_ROLE);
        // Catalog questions about schema objects must run inside the migrated database.
        try (Connection root = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = root.createStatement()) {
            assertEquals(2, scalarInt(statement, "SELECT count(*) FROM pg_roles WHERE rolname IN ('"
                    + MIGRATE_ROLE + "', '" + APP_ROLE + "')"));
            assertEquals(2, scalarInt(statement, "SELECT count(DISTINCT oid) FROM pg_roles WHERE rolname IN ('"
                    + MIGRATE_ROLE + "', '" + APP_ROLE + "')"));
            assertLeastPrivileged(statement, MIGRATE_ROLE);
            assertLeastPrivileged(statement, APP_ROLE);
            // The runtime role owns no table, so it cannot alter or drop the schema it reads.
            assertEquals(0, scalarInt(statement, "SELECT count(*) FROM pg_tables WHERE schemaname='public' "
                    + "AND tableowner='" + APP_ROLE + "'"));
            assertEquals(MIGRATE_ROLE, scalarText(statement,
                    "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid='public.facility'::regclass"));
            assertFalse(scalarFlag(statement,
                    "SELECT has_schema_privilege('" + APP_ROLE + "', 'public', 'CREATE')"));
            // A non-owner is subject to RLS even without FORCE. Asserting the
            // catalog flag is what actually proves the owner is constrained too.
            assertTrue(scalarFlag(statement, "SELECT bool_and(c.relrowsecurity AND c.relforcerowsecurity) "
                    + "FROM pg_class c WHERE c.relkind = 'r' AND c.relnamespace = 'public'::regnamespace "
                    + "AND c.relname IN ('organization', 'user_identity', 'facility', "
                    + "'membership', 'role_assignment', 'audit_event')"));
        }
        try (Connection connection = applicationDataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT current_user::text")) {
            assertTrue(result.next(), "the application datasource must expose its database role");
            assertEquals(APP_ROLE, result.getString(1));
        }
    }

    /**
     * Negative proof that the runtime role cannot bypass forced row level
     * security: whichever tenant it binds, it sees only that tenant's rows and
     * the peer tenant's rows stay concealed. The same must hold for the
     * migration owner, which only FORCE ROW LEVEL SECURITY constrains.
     */
    @Test
    void runtimeRoleCannotBypassForcedRowLevelSecurity() throws Exception {
        assertEquals(List.of("MAIN", "ONLY-A"), visibleFacilityCodes(ORG_A));
        assertEquals(List.of("MAIN", "ONLY-B"), visibleFacilityCodes(ORG_B));
        try (Connection connection = runtimeConnectionBoundTo(ORG_A);
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalarInt(statement, "SELECT count(*) FROM public.facility WHERE organization_id = '"
                    + ORG_B + "'::uuid"));
        }
        try (Connection owner = DriverManager.getConnection(URL, MIGRATE_ROLE, "");
             Statement statement = owner.createStatement()) {
            assertTrue(scalarFlag(statement, "SELECT public.app_org() IS NULL"));
            assertEquals(0L, scalarLong(statement, "SELECT count(*) FROM public.facility"));
        }
    }

    /**
     * Negative proof that a runtime connection with no tenant context fails
     * closed instead of reading every tenant's rows.
     */
    @Test
    void runtimeRoleWithoutTenantContextSeesNoRows() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, APP_ROLE, "");
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT current_setting('app.current_org', true)")) {
                assertTrue(result.next());
                String raw = result.getString(1);
                assertTrue(raw == null || raw.isEmpty(), "the test session must not inherit a tenant setting");
            }
            assertTrue(scalarFlag(statement, "SELECT public.app_org() IS NULL"));
            assertEquals(0L, scalarLong(statement, "SELECT count(*) FROM public.facility"));
        }
    }

    /**
     * Negative proof that the runtime role cannot apply the DDL reserved for
     * the migration role: a schema change is refused with insufficient
     * privilege and leaves no object behind.
     */
    @Test
    void runtimeRoleCannotRunMigrationDdl() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, APP_ROLE, "");
             Statement statement = connection.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                    () -> statement.execute("CREATE TABLE public.runtime_ddl_probe (id integer)"));
            assertEquals("42501", denied.getSQLState(), denied.getMessage());
        }
        try (Connection root = DriverManager.getConnection(URL, ADMIN, "");
             Statement statement = root.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT to_regclass('public.runtime_ddl_probe')")) {
                assertTrue(result.next());
                assertNull(result.getString(1), "a denied DDL statement must not create a table");
            }
            assertFalse(scalarFlag(statement,
                    "SELECT has_schema_privilege('" + APP_ROLE + "', 'public', 'CREATE')"));
        }
    }
}
