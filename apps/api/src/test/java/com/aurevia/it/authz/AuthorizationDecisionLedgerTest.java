package com.aurevia.it.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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

import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.CommandAuthorization.DecisionSink;
import com.aurevia.api.authorization.JdbcDecisionSink;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02 acceptance for the durable authorization decision ledger (V65).
 *
 * <p>The production Spring bean is exercised inside the tenant-guarded transaction
 * and its rows are read back through a second connection. The HTTP caller is owned by
 * AUTH-WIRE-01, so this suite proves the durable sink boundary, not a new route.
 * All identifiers and codes are synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_authz_ledger_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class AuthorizationDecisionLedgerTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_authz_ledger_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final String SUBJECT = "svc-a";
    static final String PURPOSE = "AUTHORIZATION";
    static final Instant AT = Instant.parse("2026-11-25T09:00:00Z");

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "id", "organization_id", "subject", "action", "resource_type", "decision",
            "reason_code", "correlation_id", "decided_at");

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

    private record LedgerRow(String subject, String action, String resourceType, String decision,
                             String reasonCode, String correlationId, String decidedAt) {
    }

    private static Decision decision(String commandId, boolean allowed, String reason, String correlation) {
        return new Decision(commandId, "FACILITY_WRITE", ORG_A, SUBJECT, correlation, allowed,
                reason, List.of("tenant_admin"), AT);
    }

    private void record(String organization, Decision decision) {
        TenantContext.set(new TenantContext(organization, SUBJECT, PURPOSE, decision.correlationId()));
        try {
            transactions.executeWithoutResult(status -> sink.record(decision));
        } finally {
            TenantContext.clear();
        }
    }

    private static Connection bound(String organization) throws Exception {
        Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, SUBJECT);
            statement.execute();
        }
        return connection;
    }

    private static List<LedgerRow> rows(String organization) throws Exception {
        List<LedgerRow> found = new ArrayList<>();
        try (Connection connection = bound(organization);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT subject, action, resource_type, decision, reason_code, correlation_id, "
                             + "decided_at::text FROM public.authorization_decision")) {
            while (result.next()) {
                found.add(new LedgerRow(result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6), result.getString(7)));
            }
        }
        return found;
    }

    private static LedgerRow withCorrelation(List<LedgerRow> rows, String correlation) {
        return rows.stream().filter(row -> correlation.equals(row.correlationId())).findFirst().orElseThrow();
    }

    private static boolean executes(String organization, String sql) throws Exception {
        try (Connection connection = bound(organization);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.commit();
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String insert(String organization, String subject, String action,
                                 String resourceType, String decision, String reason, String correlation) {
        return "INSERT INTO public.authorization_decision "
                + "(organization_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                + "VALUES (" + literal(organization) + ", " + literal(subject) + ", " + literal(action)
                + ", " + literal(resourceType) + ", " + literal(decision) + ", " + literal(reason)
                + ", " + literal(correlation) + ")";
    }

    private static String insertWithId(String id, String organization, String subject, String action,
                                       String resourceType, String decision, String reason, String correlation) {
        return "INSERT INTO public.authorization_decision "
                + "(id, organization_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                + "VALUES (" + literal(id) + ", " + literal(organization) + ", " + literal(subject)
                + ", " + literal(action) + ", " + literal(resourceType) + ", " + literal(decision)
                + ", " + literal(reason) + ", " + literal(correlation) + ")";
    }

    private static Set<String> querySet(String sql) throws Exception {
        Set<String> values = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static boolean tenantTableHasForcedRls() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                             + "WHERE oid = 'public.authorization_decision'::regclass")) {
            assertTrue(result.next());
            return result.getBoolean(1) && result.getBoolean(2);
        }
    }

    @Test
    void productionBeanIsTheDurableSink() {
        assertInstanceOf(JdbcDecisionSink.class, sink);
    }

    @Test
    void allowAndDenyDecisionsArePersistedWithOnlyStructuredColumns() throws Exception {
        record(ORG_A, decision("createFacility", true, "ALLOWED", "corr-allow"));
        record(ORG_A, decision("createFacility", false, "COMMAND_FORBIDDEN", "corr-deny"));

        List<LedgerRow> found = rows(ORG_A);
        LedgerRow allow = withCorrelation(found, "corr-allow");
        LedgerRow deny = withCorrelation(found, "corr-deny");
        assertEquals(SUBJECT, allow.subject());
        assertEquals("FACILITY_WRITE", allow.action());
        assertEquals("createFacility", allow.resourceType());
        assertEquals("allow", allow.decision());
        assertEquals("ALLOWED", allow.reasonCode());
        assertNotNull(allow.decidedAt());
        assertEquals("deny", deny.decision());
        assertEquals("COMMAND_FORBIDDEN", deny.reasonCode());

        assertEquals(EXPECTED_COLUMNS, querySet("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'authorization_decision'"));
    }

    @Test
    void boundedCommandIdentifierMatchesTheSqlCheckLength() throws Exception {
        String oversized = "a".repeat(300) + "\nFORGED";
        record(ORG_A, decision(oversized, true, "ALLOWED", "corr-bound"));

        assertEquals("a".repeat(128), withCorrelation(rows(ORG_A), "corr-bound").resourceType());
        assertFalse(executes(ORG_A, "INSERT INTO public.authorization_decision "
                + "(organization_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                + "VALUES ('" + ORG_A + "', 'svc-a', 'FACILITY_WRITE', '" + "b".repeat(129)
                + "', 'allow', 'ALLOWED', 'corr-too-long')"));
    }

    @Test
    void codeColumnsEnforceDeclaredLengths() throws Exception {
        assertTrue(executes(ORG_A, insert(ORG_A, "s".repeat(256), "a".repeat(128), "createFacility",
                "allow", "r".repeat(64), "c".repeat(128))));
        assertFalse(executes(ORG_A, insert(ORG_A, "s".repeat(257), "a".repeat(128), "createFacility",
                "allow", "ALLOWED", "corr-subject-too-long")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", "a".repeat(129), "createFacility",
                "allow", "ALLOWED", "corr-action-too-long")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "r".repeat(65), "corr-reason-too-long")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "c".repeat(129))));
    }

    @Test
    void crossTenantInsertIsRejectedByWithCheck() throws Exception {
        assertFalse(executes(ORG_A, insert(ORG_B, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "corr-cross")));

        assertTrue(rows(ORG_B).stream().noneMatch(row -> "corr-cross".equals(row.correlationId())));
    }

    @Test
    void ledgerRowConstraintsAreDatabaseEnforced() throws Exception {
        assertTrue(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "corr-default-id-1")));
        assertTrue(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "corr-default-id-2")));
        assertFalse(executes(ORG_A, insert(null, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "corr-null-org")));
        assertFalse(executes(ORG_A, insert(ORG_A, null, "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "corr-null-subject")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", null, "createFacility",
                "allow", "ALLOWED", "corr-null-action")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", null,
                "allow", "ALLOWED", "corr-null-resource")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                null, "ALLOWED", "corr-null-decision")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", null, "corr-null-reason")));
        assertFalse(executes(ORG_A, insert(ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", null)));
        assertFalse(executes(ORG_A, insert("33333333-3333-3333-3333-333333333333", "svc-a",
                "FACILITY_WRITE", "createFacility", "allow", "ALLOWED", "corr-unknown-org")));
        String id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        assertTrue(executes(ORG_A, insertWithId(id, ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "corr-pk-1")));
        assertFalse(executes(ORG_A, insertWithId(id, ORG_A, "svc-a", "FACILITY_WRITE", "createFacility",
                "allow", "ALLOWED", "corr-pk-2")));
    }

    @Test
    void gateDecisionReachesTheDurableLedger() throws Exception {
        CommandAuthorization authorization = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                identity -> new MemberContext(List.of("tenant_admin"), true), sink, Clock.fixed(AT,
                        java.time.ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG_A, SUBJECT, PURPOSE, "corr-gate"));
        try {
            transactions.executeWithoutResult(status -> authorization.authorize(
                    new CommandRequest("createFacility", "reception", List.of("*")),
                    new CommandAuthorization.ServerIdentity(ORG_A, SUBJECT, "corr-gate")));
        } finally {
            TenantContext.clear();
        }

        LedgerRow row = withCorrelation(rows(ORG_A), "corr-gate");
        assertEquals("allow", row.decision());
        assertEquals("ALLOWED", row.reasonCode());
        assertEquals("FACILITY_WRITE", row.action());
    }

    @Test
    void rolledBackDecisionLeavesNoRow() throws Exception {
        TenantContext.set(new TenantContext(ORG_A, SUBJECT, PURPOSE, "corr-rollback"));
        try {
            transactions.executeWithoutResult(status -> {
                sink.record(decision("createFacility", true, "ALLOWED", "corr-rollback"));
                status.setRollbackOnly();
            });
        } finally {
            TenantContext.clear();
        }

        assertTrue(rows(ORG_A).stream().noneMatch(row -> "corr-rollback".equals(row.correlationId())));
    }

    @Test
    void ledgerIsAppendOnlyRestrictedAndTenantIsolated() throws Exception {
        record(ORG_A, decision("createFacility", true, "ALLOWED", "corr-policy"));

        assertTrue(tenantTableHasForcedRls());
        assertTrue(rows(ORG_B).isEmpty(), "another tenant sees no decision rows");
        assertEquals(Set.of("SELECT", "INSERT"), querySet("SELECT privilege_type "
                + "FROM information_schema.role_table_grants WHERE grantee = 'aurevia_app' "
                + "AND table_schema = 'public' AND table_name = 'authorization_decision'"));
        assertFalse(executes(ORG_A, "UPDATE public.authorization_decision "
                + "SET reason_code = 'CHANGED' WHERE correlation_id = 'corr-policy'"));
        assertFalse(executes(ORG_A, "DELETE FROM public.authorization_decision "
                + "WHERE correlation_id = 'corr-policy'"));
        assertFalse(executes(ORG_A, "TRUNCATE TABLE public.authorization_decision"));
        assertFalse(executes(ORG_A, "INSERT INTO public.authorization_decision "
                + "(organization_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                + "VALUES ('" + ORG_A + "', 'svc-a', 'FACILITY_WRITE', 'createFacility', 'maybe', "
                + "'ALLOWED', 'corr-invalid')"));
        assertEquals(1, rows(ORG_A).stream().filter(row -> "corr-policy".equals(row.correlationId())).count());
    }
}
