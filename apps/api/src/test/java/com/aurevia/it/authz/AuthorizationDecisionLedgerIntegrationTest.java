package com.aurevia.it.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.JdbcDecisionSink;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02 acceptance for the durable authorization decision ledger on PostgreSQL.
 *
 * <p>The production {@code DecisionSink} bean is exercised through the real
 * {@link com.aurevia.api.authorization.CommandAuthorization} gate inside
 * tenant-guarded transactions. All identifiers and narratives are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_authorization_decision_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class AuthorizationDecisionLedgerIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ORG_C = "33333333-3333-3333-3333-333333333333";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_authorization_decision_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);

    @Autowired
    CommandAuthorization.DecisionSink decisionSink;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_authorization_decision_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_authorization_decision_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : List.of(ORG_A, ORG_B, ORG_C)) {
                statement.execute("SET app.current_org = '" + org + "'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                        + "VALUES ('" + org + "', 'Synthetic ledger tenant')");
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_authorization_decision_it WITH (FORCE)");
        }
    }

    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "AUTHORIZATION", "it-authorization"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private CommandAuthorization authorization() {
        return CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                identity -> new MemberContext(
                        List.of(ORG_A.equals(identity.organizationId()) ? "tenant_admin" : "reception"), true),
                decisionSink, CLOCK);
    }

    private static long count(String role, String org, String predicate) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, role, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.authorization_decision WHERE " + predicate)) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static boolean executes(String role, String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, role, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute(sql);
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    @Test
    void productionBeanIsTheJdbcDecisionSink() {
        assertNotNull(decisionSink);
        assertTrue(decisionSink instanceof JdbcDecisionSink,
                "the production DecisionSink bean must be the PostgreSQL ledger");
    }

    @Test
    void allowAndDenyDecisionsAreDurableAndTenantIsolated() throws Exception {
        as(ORG_A, "admin-a", () -> authorization().authorize(
                new CommandRequest("createFacility", null, null),
                new ServerIdentity(ORG_A, "admin-a", "corr-allow")));
        as(ORG_B, "reception-b", () -> {
            ApiException denial = assertThrows(ApiException.class, () -> authorization().authorize(
                    new CommandRequest("createFacility", null, null),
                    new ServerIdentity(ORG_B, "reception-b", "corr-deny")));
            assertEquals(403, denial.status());
            return null;
        });

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT command_id, subject, action, resource_type, decision, reason_code, "
                            + "correlation_id, decided_at FROM public.authorization_decision "
                            + "WHERE organization_id = CAST('" + ORG_A + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("createFacility", result.getString("command_id"));
                assertEquals("admin-a", result.getString("subject"));
                assertEquals("FACILITY_WRITE", result.getString("action"));
                assertEquals("command", result.getString("resource_type"));
                assertEquals("allow", result.getString("decision"));
                assertEquals("ALLOWED", result.getString("reason_code"));
                assertEquals("corr-allow", result.getString("correlation_id"));
                assertEquals(CLOCK.instant(), result.getTimestamp("decided_at").toInstant());
                assertFalse(result.next());
            }
            // Forced RLS conceals the other tenant's row even with an explicit id.
            try (ResultSet result = statement.executeQuery(
                    "SELECT count(*) FROM public.authorization_decision "
                            + "WHERE organization_id = CAST('" + ORG_B + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals(0, result.getLong(1));
            }
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            try (ResultSet result = statement.executeQuery(
                    "SELECT decision, reason_code FROM public.authorization_decision "
                            + "WHERE organization_id = CAST('" + ORG_B + "' AS uuid)")) {
                assertTrue(result.next());
                assertEquals("deny", result.getString("decision"));
                assertEquals("COMMAND_FORBIDDEN", result.getString("reason_code"));
            }
        }
    }

    @Test
    void aDecisionForAnotherTenantFailsClosedWithAuditUnavailable() throws Exception {
        as(ORG_A, "admin-a", () -> {
            Decision crossTenant = new Decision("createFacility", "FACILITY_WRITE", ORG_B, "admin-a",
                    "corr-cross", true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant());
            ApiException failure = assertThrows(ApiException.class, () -> decisionSink.record(crossTenant));
            assertEquals(500, failure.status());
            assertEquals("AUDIT_UNAVAILABLE", failure.code());
            return null;
        });
        assertEquals(0, count("aurevia_migrate", ORG_B, "reason_code = 'ALLOWED' AND correlation_id = 'corr-cross'"));
    }

    @Test
    void anUnauthenticatedDecisionIsRecordedAsTenantlessEvidence() throws Exception {
        assertEquals(0, count("aurevia_migrate", ORG_A, "reason_code = 'UNAUTHENTICATED'"));
        as(ORG_A, "admin-a", () -> {
            ApiException failure = assertThrows(ApiException.class,
                    () -> authorization().authorize(null, new ServerIdentity(null, null, "corr-unauth")));
            assertEquals(401, failure.status());
            return null;
        });
        assertEquals(1, count("aurevia_migrate", ORG_A,
                "organization_id IS NULL AND reason_code = 'UNAUTHENTICATED' AND correlation_id = 'corr-unauth'"));
    }

    @Test
    void ledgerIsAppendOnlyBoundedAndForcedRls() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT relrowsecurity, relforcerowsecurity "
                    + "FROM pg_class WHERE oid = 'public.authorization_decision'::regclass")) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1));
                assertTrue(result.getBoolean(2));
            }
            Set<String> columns = new HashSet<>();
            try (ResultSet result = statement.executeQuery("SELECT column_name "
                    + "FROM information_schema.columns WHERE table_schema = 'public' "
                    + "AND table_name = 'authorization_decision'")) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
            assertEquals(Set.of("id", "organization_id", "command_id", "subject", "action",
                    "resource_type", "decision", "reason_code", "correlation_id", "decided_at"), columns);

            Set<String> grants = new HashSet<>();
            try (ResultSet result = statement.executeQuery("SELECT privilege_type "
                    + "FROM information_schema.role_table_grants WHERE grantee = 'aurevia_app' "
                    + "AND table_schema = 'public' AND table_name = 'authorization_decision'")) {
                while (result.next()) {
                    grants.add(result.getString(1));
                }
            }
            assertEquals(Set.of("SELECT", "INSERT"), grants);
        }
        // Seed a row in this test's own tenant so the mutation assertions always
        // fire the trigger; JUnit does not guarantee a declaration order.
        assertTrue(executes("aurevia_migrate", ORG_C,
                "INSERT INTO public.authorization_decision "
                        + "(organization_id, command_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                        + "VALUES (CAST('" + ORG_C + "' AS uuid), 'createFacility', 'probe', 'FACILITY_WRITE', "
                        + "'command', 'allow', 'PROBE', 'corr-append')"));
        String insert = "INSERT INTO public.authorization_decision "
                + "(organization_id, command_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                + "VALUES (CAST('" + ORG_C + "' AS uuid), '%s', 'probe', 'FACILITY_WRITE', '%s', '%s', 'PROBE', 'corr-probe')";
        assertRejected("aurevia_migrate", ORG_C,
                String.format(insert, "createFacility", "command", "maybe"), "23514");
        assertRejected("aurevia_migrate", ORG_C,
                String.format(insert, "c".repeat(129), "command", "deny"), "23514");
        assertRejected("aurevia_migrate", ORG_C,
                String.format(insert, "createFacility", "free-text", "deny"), "23514");

        // The app role has no UPDATE/DELETE grant (42501); the owner does, so
        // its mutation must be stopped by the trigger (44000).
        assertRejected("aurevia_app", ORG_C,
                "UPDATE public.authorization_decision SET reason_code = 'changed'", "42501");
        assertRejected("aurevia_app", ORG_C, "DELETE FROM public.authorization_decision", "42501");
        assertRejected("aurevia_migrate", ORG_C,
                "UPDATE public.authorization_decision SET reason_code = 'changed'", "44000");
        assertRejected("aurevia_migrate", ORG_C, "DELETE FROM public.authorization_decision", "44000");
        assertRejected("aurevia_migrate", ORG_C, "TRUNCATE TABLE public.authorization_decision", "44000");
    }

    private static void assertRejected(String role, String org, String sql, String sqlState) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, role, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            SQLException failure = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertEquals(sqlState, failure.getSQLState());
        }
    }
}
