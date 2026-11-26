package com.aurevia.it.authz;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.JdbcDecisionSink;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02/ARC-04 acceptance for the durable authorization decision ledger.
 *
 * <p>The real Spring bean writes to real PostgreSQL with every migration
 * applied, so forced RLS, the coded-field constraints, the append-only triggers
 * and the tenant-guarded transaction are exercised rather than mocked. The
 * command gate is built with the injected bean to prove gate to ledger reachability;
 * the HTTP gate itself is owned by the dependent AUTH-WIRE-01 story. All
 * identifiers, subjects and reasons are synthetic; no patient field is used.</p>
 */
@Tag("integration")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_authorization_ledger_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class AuthorizationDecisionLedgerTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_authorization_ledger_it";
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T06:00:00Z"), ZoneOffset.UTC);
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private static final Set<String> COLUMNS = Set.of("organization_id", "id", "subject", "action",
            "resource_type", "decision", "reason_code", "correlation_id", "decided_at");

    @Autowired
    CommandAuthorization.DecisionSink sink;

    @Autowired
    ApplicationContext applicationContext;

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
            statement.execute("DROP DATABASE IF EXISTS aurevia_authorization_ledger_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_authorization_ledger_it OWNER aurevia_migrate");
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
                        + "VALUES ('" + org + "', 'Synthetic authorization tenant')");
            }
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_authorization_ledger_it WITH (FORCE)");
        }
    }

    @BeforeEach
    void clearBefore() {
        TenantContext.clear();
    }

    @AfterEach
    void clearAfter() {
        TenantContext.clear();
    }

    private static final class Resolver implements MembershipResolver {

        private final List<String> roles;

        Resolver(List<String> roles) {
            this.roles = roles;
        }

        @Override
        public MemberContext resolve(ServerIdentity identity) {
            return new MemberContext(roles, true);
        }
    }

    private static void bind(String organization, String subject) {
        TenantContext.set(new TenantContext(organization, subject, "FACILITY_ADMIN", "corr-test"));
    }

    private static void bind(Connection connection, String organization, String subject) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /** Count only the allowed facility-write decision recorded without a correlation id. */
    private static long countAllowWithMissingCorrelation() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM public.authorization_decision WHERE correlation_id = '' "
                             + "AND action = 'FACILITY_WRITE' AND reason_code = 'ALLOWED' "
                             + "AND decision = 'allow'")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static long count(String role, String organization, String correlation) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, role, "")) {
            connection.setAutoCommit(false);
            if (organization != null) {
                bind(connection, organization, "admin-a");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public.authorization_decision WHERE correlation_id = ?")) {
                statement.setString(1, correlation);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getLong(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertLedgerDecision(String correlation, String action, String reason, String outcome)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT action, resource_type, reason_code, decision FROM public.authorization_decision "
                             + "WHERE organization_id = CAST(? AS uuid) AND correlation_id = ? AND action = ?")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            statement.setString(1, ORG_A);
            statement.setString(2, correlation);
            statement.setString(3, action);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(action, result.getString("action"));
                assertEquals("COMMAND", result.getString("resource_type"));
                assertEquals(reason, result.getString("reason_code"));
                assertEquals(outcome, result.getString("decision"));
            }
        }
    }

    private static boolean executes(String role, String organization, String sql) {
        try (Connection connection = DriverManager.getConnection(URL, role, "")) {
            connection.setAutoCommit(false);
            if (organization != null) {
                bind(connection, organization, "admin-a");
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            connection.commit();
            return true;
        } catch (SQLException failure) {
            return false;
        }
    }

    private static boolean hasPrivilege(String privilege) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT has_table_privilege('aurevia_app', 'public.authorization_decision', ?)")) {
            statement.setString(1, privilege);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBoolean(1);
            }
        }
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String insertDecision(String organization, String subject, String action,
                                         String resourceType, String outcome, String reason,
                                         String correlation) {
        return "INSERT INTO public.authorization_decision "
                + "(organization_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                + "VALUES (" + literal(organization) + ", " + literal(subject) + ", " + literal(action)
                + ", " + literal(resourceType) + ", " + literal(outcome) + ", " + literal(reason)
                + ", " + literal(correlation) + ")";
    }

    private static String insertDecisionWithId(String id, String organization, String correlation) {
        return "INSERT INTO public.authorization_decision "
                + "(id, organization_id, decision, reason_code, correlation_id) "
                + "VALUES (" + literal(id) + ", " + literal(organization) + ", 'allow', 'ALLOWED', "
                + literal(correlation) + ")";
    }

    @Test
    void codeColumnsEnforceDeclaredLengths() throws Exception {
        assertTrue(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "s".repeat(256),
                "a".repeat(128), "COMMAND", "allow", "ALLOWED", "c".repeat(128))));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "s".repeat(257),
                "a".repeat(128), "COMMAND", "allow", "ALLOWED", "corr-subject-too-long")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "svc-a", "a".repeat(129),
                "COMMAND", "allow", "ALLOWED", "corr-action-too-long")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "svc-a", "FACILITY_WRITE",
                "COMMAND", "allow", "ALLOWED", "c".repeat(129))));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-subject-too-long"));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-action-too-long"));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "c".repeat(129)));
    }

    @Test
    void ledgerRowIntegrityAndDefaultsAreDatabaseEnforced() throws Exception {
        assertTrue(executes("aurevia_migrate", ORG_A,
                "INSERT INTO public.authorization_decision "
                        + "(organization_id, decision, reason_code, correlation_id) "
                        + "VALUES ('" + ORG_A + "', 'allow', 'ALLOWED', 'corr-defaults')"));
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, subject, action, resource_type, decided_at "
                            + "FROM public.authorization_decision WHERE correlation_id = 'corr-defaults'")) {
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertNotNull(result.getObject("id"));
                    assertEquals("", result.getString("subject"));
                    assertEquals("", result.getString("action"));
                    assertEquals("COMMAND", result.getString("resource_type"));
                    assertNotNull(result.getObject("decided_at"));
                }
            } finally {
                connection.rollback();
            }
        }

        Instant beforeDefault = Instant.now();
        assertTrue(executes("aurevia_migrate", ORG_A,
                "INSERT INTO public.authorization_decision "
                        + "(organization_id, action, decision, reason_code) "
                        + "VALUES ('" + ORG_A + "', 'DEFAULT_TIME_PROBE', 'allow', 'ALLOWED')"));
        Instant afterDefault = Instant.now();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT correlation_id, decided_at FROM public.authorization_decision "
                            + "WHERE action = 'DEFAULT_TIME_PROBE'")) {
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("", result.getString("correlation_id"));
                    OffsetDateTime decidedAt = result.getObject("decided_at", OffsetDateTime.class);
                    assertFalse(decidedAt.toInstant().isBefore(beforeDefault));
                    assertFalse(decidedAt.toInstant().isAfter(afterDefault));
                }
            } finally {
                connection.rollback();
            }
        }

        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(null, "svc-a", "FACILITY_WRITE",
                "COMMAND", "allow", "ALLOWED", "corr-null-org")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, null, "FACILITY_WRITE",
                "COMMAND", "allow", "ALLOWED", "corr-null-subject")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "svc-a", null,
                "COMMAND", "allow", "ALLOWED", "corr-null-action")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "svc-a", "FACILITY_WRITE",
                null, "allow", "ALLOWED", "corr-null-resource")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "svc-a", "FACILITY_WRITE",
                "COMMAND", null, "ALLOWED", "corr-null-decision")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "svc-a", "FACILITY_WRITE",
                "COMMAND", "allow", null, "corr-null-reason")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(ORG_A, "svc-a", "FACILITY_WRITE",
                "COMMAND", "allow", "ALLOWED", null)));
        assertFalse(executes("aurevia_migrate", ORG_A,
                "INSERT INTO public.authorization_decision "
                        + "(id, organization_id, decision, reason_code) "
                        + "VALUES (NULL, '" + ORG_A + "', 'allow', 'ALLOWED')"));
        assertFalse(executes("aurevia_migrate", ORG_A,
                "INSERT INTO public.authorization_decision "
                        + "(organization_id, decision, reason_code, decided_at) "
                        + "VALUES ('" + ORG_A + "', 'allow', 'ALLOWED', NULL)"));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecision(
                "33333333-3333-3333-3333-333333333333", "svc-a", "FACILITY_WRITE",
                "COMMAND", "allow", "ALLOWED", "corr-unknown-org")));

        String id = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        assertTrue(executes("aurevia_migrate", ORG_A, insertDecisionWithId(id, ORG_A, "corr-pk-a")));
        assertTrue(executes("aurevia_migrate", ORG_B, insertDecisionWithId(id, ORG_B, "corr-pk-b")));
        assertFalse(executes("aurevia_migrate", ORG_A, insertDecisionWithId(id, ORG_A, "corr-pk-duplicate")));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-pk-duplicate"));
    }

    @Test
    void productionBeanRecordsAllowAndDenyWithTheInjectedClock() throws Exception {
        assertEquals(1, applicationContext.getBeansOfType(CommandAuthorization.DecisionSink.class).size(),
                "production must expose exactly one durable decision sink");
        assertTrue(applicationContext.getBean(CommandAuthorization.DecisionSink.class)
                instanceof JdbcDecisionSink, "the production bean must be the durable sink");
        CommandCatalog catalog = CommandCatalog.synthetic();

        bind(ORG_A, "admin-a");
        CommandAuthorization allowedGate = CommandAuthorization.synthetic(
                catalog, new Resolver(List.of("tenant_admin")), sink, CLOCK);
        Decision allow = allowedGate.authorize(new CommandRequest("createFacility", null, null),
                new ServerIdentity(ORG_A, "admin-a", "corr-allow"));
        assertEquals("ALLOWED", allow.reasonCode());

        CommandAuthorization deniedGate = CommandAuthorization.synthetic(
                catalog, new Resolver(List.of("reception")), sink, CLOCK);
        ApiException denial = assertThrows(ApiException.class, () -> deniedGate.authorize(
                new CommandRequest("createFacility", null, null),
                new ServerIdentity(ORG_A, "reception-a", "corr-deny")));
        assertEquals(403, denial.status());

        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-allow"));
        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-deny"));
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT decision, reason_code, subject, action, resource_type, correlation_id, decided_at "
                            + "FROM public.authorization_decision WHERE correlation_id = 'corr-allow'")) {
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("allow", result.getString("decision"));
                    assertEquals("ALLOWED", result.getString("reason_code"));
                    assertEquals("admin-a", result.getString("subject"));
                    assertEquals("FACILITY_WRITE", result.getString("action"));
                    assertEquals("COMMAND", result.getString("resource_type"));
                    assertEquals(CLOCK.instant(),
                            result.getObject("decided_at", OffsetDateTime.class).toInstant());
                }
            } finally {
                connection.rollback();
            }
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT decision, reason_code FROM public.authorization_decision "
                                 + "WHERE correlation_id = 'corr-deny'")) {
                assertTrue(result.next());
                assertEquals("deny", result.getString("decision"));
                assertEquals("COMMAND_FORBIDDEN", result.getString("reason_code"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void denySurvivesRollbackOfTheRejectedCommandTransaction() throws Exception {
        bind(ORG_A, "admin-a");
        CommandAuthorization deniedGate = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                new Resolver(List.of("reception")), sink, CLOCK);
        assertThrows(ApiException.class, () -> transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            deniedGate.authorize(new CommandRequest("createFacility", null, null),
                    new ServerIdentity(ORG_A, "reception-a", "corr-rollback"));
        }));
        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-rollback"));
    }

    @Test
    void decisionsAreTenantIsolatedAndCrossTenantWritesFailClosed() throws Exception {
        bind(ORG_A, "admin-a");
        sink.record(new Decision("createFacility", "FACILITY_WRITE", ORG_A, "admin-a", "corr-tenant",
                true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()));
        assertEquals(1L, count("aurevia_app", ORG_A, "corr-tenant"));
        assertEquals(0L, count("aurevia_app", ORG_B, "corr-tenant"));
        assertEquals(0L, count("aurevia_app", null, "corr-tenant"));
        assertEquals(0L, count("aurevia_migrate", null, "corr-tenant"));

        assertFalse(executes("aurevia_app", ORG_A, "INSERT INTO public.authorization_decision "
                + "(organization_id, decision, reason_code) VALUES ('" + ORG_B + "', 'allow', 'ALLOWED')"));
        assertFalse(executes("aurevia_migrate", ORG_B, "INSERT INTO public.authorization_decision "
                + "(organization_id, decision, reason_code) VALUES ('" + ORG_A + "', 'allow', 'ALLOWED')"));

        TenantContext.clear();
        ApiException failure = assertThrows(ApiException.class, () -> sink.record(
                new Decision("createFacility", "FACILITY_WRITE", ORG_A, "admin-a", "corr-no-context",
                        true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant())));
        assertEquals(403, failure.status());
        assertEquals("AUDIT_UNAVAILABLE", failure.code());
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-no-context"));
    }

    @Test
    void gatePersistsBoundedCodesAndMissingCorrelationThroughProductionSink() throws Exception {
        bind(ORG_A, "admin-a");
        CommandAuthorization allowing = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                new Resolver(List.of("tenant_admin")), sink, CLOCK);
        String untrusted = "SECRET_MARKER\n" + "a".repeat(256);
        ApiException unknown = assertThrows(ApiException.class, () -> allowing.authorize(
                new CommandRequest(untrusted, null, null),
                new ServerIdentity(ORG_A, "admin-a", "corr-unknown-gate")));
        assertEquals(422, unknown.status());
        assertEquals("UNKNOWN_COMMAND", unknown.code());
        ApiException malformed = assertThrows(ApiException.class, () -> allowing.authorize(
                new CommandRequest("   ", null, null),
                new ServerIdentity(ORG_A, "admin-a", "corr-malformed-gate")));
        assertEquals(422, malformed.status());
        assertEquals("INVALID_COMMAND", malformed.code());
        Decision allowed = allowing.authorize(new CommandRequest("createFacility", null, null),
                new ServerIdentity(ORG_A, "admin-a", null));
        assertTrue(allowed.allowed());

        bind(ORG_A, "reception-a");
        CommandAuthorization denying = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                new Resolver(List.of("reception")), sink, CLOCK);
        ApiException denied = assertThrows(ApiException.class, () -> denying.authorize(
                new CommandRequest("createFacility", null, null),
                new ServerIdentity(ORG_A, "reception-a", "corr-deny-gate")));
        assertEquals(403, denied.status());

        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-unknown-gate"));
        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-malformed-gate"));
        // Other acceptance cases commit their own missing-correlation rows, so scope
        // this count to the allowed command made above rather than every empty key.
        assertEquals(1L, countAllowWithMissingCorrelation());
        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-deny-gate"));
        assertLedgerDecision("corr-unknown-gate", "", "UNKNOWN_COMMAND", "deny");
        assertLedgerDecision("corr-malformed-gate", "", "INVALID_COMMAND", "deny");
        assertLedgerDecision("", "FACILITY_WRITE", "ALLOWED", "allow");
        assertLedgerDecision("corr-deny-gate", "FACILITY_WRITE", "COMMAND_FORBIDDEN", "deny");

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM public.authorization_decision "
                             + "WHERE concat_ws(' ', subject, action, resource_type, reason_code, correlation_id) "
                             + "LIKE '%SECRET_MARKER%'")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0L, result.getLong(1), "client-controlled text never reaches the ledger");
            }
        }
    }

    @Test
    void unauthenticatedDecisionsKeep401AndPersistOnlyWithVerifiedTenantContext() throws Exception {
        TenantContext.clear();
        CommandAuthorization gate = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                new Resolver(List.of("tenant_admin")), sink, CLOCK);
        ApiException withoutContext = assertThrows(ApiException.class, () -> gate.authorize(
                new CommandRequest("createFacility", null, null),
                new ServerIdentity(ORG_A, null, "corr-unauthenticated-no-context")));
        assertEquals(401, withoutContext.status());
        assertEquals("UNAUTHENTICATED", withoutContext.code());
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-unauthenticated-no-context"));

        bind(ORG_A, "admin-a");
        ApiException withContext = assertThrows(ApiException.class, () -> gate.authorize(
                new CommandRequest("createFacility", null, null),
                new ServerIdentity(ORG_A, null, "corr-unauthenticated-with-context")));
        assertEquals(401, withContext.status());
        assertEquals("UNAUTHENTICATED", withContext.code());
        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-unauthenticated-with-context"));
        assertLedgerDecision("corr-unauthenticated-with-context", "", "UNAUTHENTICATED", "deny");
    }

    @Test
    void productionSinkSanitizesUntrustedEvidence() throws Exception {
        bind(ORG_A, "admin-a");
        String correlation = "sanitized-" + "c".repeat(118);
        sink.record(new Decision("ignored", "A".repeat(140) + "\nFORGED", ORG_A,
                "s".repeat(260) + "\nFORGED", correlation + "\rFORGED",
                true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT subject, action, correlation_id FROM public.authorization_decision "
                             + "WHERE organization_id = CAST(? AS uuid) AND correlation_id = ?")) {
            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            statement.setString(1, ORG_A);
            statement.setString(2, correlation);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("s".repeat(255), result.getString("subject"));
                assertEquals("A".repeat(128), result.getString("action"));
                assertEquals(correlation, result.getString("correlation_id"));
            }
        }
    }

    @Test
    void contradictoryUnauthenticatedDecisionNeverBypassesValidation() throws Exception {
        TenantContext.clear();
        Decision contradictory = new Decision("createFacility", "FACILITY_WRITE", ORG_A, "admin-a",
                "corr-contradictory-unauthenticated", true, "UNAUTHENTICATED", List.of(), CLOCK.instant());

        ApiException failure = assertThrows(ApiException.class, () -> sink.record(contradictory));

        assertEquals(403, failure.status());
        assertEquals("AUDIT_UNAVAILABLE", failure.code());
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-contradictory-unauthenticated"));
        assertEquals(0L, count("aurevia_migrate", null, "corr-contradictory-unauthenticated"));
    }

    @Test
    void crossTenantDecisionEvidenceNeverChangesTheWriteTenant() throws Exception {
        bind(ORG_A, "admin-a");
        Decision crossTenant = new Decision("createFacility", "FACILITY_WRITE", ORG_B, "admin-a",
                "corr-cross-tenant-evidence", false, "COMMAND_FORBIDDEN", List.of(), CLOCK.instant());

        ApiException failure = assertThrows(ApiException.class, () -> sink.record(crossTenant));

        assertEquals(403, failure.status());
        assertEquals("AUDIT_UNAVAILABLE", failure.code());
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-cross-tenant-evidence"));
        assertEquals(0L, count("aurevia_migrate", ORG_B, "corr-cross-tenant-evidence"));
    }

    @Test
    void ledgerIsAppendOnlyForRuntimeAndOwnerAndStoresOnlyCodedColumns() throws Exception {
        bind(ORG_A, "admin-a");
        sink.record(new Decision("createFacility", "FACILITY_WRITE", ORG_A, "admin-a", "corr-immutable",
                true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()));
        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-immutable"));

        for (String role : List.of("aurevia_app", "aurevia_migrate")) {
            assertFalse(executes(role, ORG_A, "UPDATE public.authorization_decision "
                    + "SET decision = 'deny' WHERE correlation_id = 'corr-immutable'"));
            assertFalse(executes(role, ORG_A, "DELETE FROM public.authorization_decision "
                    + "WHERE correlation_id = 'corr-immutable'"));
            assertFalse(executes(role, ORG_A, "TRUNCATE public.authorization_decision"));
        }
        assertTrue(hasPrivilege("SELECT"));
        assertTrue(hasPrivilege("INSERT"));
        assertFalse(hasPrivilege("UPDATE"));
        assertFalse(hasPrivilege("DELETE"));
        assertFalse(hasPrivilege("TRUNCATE"));

        assertFalse(executes("aurevia_migrate", ORG_A, "INSERT INTO public.authorization_decision "
                + "(organization_id, decision, reason_code) VALUES ('" + ORG_A + "', 'maybe', 'ALLOWED')"));
        assertFalse(executes("aurevia_migrate", ORG_A, "INSERT INTO public.authorization_decision "
                + "(organization_id, decision, reason_code) VALUES ('" + ORG_A + "', 'allow', 'FREE_TEXT')"));
        assertFalse(executes("aurevia_migrate", ORG_A, "INSERT INTO public.authorization_decision "
                + "(organization_id, resource_type, decision, reason_code) "
                + "VALUES ('" + ORG_A + "', 'CLIENT_COMMAND', 'allow', 'ALLOWED')"));
        assertEquals(1L, count("aurevia_migrate", ORG_A, "corr-immutable"));

        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT relrowsecurity, relforcerowsecurity "
                     + "FROM pg_class WHERE oid = 'public.authorization_decision'::regclass")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
            assertTrue(result.getBoolean(2));
        }
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT column_name FROM information_schema.columns "
                     + "WHERE table_schema = 'public' AND table_name = 'authorization_decision'")) {
            Set<String> actual = new HashSet<>();
            while (result.next()) {
                actual.add(result.getString(1));
            }
            assertEquals(COLUMNS, actual, "no patient, request-body or role-list column exists");
        }
    }

    @Test
    void codedFieldLengthLimitsAreEnforcedAtBoundaries() throws Exception {
        bind(ORG_A, "admin-a");
        String subject = "s".repeat(256);
        String action = "a".repeat(128);
        String resourceType = "COMMAND";
        String correlationId = "c".repeat(128);
        String insert = """
                INSERT INTO public.authorization_decision
                    (organization_id, subject, action, resource_type, decision, reason_code, correlation_id)
                VALUES ('%s', '%s', '%s', '%s', 'allow', 'ALLOWED', '%s')
                """;

        assertTrue(executes("aurevia_app", ORG_A,
                insert.formatted(ORG_A, subject, action, resourceType, correlationId)));
        assertEquals(1L, count("aurevia_migrate", ORG_A, correlationId));

        assertFalse(executes("aurevia_app", ORG_A,
                insert.formatted(ORG_A, "s".repeat(257), action, resourceType, "corr-subject-overflow")));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-subject-overflow"));
        assertFalse(executes("aurevia_app", ORG_A,
                insert.formatted(ORG_A, subject, "a".repeat(129), resourceType, "corr-action-overflow")));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-action-overflow"));
        // The discriminator is fixed by policy, not merely bounded: a client-supplied
        // command name must never become a resource category in the ledger.
        assertFalse(executes("aurevia_app", ORG_A,
                insert.formatted(ORG_A, subject, action, "CLIENT_COMMAND", "corr-resource-overflow")));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-resource-overflow"));
        String correlationOverflow = "c".repeat(129);
        assertFalse(executes("aurevia_app", ORG_A,
                insert.formatted(ORG_A, subject, action, resourceType, correlationOverflow)));
        assertEquals(0L, count("aurevia_migrate", ORG_A, correlationOverflow));

        // The production sink bounds untrusted fields before insertion; the
        // database checks above remain the backstop for direct SQL callers.
        List<Decision> oversizedDecisions = List.of(
                new Decision("createFacility", action, ORG_A, "s".repeat(257),
                        "corr-sink-subject-overflow", true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()),
                new Decision("createFacility", "a".repeat(129), ORG_A, subject,
                        "corr-sink-action-overflow", true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()),
                new Decision("createFacility", action, ORG_A, subject,
                        correlationOverflow, true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()));
        for (Decision oversized : oversizedDecisions) {
            String storedCorrelation = oversized.correlationId().substring(
                    0, Math.min(128, oversized.correlationId().length()));
            long before = count("aurevia_migrate", ORG_A, storedCorrelation);

            sink.record(oversized);

            assertEquals(before + 1, count("aurevia_migrate", ORG_A, storedCorrelation));
            try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "")) {
                connection.setAutoCommit(false);
                bind(connection, ORG_A, "admin-a");
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT subject, action, correlation_id FROM public.authorization_decision "
                                + "WHERE correlation_id = ? AND decided_at = CAST(? AS timestamptz)")) {
                    statement.setString(1, storedCorrelation);
                    statement.setObject(2, OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(oversized.subject().substring(0, 255), result.getString("subject"));
                        assertEquals(oversized.action().substring(0, 128), result.getString("action"));
                        assertEquals(storedCorrelation, result.getString("correlation_id"));
                        assertFalse(result.next());
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
    }
}
