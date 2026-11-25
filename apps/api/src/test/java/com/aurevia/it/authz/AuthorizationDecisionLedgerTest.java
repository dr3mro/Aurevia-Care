package com.aurevia.it.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void productionBeanRecordsAllowAndDenyWithTheInjectedClock() throws Exception {
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
        String resourceType = "r".repeat(128);
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
        assertFalse(executes("aurevia_app", ORG_A,
                insert.formatted(ORG_A, subject, action, "r".repeat(129), "corr-resource-overflow")));
        assertEquals(0L, count("aurevia_migrate", ORG_A, "corr-resource-overflow"));
        String correlationOverflow = "c".repeat(129);
        assertFalse(executes("aurevia_app", ORG_A,
                insert.formatted(ORG_A, subject, action, resourceType, correlationOverflow)));
        assertEquals(0L, count("aurevia_migrate", ORG_A, correlationOverflow));

        List<Decision> invalidDecisions = List.of(
                new Decision("createFacility", action, ORG_A, "s".repeat(257),
                        "corr-sink-subject-overflow", true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()),
                new Decision("createFacility", "a".repeat(129), ORG_A, subject,
                        "corr-sink-action-overflow", true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()),
                new Decision("createFacility", action, ORG_A, subject,
                        correlationOverflow, true, "ALLOWED", List.of("tenant_admin"), CLOCK.instant()));
        for (Decision invalid : invalidDecisions) {
            ApiException failure = assertThrows(ApiException.class, () -> sink.record(invalid));
            assertEquals(403, failure.status());
            assertEquals("AUDIT_UNAVAILABLE", failure.code());
            assertEquals("authorization decision could not be recorded", failure.getMessage());
            assertEquals(0L, count("aurevia_migrate", ORG_A, invalid.correlationId()));
        }
    }
}
