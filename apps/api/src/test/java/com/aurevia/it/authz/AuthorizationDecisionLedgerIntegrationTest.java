package com.aurevia.it.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
import com.aurevia.api.authorization.InMemoryDecisionSink;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02 acceptance for the durable authorization decision ledger on real PostgreSQL.
 *
 * <p>The production {@link com.aurevia.api.authorization.CommandAuthorization.DecisionSink}
 * bean is called inside the tenant-guarded
 * transaction, which binds {@code app.current_org}. Two synthetic organizations
 * use the same subject so isolation is observable. All identifiers are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_authz_ledger_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class AuthorizationDecisionLedgerIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_authz_ledger_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final Instant DECIDED_AT = Instant.parse("2026-11-25T10:15:30Z");

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "id", "organization_id", "subject", "action", "resource_type",
            "decision", "reason_code", "correlation_id", "decided_at");

    @Autowired
    DecisionSink sink;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_authz_ledger_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_authz_ledger_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            for (String org : List.of(ORG_A, ORG_B)) {
                statement.execute("SET app.current_org = '" + org + "'");
                statement.execute("SET app.actor = 'bootstrap'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                        + "VALUES ('" + org + "', 'Synthetic " + org.substring(0, 4) + "')");
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_authz_ledger_it WITH (FORCE)");
        }
    }

    @BeforeEach
    void bindTenant() {
        TenantContext.set(new TenantContext(ORG_A, "shared-user", "AUTH", "corr-ledger"));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static Decision decision(String org, boolean allowed, String reasonCode, String commandId,
                                     String correlationId) {
        return new Decision(commandId, "FACILITY_WRITE", org, "shared-user", correlationId,
                allowed, reasonCode, List.of("tenant_admin"), DECIDED_AT);
    }

    private void record(String org, Decision decision) {
        TenantContext.set(new TenantContext(org, "shared-user", "AUTH", "corr-ledger"));
        transactions.executeWithoutResult(status -> sink.record(decision));
    }

    private static Connection open(String role, String org) throws SQLException {
        Connection connection = DriverManager.getConnection(URL, role, "");
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', 'shared-user', true)")) {
            statement.setString(1, org);
            statement.execute();
        }
        return connection;
    }

    private static long count(String role, String org) throws Exception {
        try (Connection connection = open(role, org);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT count(*) FROM public.authorization_decision")) {
            assertTrue(result.next());
            return result.getLong(1);
        } finally {
            // Connections are never returned to a pool with a prior tenant bound.
        }
    }

    private static boolean executes(String role, String org, String sql) {
        try (Connection connection = open(role, org);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
            return true;
        } catch (SQLException failure) {
            return false;
        }
    }

    @Test
    void allowAndDenyDecisionsPersistWithStableCodesAndOnlyAuditColumns() throws Exception {
        record(ORG_A, decision(ORG_A, true, "ALLOWED", "createFacility", "corr-allow-deny"));
        record(ORG_A, decision(ORG_A, false, "COMMAND_FORBIDDEN", "createFacility", "corr-allow-deny"));

        try (Connection connection = open("aurevia_app", ORG_A);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT decision, reason_code, subject, action, resource_type,
                            correlation_id, decided_at::text
                       FROM public.authorization_decision
                      WHERE correlation_id = 'corr-allow-deny' ORDER BY decision
                     """)) {
            assertTrue(result.next());
            assertEquals("allow", result.getString(1));
            assertEquals("ALLOWED", result.getString(2));
            assertEquals("shared-user", result.getString(3));
            assertEquals("FACILITY_WRITE", result.getString(4));
            assertEquals("createFacility", result.getString(5));
            assertEquals("corr-allow-deny", result.getString(6));
            assertNotNull(result.getString(7));
            assertTrue(result.next());
            assertEquals("deny", result.getString(1));
            assertEquals("COMMAND_FORBIDDEN", result.getString(2));
            assertFalse(result.next());
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT column_name FROM information_schema.columns
                      WHERE table_schema = 'public' AND table_name = 'authorization_decision'
                     """);
             ResultSet result = statement.executeQuery()) {
            Set<String> columns = new HashSet<>();
            while (result.next()) {
                columns.add(result.getString(1));
            }
            assertEquals(EXPECTED_COLUMNS, columns, "no request body or patient field has a column");
        }
    }

    @Test
    void twoTenantsWithIdenticalSubjectsAreIsolated() throws Exception {
        record(ORG_A, decision(ORG_A, true, "ALLOWED", "createFacility", "corr-tenant-a"));
        long aBefore = count("aurevia_app", ORG_A);
        long bBefore = count("aurevia_app", ORG_B);

        record(ORG_B, decision(ORG_B, false, "COMMAND_FORBIDDEN", "createFacility", "corr-tenant-b"));

        assertEquals(aBefore, count("aurevia_app", ORG_A), "tenant B's row is invisible to tenant A");
        assertEquals(bBefore + 1, count("aurevia_app", ORG_B));
        assertFalse(executes("aurevia_migrate", ORG_A,
                "INSERT INTO public.authorization_decision (organization_id, action, resource_type, "
                        + "decision, reason_code) VALUES ('" + ORG_B + "', 'FACILITY_WRITE', "
                        + "'createFacility', 'allow', 'ALLOWED')"),
                "WITH CHECK rejects an insert into a different tenant");
    }

    @Test
    void ledgerRejectsMutationForTheApplicationAndTheMigrationOwner() throws Exception {
        record(ORG_A, decision(ORG_A, true, "ALLOWED", "createFacility", "corr-immutable"));

        for (String role : List.of("aurevia_app", "aurevia_migrate")) {
            assertFalse(executes(role, ORG_A,
                    "UPDATE public.authorization_decision SET reason_code = 'ALLOWED'"),
                    role + " cannot rewrite a decision");
            assertFalse(executes(role, ORG_A, "DELETE FROM public.authorization_decision"),
                    role + " cannot delete a decision");
            assertFalse(executes(role, ORG_A, "TRUNCATE TABLE public.authorization_decision"),
                    role + " cannot truncate the ledger");
        }
        assertTrue(count("aurevia_app", ORG_A) > 0, "the seeded row survives every rejected mutation");
    }

    @Test
    void forcedRlsHidesRowsWithoutATenantContext() throws Exception {
        record(ORG_A, decision(ORG_A, true, "ALLOWED", "createFacility", "corr-rls"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                             + "WHERE oid = 'public.authorization_decision'::regclass")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
            assertTrue(result.getBoolean(2));
            ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM public.authorization_decision");
            assertTrue(rows.next());
            assertEquals(0L, rows.getLong(1), "no tenant context exposes no rows");
        }
    }

    @Test
    void reasonCodeCheckRejectsFreeText() throws Exception {
        assertFalse(executes("aurevia_migrate", ORG_A,
                "INSERT INTO public.authorization_decision (organization_id, action, resource_type, "
                        + "decision, reason_code) VALUES ('" + ORG_A + "', 'FACILITY_WRITE', "
                        + "'createFacility', 'allow', 'FREE TEXT PATIENT DETAIL')"),
                "only enumerated reason codes can enter the ledger");
    }

    @Test
    void aDecisionWithoutAnActiveTenantCannotBeRecorded() {
        TenantContext.set(new TenantContext("  ", "shared-user", "AUTH", "corr-ledger"));
        Decision blank = new Decision("createFacility", "FACILITY_WRITE", "  ", "shared-user",
                "corr-ledger", true, "ALLOWED", List.of("tenant_admin"), DECIDED_AT);

        assertThrows(DataAccessException.class,
                () -> transactions.executeWithoutResult(status -> sink.record(blank)));
    }

    @Test
    void malformedAndUnknownCommandDenialsAreRecordedWithoutChangingTheGateStatus() throws Exception {
        long before = count("aurevia_app", ORG_A);
        Decision invalid = new Decision("", "", ORG_A, "shared-user", "corr-invalid",
                false, "INVALID_COMMAND", List.of(), DECIDED_AT);
        Decision unknown = new Decision("notACommand", "", ORG_A, "shared-user", "corr-unknown",
                false, "UNKNOWN_COMMAND", List.of(), DECIDED_AT);
        record(ORG_A, invalid);
        record(ORG_A, unknown);

        assertEquals(before + 2, count("aurevia_app", ORG_A));
        try (Connection connection = open("aurevia_app", ORG_A);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT reason_code, action, resource_type FROM public.authorization_decision
                      WHERE correlation_id IN ('corr-invalid', 'corr-unknown') ORDER BY reason_code
                     """)) {
            assertTrue(result.next());
            assertEquals("INVALID_COMMAND", result.getString(1));
            assertNull(result.getString(2));
            assertNull(result.getString(3));
            assertTrue(result.next());
            assertEquals("UNKNOWN_COMMAND", result.getString(1));
            assertNull(result.getString(2));
            assertEquals("notACommand", result.getString(3));
        }

        // The status belongs to the gate, not to the ledger: an unknown command is
        // still 422 even though its bounded token is now durable audit evidence.
        CommandAuthorization gate = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                identity -> new MemberContext(List.of("tenant_admin"), true),
                new InMemoryDecisionSink(), Clock.systemUTC());
        ApiException denial = assertThrows(ApiException.class, () -> gate.authorize(
                new CommandRequest("notACommand", null, null),
                new ServerIdentity(ORG_A, "shared-user", "corr-unknown")));
        assertEquals(422, denial.status());
    }

    @Test
    void everyCatalogCommandCanBeRecorded() throws Exception {
        long before = count("aurevia_app", ORG_A);
        var commands = CommandCatalog.synthetic().commands();
        for (var definition : commands) {
            Decision allowed = new Decision(definition.id(), definition.action(), ORG_A,
                    "shared-user", "corr-catalog", true, "ALLOWED", List.of("tenant_admin"), DECIDED_AT);
            record(ORG_A, allowed);
        }
        assertEquals(before + commands.size(), count("aurevia_app", ORG_A),
                "bounded command tokens are accepted for every catalog entry");
    }
}
