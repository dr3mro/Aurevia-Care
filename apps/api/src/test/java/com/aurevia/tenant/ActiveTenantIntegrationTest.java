package com.aurevia.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Real-PostgreSQL gate for the cross-tenant active-organization enumerator.
 *
 * <p>All organizations are synthetic. Separate connections prove that the
 * definer function enumerates active tenants while a direct application read
 * remains limited to the caller's tenant.</p>
 */
@Tag("integration")
class ActiveTenantIntegrationTest {

    private static final String ORG_A = "61111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "62222222-2222-2222-2222-222222222222";
    private static final String ORG_C = "63333333-3333-3333-3333-333333333333";
    private static final String ORG_D = "64444444-4444-4444-4444-444444444444";
    private static final UUID A_ID = UUID.fromString(ORG_A);
    private static final UUID B_ID = UUID.fromString(ORG_B);
    private static final UUID C_ID = UUID.fromString(ORG_C);
    private static final UUID D_ID = UUID.fromString(ORG_D);
    private static final String DB = "aurevia_active_tenant_it";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DB;
    private static final String ADMIN = System.getenv().getOrDefault("AUREVIA_PG_ADMIN", "postgres");

    private static Connection migrateConnection;
    private static Connection appConnection;
    private static Connection ungrantedConnection;
    private static JdbcActiveTenantSource source;

