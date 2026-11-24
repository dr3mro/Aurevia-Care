package com.aurevia.tenant.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ARC-01 clause 2 acceptance against the migrated PostgreSQL schema with synthetic
 * tenants.
 *
 * <p>Tenant-owned records carry an {@code organization_id}. An internal foreign key
 * must therefore include that column on both the referencing and the referenced
 * side, so a row owned by one organization cannot point at a row owned by another.
 * The first test reads the live {@code pg_constraint} catalog of a freshly migrated
 * database and checks every foreign key on every tenant-owned table; the second
 * test attempts a cross-tenant reference through the real {@code membership} to
 * {@code user_identity} composite key and proves the database refuses it.</p>
 *
 * <p>The only foreign keys whose referencing column is not {@code organization_id}
 * point at the global {@code organization} registry through {@code destination_org};
 * those are the explicit cross-tenant exchange pointers of ARC-02 and are checked
 * to reference the registry's {@code organization_id} key.</p>
 *
 * <p>No HTTP route or contract belongs to a schema-key rule, so the test drives the
 * authoritative schema directly. All fixtures are synthetic UUIDs and subjects.</p>
 */
@Tag("integration")
class InternalForeignKeysIncludeOrganizationIdTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_arc01_clause2_it";
    static final String ROOT = "jdbc:postgresql://127.0.0.1:5432/postgres";
    static final String DATABASE = "aurevia_arc01_clause2_it";
    static final String MIGRATOR = "aurevia_migrate";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String CONTROL_MEMBERSHIP = "aaaaaaaa-0000-0000-0000-000000000001";
    static final String CROSS_TENANT_MEMBERSHIP = "aaaaaaaa-0000-0000-0000-000000000002";

    static String userA;
    static String userB;

    /** One catalog row: a foreign key declared on a tenant-owned table. */
    private record ForeignKey(String child, String parent, String name,
                              String referencingColumns, String referencedColumns) {
    }

    /**
     * Create NOBYPASSRLS runtime roles, a dedicated database and migrate it from
     * the authoritative Flyway scripts, then seed two synthetic tenants.
     *
     * @throws Exception when the database or the seed cannot be prepared
     */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(ROOT, ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER " + MIGRATOR);
        }
        Flyway.configure()
                .dataSource(URL, MIGRATOR, "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        userA = seedUser(ORG_A, "arc-user-a");
        userB = seedUser(ORG_B, "arc-user-b");
    }

    /** Drop the dedicated database so repeated runs start from a clean schema. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(ROOT, ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Open a connection bound to a synthetic tenant and actor.
     *
     * @param org    the active organization UUID
     * @param actor  the authenticated synthetic subject
     * @return a connection with {@code app.current_org} and {@code app.actor} set
     * @throws SQLException when the connection cannot be opened or bound
     */
    private static Connection connect(String org, String actor) throws SQLException {
        Connection connection = DriverManager.getConnection(URL, MIGRATOR, "");
        try (PreparedStatement bind = connection.prepareStatement(
                "SELECT set_config('app.current_org', ?, false), set_config('app.actor', ?, false)")) {
            bind.setString(1, org);
            bind.setString(2, actor);
            bind.execute();
        }
        return connection;
    }

    /**
     * Seed one synthetic organization and one user identity owned by it.
     *
     * @param org     the owning organization UUID
     * @param subject the synthetic subject unique inside that organization
     * @return the generated user identity id
     * @throws SQLException when the seed cannot be inserted or read back
     */
    private static String seedUser(String org, String subject) throws SQLException {
        try (Connection connection = connect(org, subject);
             PreparedStatement insertOrg = connection.prepareStatement(
                     "INSERT INTO public.organization (organization_id, display_name) "
                             + "VALUES (CAST(? AS uuid), ?) ON CONFLICT DO NOTHING");
             PreparedStatement insertUser = connection.prepareStatement(
                     "INSERT INTO public.user_identity (organization_id, subject, display_name) "
                             + "VALUES (CAST(? AS uuid), ?, ?)");
             PreparedStatement selectUser = connection.prepareStatement(
                     "SELECT id FROM public.user_identity WHERE organization_id = CAST(? AS uuid) "
                             + "AND subject = ?")) {
            insertOrg.setString(1, org);
            insertOrg.setString(2, "Org " + subject);
            assertEquals(1, insertOrg.executeUpdate(), "synthetic organization must be inserted");
            insertUser.setString(1, org);
            insertUser.setString(2, subject);
            insertUser.setString(3, subject);
            assertEquals(1, insertUser.executeUpdate(), "synthetic user identity must be inserted");
            selectUser.setString(1, org);
            selectUser.setString(2, subject);
            try (ResultSet result = selectUser.executeQuery()) {
                assertTrue(result.next(), "seeded user identity must be readable");
                return result.getString(1);
            }
        }
    }

    /**
     * Test whether a comma-joined catalog column list contains an exact column name.
     *
     * @param columns a comma-joined list of column names
     * @param name    the column to look for
     * @return true only for a whole matching token, never a substring
     */
    private static boolean hasColumn(String columns, String name) {
        if (columns == null) {
            return false;
        }
        for (String token : columns.split(",")) {
            if (token.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read every foreign key declared on a tenant-owned table from the live catalog.
     *
     * @return one row per foreign key, with its referencing and referenced column sets
     * @throws SQLException when the catalog query fails
     */
    private static List<ForeignKey> catalogForeignKeys() throws SQLException {
        String sql = """
                WITH tenant_table AS (
                    SELECT c.oid, c.relname
                      FROM pg_class c
                      JOIN pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = 'public'
                       AND c.relkind IN ('r', 'p')
                       AND c.relname <> 'organization'
                       AND EXISTS (SELECT 1 FROM pg_attribute a
                                    WHERE a.attrelid = c.oid AND a.attname = 'organization_id'
                                      AND a.attnum > 0 AND NOT a.attisdropped)
                )
                SELECT child.relname AS child_table,
                       parent.relname AS parent_table,
                       con.conname AS constraint_name,
                       (SELECT string_agg(att.attname, ',' ORDER BY k.ord)
                          FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k.attnum)
                         AS referencing_columns,
                       (SELECT string_agg(att.attname, ',' ORDER BY k.ord)
                          FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                          JOIN pg_attribute att ON att.attrelid = con.confrelid AND att.attnum = k.attnum)
                         AS referenced_columns
                  FROM pg_constraint con
                  JOIN tenant_table child ON child.oid = con.conrelid
                  JOIN pg_class parent ON parent.oid = con.confrelid
                 WHERE con.contype = 'f'
                 ORDER BY child.relname, con.conname
                """;
        List<ForeignKey> found = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, MIGRATOR, "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                found.add(new ForeignKey(
                        result.getString("child_table"),
                        result.getString("parent_table"),
                        result.getString("constraint_name"),
                        result.getString("referencing_columns"),
                        result.getString("referenced_columns")));
            }
        }
        return found;
    }

    /**
     * Prove that every internal foreign key in the migrated schema carries the
     * organization id on both sides, and that references to the global tenant
     * registry still target its organization id key.
     *
     * @throws SQLException when the catalog cannot be read
     */
    @Test
    void everyInternalForeignKeyOnTenantOwnedTablesIncludesOrganizationId() throws SQLException {
        List<ForeignKey> foreignKeys = catalogForeignKeys();
        assertTrue(foreignKeys.size() >= 100,
                "the catalog scan must cover the real schema, not an empty subset: scanned "
                        + foreignKeys.size() + " foreign keys");
        assertTrue(foreignKeys.stream().anyMatch(key ->
                        key.child().equals("membership") && key.parent().equals("user_identity")
                                && hasColumn(key.referencingColumns(), "organization_id")
                                && hasColumn(key.referencedColumns(), "organization_id")),
                "the V1 composite membership to user identity key must be visible in the scan");
        Set<String> registryPointers = new HashSet<>();
        for (ForeignKey key : foreignKeys) {
            assertTrue(hasColumn(key.referencedColumns(), "organization_id"),
                    "FK " + key.name() + " on " + key.child() + " -> " + key.parent()
                            + " references " + key.parent() + " without organization_id: "
                            + key.referencedColumns());
            if (hasColumn(key.referencingColumns(), "organization_id")) {
                continue;
            }
            // The only permitted exception is an explicit ARC-02 exchange pointer at
            // the global tenant registry, never an internal tenant-owned parent.
            assertEquals("organization", key.parent(),
                    "a key omitting organization_id may only target the global registry: " + key.name());
            assertEquals("organization_id", key.referencedColumns(),
                    "a registry reference must target the organization id key: " + key.name());
            registryPointers.add(key.child() + "." + key.referencingColumns());
        }
        assertEquals(Set.of("exchange_authorization.destination_org",
                        "exchange_order.destination_org",
                        "exchange_work_item.destination_org"),
                registryPointers,
                "the only foreign keys whose referencing side omits organization_id must be the "
                        + "three documented ARC-02 cross-tenant exchange pointers");
    }

    /**
     * Prove that a tenant cannot reference another tenant's row through the
     * composite membership to user identity key, even when it supplies its own
     * organization id, while a same-tenant reference still succeeds.
     *
     * <p>Both inserts run on the {@code aurevia_migrate} connection with
     * {@code app.current_org=ORG_A} and {@code app.actor} bound, because forced
     * row-level security applies to the migration owner (NOBYPASSRLS) too. The
     * same-tenant control proves that RLS, the role grants and the append trigger
     * permit the write, so the peer-tenant refusal can only come from the
     * composite foreign key (SQLSTATE 23503), not from a permission denial.</p>
     *
     * @throws SQLException when a statement cannot be executed
     */
    @Test
    void crossTenantReferenceWithoutMatchingOrganizationIdIsRefused() throws SQLException {
        try (Connection connection = connect(ORG_A, "arc-user-a");
             PreparedStatement control = connection.prepareStatement(
                     "INSERT INTO public.membership (organization_id, id, user_id, status) "
                             + "VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'active')")) {
            control.setString(1, ORG_A);
            control.setString(2, CONTROL_MEMBERSHIP);
            control.setString(3, userA);
            assertEquals(1, control.executeUpdate(),
                    "a same-tenant composite reference must be accepted");
        }

        try (Connection connection = connect(ORG_A, "arc-user-a");
             PreparedStatement crossTenant = connection.prepareStatement(
                     "INSERT INTO public.membership (organization_id, id, user_id, status) "
                             + "VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'active')")) {
            crossTenant.setString(1, ORG_A);
            crossTenant.setString(2, CROSS_TENANT_MEMBERSHIP);
            crossTenant.setString(3, userB);
            SQLException refused = assertThrows(SQLException.class, crossTenant::executeUpdate,
                    "the composite foreign key must refuse the peer organization's user id");
            assertEquals("23503", refused.getSQLState(),
                    "the refusal must be a foreign-key violation (23503), not a permission "
                            + "denial (42501) or a check violation (23514)");
            assertTrue(refused.getMessage().contains("membership_organization_id_user_id_fkey"),
                    "the violated key must be the composite membership to user identity key");
        }
    }
}
