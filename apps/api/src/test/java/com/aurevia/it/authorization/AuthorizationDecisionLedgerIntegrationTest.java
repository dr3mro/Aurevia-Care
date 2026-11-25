package com.aurevia.it.authorization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.JdbcDecisionSink;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02/ARC-04 acceptance for the durable authorization decision ledger.
 *
 * <p>The production Spring bean writes through a tenant-guarded transaction into
 * real PostgreSQL. Only synthetic organizations, subjects and correlation ids are
 * used; no patient identifier or request body reaches this ledger.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_decision_ledger_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class AuthorizationDecisionLedgerIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_decision_ledger_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final Instant DECIDED_AT = Instant.parse("2026-11-25T10:15:30Z");
    static final Set<String> EXPECTED_COLUMNS = Set.of("organization_id", "id", "subject", "action",
            "resource_type", "decision", "reason_code", "correlation_id", "decided_at");

    @Autowired
    DecisionSink decisionSink;

    @Autowired
    TransactionTemplate transactions;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_decision_ledger_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_decision_ledger_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : List.of(ORG_A, ORG_B)) {
                statement.execute("SET app.current_org = '" + org + "'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                        + "VALUES ('" + org + "', 'Synthetic " + suffix(org) + "')");
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_decision_ledger_it WITH (FORCE)");
        }
    }

    /** Bind the tenant exactly as the HTTP interceptor does, then run inside the guarded transaction. */
    private void as(String org, String subject, Runnable action) {
        TenantContext.set(new TenantContext(org, subject, "AUTHZ_LEDGER", "corr-ledger"));
        try {
            transactions.executeWithoutResult(status -> action.run());
        } finally {
            TenantContext.clear();
        }
    }

    private static Decision decision(String org, boolean allowed, String reason, String correlation) {
        return new Decision("verifyIdentity", "IDENTITY_VERIFY", org, "verifier-" + suffix(org), correlation,
                allowed, reason, List.of("lab_verifier"), DECIDED_AT);
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    private static void bind(Connection connection, String org) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), false)")) {
            statement.setString(1, org);
            statement.execute();
        }
    }

    private static boolean executes(String org, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            bind(connection, org);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return true;
        } catch (SQLException failure) {
            return false;
        }
    }

    @Test
    void productionDecisionSinkBeanIsTheJdbcLedger() {
        assertNotNull(decisionSink);
        assertTrue(decisionSink instanceof JdbcDecisionSink,
                "the production DecisionSink bean must persist to PostgreSQL");
    }

    @Test
    void recordsAllowAndDenyRowsWithOnlyBoundedColumns() throws Exception {
        as(ORG_A, "verifier-1111", () -> {
            decisionSink.record(decision(ORG_A, true, "ALLOWED", "corr-allow"));
            decisionSink.record(decision(ORG_A, false, "COMMAND_FORBIDDEN", "corr-deny"));
        });

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            bind(connection, ORG_A);
            Set<String> columns = new LinkedHashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT column_name FROM information_schema.columns "
                         + "WHERE table_schema = 'public' AND table_name = 'authorization_decision'")) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
            assertEquals(EXPECTED_COLUMNS, columns, "the ledger stores codes and identifiers only");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT subject, action, resource_type, decision, reason_code, correlation_id, decided_at "
                            + "FROM public.authorization_decision WHERE correlation_id = ? ORDER BY decided_at")) {
                statement.setString(1, "corr-allow");
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("verifier-1111", result.getString("subject"));
                    assertEquals("IDENTITY_VERIFY", result.getString("action"));
                    assertEquals("verifyIdentity", result.getString("resource_type"));
                    assertEquals("allow", result.getString("decision"));
                    assertEquals("ALLOWED", result.getString("reason_code"));
                    assertEquals("corr-allow", result.getString("correlation_id"));
                    assertNotNull(result.getTimestamp("decided_at"));
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT decision, reason_code FROM public.authorization_decision WHERE correlation_id = ?")) {
                statement.setString(1, "corr-deny");
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("deny", result.getString("decision"));
                    assertEquals("COMMAND_FORBIDDEN", result.getString("reason_code"));
                }
            }
        }
    }

    @Test
    void forcedRlsConcealsAnotherTenantsRowsAndRejectsCrossTenantInsert() throws Exception {
        as(ORG_A, "verifier-1111", () -> decisionSink.record(decision(ORG_A, true, "ALLOWED", "corr-isolation")));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "")) {
            bind(connection, ORG_B);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT count(*) FROM public.authorization_decision")) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong(1), "tenant B sees no tenant A decisions");
            }
            SQLException forged = assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO public.authorization_decision "
                            + "(organization_id, subject, action, resource_type, decision, reason_code, "
                            + "correlation_id) VALUES ('" + ORG_A + "', 'forged', 'ACTION', 'cmd', "
                            + "'allow', 'ALLOWED', 'corr-forged')");
                }
            }, "WITH CHECK forbids writing a decision into another tenant");
            assertTrue(forged.getMessage().contains("row-level security"),
                    "the rejection must come from the tenant policy, not a syntax or constraint fault");
        }
    }

    @Test
    void ledgerIsAppendOnlyAndForcedRlsIsEnabledForEveryRole() throws Exception {
        as(ORG_A, "verifier-1111", () -> decisionSink.record(decision(ORG_A, true, "ALLOWED", "corr-immutable")));

        String id;
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            bind(connection, ORG_A);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT id::text FROM public.authorization_decision WHERE correlation_id = 'corr-immutable'")) {
                assertTrue(result.next());
                id = result.getString(1);
            }
        }
        assertFalse(executes(ORG_A, "UPDATE public.authorization_decision "
                + "SET reason_code = 'FORGED' WHERE id = CAST('" + id + "' AS uuid)"));
        assertFalse(executes(ORG_A, "DELETE FROM public.authorization_decision "
                + "WHERE id = CAST('" + id + "' AS uuid)"));
        assertFalse(executes(ORG_A, "TRUNCATE TABLE public.authorization_decision"),
                "truncation is rejected for every role, including the migration owner");

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT relrowsecurity, relforcerowsecurity "
                     + "FROM pg_class WHERE oid = 'public.authorization_decision'::regclass")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
            assertTrue(result.getBoolean(2));
        }
    }

    /** Count ledger rows for the gate-driven bounded-code cases. */
    private static long count(Connection connection, String correlation, String resourceType, String action)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM public.authorization_decision "
                        + "WHERE correlation_id = ? AND resource_type = ? AND action = ?")) {
            statement.setString(1, correlation);
            statement.setString(2, resourceType);
            statement.setString(3, action);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    @Test
    void gatePersistsFixedCodesAbsentCorrelationAndDenyThroughTheProductionSink() throws Exception {
        CommandCatalog catalog = CommandCatalog.synthetic();
        Clock clock = Clock.fixed(DECIDED_AT, ZoneOffset.UTC);
        CommandAuthorization allowing = CommandAuthorization.synthetic(catalog,
                identity -> new MemberContext(List.of("tenant_admin"), true), decisionSink, clock);
        CommandAuthorization denying = CommandAuthorization.synthetic(catalog,
                identity -> new MemberContext(List.of("reception"), true), decisionSink, clock);

        as(ORG_A, "admin-1111", () -> {
            ApiException unknown = assertThrows(ApiException.class, () -> allowing.authorize(
                    new CommandRequest("notACommand", "tenant_admin", null),
                    new ServerIdentity(ORG_A, "admin-1111", "corr-unknown-gate")));
            assertEquals(422, unknown.status());
            ApiException malformed = assertThrows(ApiException.class, () -> allowing.authorize(
                    new CommandRequest("   ", null, null),
                    new ServerIdentity(ORG_A, "admin-1111", "corr-malformed-gate")));
            assertEquals(422, malformed.status());
            Decision allowed = allowing.authorize(new CommandRequest("createFacility", null, null),
                    new ServerIdentity(ORG_A, "admin-1111", null));
            assertTrue(allowed.allowed());
        });
        as(ORG_A, "reception-1111", () -> {
            ApiException denied = assertThrows(ApiException.class, () -> denying.authorize(
                    new CommandRequest("createFacility", null, null),
                    new ServerIdentity(ORG_A, "reception-1111", "corr-deny-gate")));
            assertEquals(403, denied.status());
        });

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            bind(connection, ORG_A);
            assertEquals(1L, count(connection, "corr-unknown-gate", "UNKNOWN_COMMAND", ""));
            assertEquals(1L, count(connection, "corr-malformed-gate", "INVALID_COMMAND", ""));
            assertEquals(1L, count(connection, "", "createFacility", "FACILITY_WRITE"),
                    "an absent correlation id is a bounded empty value, not a failed insert");
            assertEquals(1L, count(connection, "corr-deny-gate", "createFacility", "FACILITY_WRITE"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT decision, reason_code FROM public.authorization_decision "
                            + "WHERE correlation_id = 'corr-deny-gate'")) {
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("deny", result.getString("decision"));
                    assertEquals("COMMAND_FORBIDDEN", result.getString("reason_code"));
                }
            }
        }
    }
}