    /** Provision synthetic roles, a fresh database, the schema and the fixtures. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_it_ungranted') THEN "
                    + "CREATE ROLE aurevia_it_ungranted LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DB + " OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations").load().migrate();
        migrateConnection = DriverManager.getConnection(URL, "aurevia_migrate", "");
        try (Statement statement = migrateConnection.createStatement()) {
            statement.execute("GRANT CONNECT ON DATABASE " + DB + " TO aurevia_it_ungranted");
            statement.execute("GRANT USAGE ON SCHEMA public TO aurevia_it_ungranted");
        }
        appConnection = DriverManager.getConnection(URL, "aurevia_app", "");
        ungrantedConnection = DriverManager.getConnection(URL, "aurevia_it_ungranted", "");
        source = new JdbcActiveTenantSource(
                new NamedParameterJdbcTemplate(new SingleConnectionDataSource(appConnection, true)));
        seed();
    }

    /** Close every dedicated connection and remove the synthetic database. */
    @AfterAll
    static void cleanUp() throws Exception {
        if (migrateConnection != null) {
            migrateConnection.close();
        }
        if (appConnection != null) {
            appConnection.close();
        }
        if (ungrantedConnection != null) {
            ungrantedConnection.close();
        }
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DB + " WITH (FORCE)");
        }
    }

    /** Clear tenant and hostile session state before each scenario. */
    @BeforeEach
    void resetSession() throws Exception {
        clearSession(appConnection);
        clearSession(migrateConnection);
    }

    /** Leave no tenant or hostile session state for the next scenario. */
    @AfterEach
    void clearSessionState() throws Exception {
        clearSession(appConnection);
        clearSession(migrateConnection);
    }

    /**
     * Active tenants are enumerated with no tenant context; the inactive fixture
     * is absent and a deactivated membership fold never decides membership.
     */
    @Test
    void activeOrganizationsAreEnumeratedWithoutTenantContext() {
        List<UUID> ids = source.activeOrganizationIds();
        assertEquals(List.of(A_ID, B_ID), ids, "deterministic identifier order is part of the contract");
        assertEquals(ids.size(), new HashSet<>(ids).size(), "an organization must appear once");
    }

    /** An organization whose active flag is false is not scheduled. */
    @Test
    void inactiveOrganizationIsOmitted() {
        assertFalse(source.activeOrganizationIds().contains(C_ID));
    }

    /** Another tenant's GUC cannot narrow the authoritative enumeration. */
    @Test
    void callerTenantGucDoesNotNarrowTheEnumeration() throws Exception {
        setTenant(appConnection, ORG_B);
        assertEquals(Set.of(A_ID, B_ID), new HashSet<>(source.activeOrganizationIds()));
        setTenant(appConnection, ORG_C);
        assertEquals(Set.of(A_ID, B_ID), new HashSet<>(source.activeOrganizationIds()));
    }

    /** A direct application SELECT stays limited to the caller's tenant. */
    @Test
    void directOrganizationSelectStaysTenantScoped() throws Exception {
        assertEquals(Set.of(), visibleOrganizationIds(appConnection));
        setTenant(appConnection, ORG_A);
        assertEquals(Set.of(A_ID), visibleOrganizationIds(appConnection));
        setTenant(appConnection, ORG_B);
        assertEquals(Set.of(B_ID), visibleOrganizationIds(appConnection));
    }

    /** A role without EXECUTE cannot call the enumerator at all. */
    @Test
    void callerWithoutExecuteIsDenied() throws Exception {
        try (Statement statement = ungrantedConnection.createStatement()) {
            SQLException denied = assertThrows(SQLException.class, () -> statement.executeQuery(
                    "SELECT organization_id FROM public.active_organization_ids()"));
            assertEquals("42501", denied.getSQLState());
        }
        try (Statement statement = migrateConnection.createStatement();
             ResultSet privileges = statement.executeQuery("""
                     SELECT has_function_privilege('aurevia_it_ungranted',
                                'public.active_organization_ids()', 'EXECUTE'),
                            has_function_privilege('aurevia_app',
                                'public.active_organization_ids()', 'EXECUTE')
                     """)) {
            assertTrue(privileges.next());
            assertFalse(privileges.getBoolean(1));
            assertTrue(privileges.getBoolean(2));
        }
    }

    /** A hostile session search path and shadow function cannot change the result. */
    @Test
    void hostileSearchPathCannotChangeResult() throws Exception {
        try (Statement setup = migrateConnection.createStatement()) {
            setup.execute("DROP SCHEMA IF EXISTS hostile_it CASCADE");
            setup.execute("CREATE SCHEMA hostile_it");
            setup.execute("CREATE FUNCTION hostile_it.active_organization_ids() "
                    + "RETURNS TABLE (organization_id uuid) LANGUAGE sql STABLE AS $$ SELECT '" + ORG_C + "'::uuid $$");
            setup.execute("GRANT USAGE ON SCHEMA hostile_it TO aurevia_app");
            setup.execute("GRANT EXECUTE ON FUNCTION hostile_it.active_organization_ids() TO aurevia_app");
        }
        try (Statement statement = appConnection.createStatement()) {
            statement.execute("SET search_path = hostile_it, public");
            try (ResultSet shadow = statement.executeQuery(
                    "SELECT organization_id::text FROM active_organization_ids()")) {
                assertTrue(shadow.next(), "the shadow function proves the session path is hostile");
                assertEquals(ORG_C, shadow.getString(1));
            }
            assertEquals(Set.of(A_ID, B_ID), new HashSet<>(source.activeOrganizationIds()));
        } finally {
            try (Statement statement = appConnection.createStatement()) {
                statement.execute("RESET search_path");
            }
            try (Statement statement = migrateConnection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS hostile_it CASCADE");
            }
        }
    }

    /** The application role cannot bypass RLS and is not a member of the definer role. */
    @Test
    void applicationRoleCannotBypassRlsOrInheritTheDefiner() throws Exception {
        try (Statement statement = appConnection.createStatement();
             ResultSet role = statement.executeQuery("""
                     SELECT rolbypassrls, rolsuper FROM pg_roles WHERE rolname = 'aurevia_app'
                     """)) {
            assertTrue(role.next());
            assertFalse(role.getBoolean(1));
            assertFalse(role.getBoolean(2));
        }
        try (Statement statement = appConnection.createStatement();
             ResultSet memberships = statement.executeQuery("""
                     SELECT count(*) FROM pg_auth_members m
                       JOIN pg_roles r ON r.oid = m.roleid
                       JOIN pg_roles u ON u.oid = m.member
                      WHERE r.rolname = 'aurevia_migrate' AND u.rolname = 'aurevia_app'
                     """)) {
            assertTrue(memberships.next());
            assertEquals(0, memberships.getInt(1), "application must not inherit the definer role");
        }
    }

    /** The function is a narrow, owner-only definer with a pinned search path. */
    @Test
    void functionIsAnOwnerOnlyDefinerWithPinnedSearchPath() throws Exception {
        try (Statement statement = migrateConnection.createStatement();
             ResultSet function = statement.executeQuery("""
                     SELECT pg_get_userbyid(p.proowner), p.prosecdef,
                            p.proconfig::text, p.proacl::text
                       FROM pg_proc p
                      WHERE p.oid = 'public.active_organization_ids()'::regprocedure
                     """)) {
            assertTrue(function.next());
            assertEquals("aurevia_migrate", function.getString(1));
            assertTrue(function.getBoolean(2), "the function must run as its owner");
            assertTrue(function.getString(3).contains("search_path=pg_catalog"));
            assertFalse(function.getString(3).contains("enumerate_active_orgs"));
            assertTrue(function.getString(4).contains("aurevia_app=X"));
        }
        try (Statement statement = migrateConnection.createStatement();
             ResultSet publicGrants = statement.executeQuery("""
                     SELECT count(*) FROM pg_proc p, aclexplode(p.proacl) a
                      WHERE p.oid = 'public.active_organization_ids()'::regprocedure
                        AND a.grantee = 0
                     """)) {
            assertTrue(publicGrants.next());
            assertEquals(0, publicGrants.getInt(1), "no PUBLIC EXECUTE grant is allowed");
        }
        try (Statement statement = migrateConnection.createStatement();
             ResultSet policies = statement.executeQuery("""
                     SELECT unnest(p.roles)::text FROM pg_policies p
                      WHERE p.schemaname = 'public' AND p.tablename = 'organization'
                        AND p.policyname = 'active_organization_enumerator'
                     """)) {
            Set<String> roles = new LinkedHashSet<>();
            while (policies.next()) {
                roles.add(policies.getString(1));
            }
            assertEquals(Set.of("aurevia_migrate"), roles, "the permissive policy is owner-only");
        }
    }

    /** No hostile GUC or table privilege turns direct application reads cross-tenant. */
    @Test
    void hostileGucDoesNotWidenApplicationTablePrivileges() throws Exception {
        setTenant(appConnection, ORG_A);
        try (Statement statement = appConnection.createStatement()) {
            statement.execute("SET app.enumerate_active_orgs = 'on'");
            assertEquals(Set.of(A_ID), visibleOrganizationIds(appConnection));
            assertDenied(statement, "INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG_D + "', 'Synthetic Org D')");
            assertDenied(statement, "UPDATE public.organization SET active = false "
                    + "WHERE organization_id = '" + ORG_A + "'");
            assertDenied(statement, "DELETE FROM public.organization "
                    + "WHERE organization_id = '" + ORG_A + "'");
        }
    }

    /** The enumeration follows database state, not a hard-coded or cached list. */
    @Test
    void enumerationTracksDatabaseState() throws Exception {
        try (Statement statement = migrateConnection.createStatement()) {
            setTenant(migrateConnection, ORG_B);
            statement.execute("UPDATE public.organization SET active = false "
                    + "WHERE organization_id = '" + ORG_B + "'");
            assertEquals(Set.of(A_ID), new HashSet<>(source.activeOrganizationIds()));
            setTenant(migrateConnection, ORG_D);
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG_D + "', 'Synthetic Org D')");
            assertEquals(Set.of(A_ID, D_ID), new HashSet<>(source.activeOrganizationIds()));
        } finally {
            try (Statement statement = migrateConnection.createStatement()) {
                setTenant(migrateConnection, ORG_B);
                statement.execute("UPDATE public.organization SET active = true "
                        + "WHERE organization_id = '" + ORG_B + "'");
                setTenant(migrateConnection, ORG_D);
                statement.execute("DELETE FROM public.organization "
                        + "WHERE organization_id = '" + ORG_D + "'");
            }
        }
    }

    /** Seed three synthetic organizations; the third is explicitly inactive. */
    private static void seed() throws Exception {
        try (Statement statement = migrateConnection.createStatement()) {
            insertOrganization(statement, ORG_A, "Synthetic Org A", true);
            insertOrganization(statement, ORG_B, "Synthetic Org B", true);
            insertOrganization(statement, ORG_C, "Synthetic Org C", false);
        }
    }

    /** Insert one organization in its own tenant context (FORCE RLS binds the owner). */
    private static void insertOrganization(Statement statement, String organizationId,
            String displayName, boolean active) throws Exception {
        setTenant(migrateConnection, organizationId);
        statement.execute("INSERT INTO public.organization (organization_id, display_name, active) "
                + "VALUES ('" + organizationId + "', '" + displayName + "', " + active + ")");
    }

    /** Bind the given synthetic organization to a session. */
    private static void setTenant(Connection connection, String organizationId) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organizationId + "'");
        }
    }

    /** Return the organizations a plain application SELECT can see. */
    private static Set<UUID> visibleOrganizationIds(Connection connection) throws SQLException {
        Set<UUID> visible = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT organization_id::text FROM public.organization")) {
            while (rows.next()) {
                visible.add(UUID.fromString(rows.getString(1)));
            }
        }
        return visible;
    }

    /** Assert that a privilege boundary rejects a table write with SQLSTATE 42501. */
    private static void assertDenied(Statement statement, String sql) throws SQLException {
        SQLException denied = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
        assertEquals("42501", denied.getSQLState());
    }

    /** Clear tenant and hostile settings held by a reused connection. */
    private static void clearSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = ''");
            statement.execute("RESET app.enumerate_active_orgs");
            statement.execute("RESET search_path");
        }
    }
}
