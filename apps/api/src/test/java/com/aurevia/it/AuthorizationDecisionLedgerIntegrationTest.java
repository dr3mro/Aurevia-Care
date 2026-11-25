package com.aurevia.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.JdbcDecisionSink;
import com.aurevia.audit.AuditRepository;
import com.aurevia.tenant.TenantContext;

/**
 * OPS-02 acceptance: the production decision sink persists allow and deny rows
 * through the real authorization gate, isolated by tenant and append-only.
 *
 * <p>All identifiers, roles and subjects are synthetic. No HTTP entry point
 * owns this gate yet; the wiring story AUTH-WIRE-01 adds that boundary.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_authz_ledger_it")
@ActiveProfiles("synthetic-auth")
@Timeout(240)
class AuthorizationDecisionLedgerIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_authz_ledger_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T10:00:00Z"), ZoneOffset.UTC);

    private static final List<String> COLUMNS = List.of("action", "correlation_id", "decided_at", "decision",
            "id", "organization_id", "reason_code", "resource_type", "subject");

    @Autowired
    CommandAuthorization.DecisionSink decisionSink;

    @Autowired
    AuditRepository auditRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private CommandAuthorization authorization;

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
            seedMember(statement, ORG_A, "admin-a", "tenant_admin");
            seedMember(statement, ORG_A, "reception-a", "reception");
            seedMember(statement, ORG_B, "admin-b", "tenant_admin");
            seedMember(statement, ORG_B, "reception-b", "reception");
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
    void buildAuthorization() {
        assertInstanceOf(JdbcDecisionSink.class, decisionSink);
        authorization = CommandAuthorization.synthetic(CommandCatalog.synthetic(),
                identity -> new MemberContext(auditRepository.activeRoles(identity.subject()),
                        auditRepository.hasActiveMembership(identity.subject())), decisionSink, CLOCK);
    }

    private static void seedMember(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Synthetic " + org.substring(0, 4) + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        // The role grant trigger derives the grantor from the authenticated
        // actor, so bind the freshly seeded member before inserting the grant.
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private Object inTenant(String org, String subject, Supplier<Object> work) {
        TenantContext.set(new TenantContext(org, subject, "AUTHZ_LEDGER", "corr-ledger"));
        try {
            return new TransactionTemplate(transactionManager).execute(status -> work.get());
        } finally {
            TenantContext.clear();
        }
    }

    private Decision allowed(String org, String subject, String correlationId) {
        return (Decision) inTenant(org, subject, () -> authorization.authorize(
                new CommandRequest("createFacility", null, null),
                new ServerIdentity(org, subject, correlationId)));
    }

    private ApiException denied(String org, String subject, String correlationId) {
        return (ApiException) inTenant(org, subject, () -> {
            try {
                authorization.authorize(new CommandRequest("createFacility", null, null),
                        new ServerIdentity(org, subject, correlationId));
                return null;
            } catch (ApiException failure) {
                return failure;
            }
        });
    }

    private static List<String> rows(String org, String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                while (resultSet.next()) {
                    result.add(resultSet.getString(1));
                }
            }
        }
        return result;
    }

    private static boolean executes(String org, String sql) {
        return executesAs("aurevia_app", org, sql);
    }

    private static boolean executesAs(String user, String org, String sql) {
        try (Connection connection = DriverManager.getConnection(URL, user, "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute(sql);
            return true;
        } catch (SQLException failure) {
            return false;
        }
    }

    @Test
    void allowAndDenyDecisionsArePersistedThroughTheProductionBean() throws Exception {
        Decision allow = allowed(ORG_A, "admin-a", "corr-allow");
        ApiException forbid = denied(ORG_A, "reception-a", "corr-deny");

        assertTrue(allow.allowed());
        assertEquals(403, forbid.status());
        List<String> rows = rows(ORG_A, "SELECT decision || '|' || reason_code || '|' || subject || '|' || "
                + "correlation_id || '|' || resource_type || '|' || action || '|' || decided_at::text "
                + "FROM public.authorization_decision "
                + "WHERE correlation_id IN ('corr-allow', 'corr-deny') ORDER BY correlation_id");
        assertEquals(2, rows.size(), rows.toString());
        assertTrue(rows.get(0).startsWith("allow|ALLOWED|admin-a|corr-allow|createFacility|FACILITY_WRITE|"),
                rows.get(0));
        assertTrue(rows.get(1).startsWith("deny|COMMAND_FORBIDDEN|reception-a|corr-deny|createFacility|"
                + "FACILITY_WRITE|"), rows.get(1));
        assertTrue(rows.get(0).endsWith("2026-11-25 10:00:00+00"), rows.get(0));
        assertFalse(rows.get(0).contains("body"), "no request payload is stored");
    }

    @Test
    void theLedgerHasOnlyTheDeclaredCodeColumns() throws Exception {
        List<String> columns = rows(ORG_A, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'authorization_decision' ORDER BY column_name");
        assertEquals(COLUMNS, columns);
        assertFalse(columns.contains("patient_id"));
        assertFalse(columns.contains("payload"));
        assertFalse(columns.contains("request_body"));
    }

    @Test
    void decisionsAreIsolatedByTheirActiveTenant() throws Exception {
        allowed(ORG_A, "admin-a", "corr-tenant-a");
        allowed(ORG_B, "admin-b", "corr-tenant-b");

        List<String> inA = rows(ORG_A, "SELECT correlation_id FROM public.authorization_decision "
                + "ORDER BY correlation_id");
        List<String> inB = rows(ORG_B, "SELECT correlation_id FROM public.authorization_decision "
                + "ORDER BY correlation_id");
        assertTrue(inA.contains("corr-tenant-a"));
        assertFalse(inA.contains("corr-tenant-b"));
        assertTrue(inB.contains("corr-tenant-b"));
        assertFalse(inB.contains("corr-tenant-a"));
        for (String row : inA) {
            assertTrue(row.length() > 0);
        }
    }

    @Test
    void theLedgerIsForcedRlsAndAppendOnlyForEveryRole() throws Exception {
        allowed(ORG_A, "admin-a", "corr-immutable");
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT relrowsecurity, relforcerowsecurity "
                     + "FROM pg_class WHERE oid = 'public.authorization_decision'::regclass")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
            assertTrue(result.getBoolean(2));
        }
        assertFalse(executes(ORG_A, "UPDATE public.authorization_decision SET decision = 'deny' "
                + "WHERE correlation_id = 'corr-immutable'"));
        assertFalse(executes(ORG_A, "DELETE FROM public.authorization_decision "
                + "WHERE correlation_id = 'corr-immutable'"));

        // Grants alone cannot stop the migration owner; prove the row is
        // visible to it and that both triggers actually reject the mutation.
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM public.authorization_decision "
                    + "WHERE correlation_id = 'corr-immutable'")) {
                assertTrue(result.next());
                assertEquals(1L, result.getLong(1));
            }
        }
        assertFalse(executesAs("aurevia_migrate", ORG_A,
                "UPDATE public.authorization_decision SET decision = 'deny' "
                        + "WHERE correlation_id = 'corr-immutable'"));
        assertFalse(executesAs("aurevia_migrate", ORG_A,
                "DELETE FROM public.authorization_decision WHERE correlation_id = 'corr-immutable'"));
        assertFalse(executesAs("aurevia_migrate", ORG_A, "TRUNCATE TABLE public.authorization_decision"));
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SELECT has_table_privilege('aurevia_app', 'public.authorization_decision', 'INSERT')");
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1));
            }
            statement.execute("SELECT has_table_privilege('aurevia_app', 'public.authorization_decision', 'UPDATE')");
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1));
            }
            statement.execute("SELECT has_table_privilege('aurevia_app', 'public.authorization_decision', 'DELETE')");
            try (ResultSet result = statement.getResultSet()) {
                assertTrue(result.next());
                assertFalse(result.getBoolean(1));
            }
        }
    }

    @Test
    void aCrossTenantRowCannotBeInsertedUnderTheActiveTenant() throws Exception {
        assertFalse(executes(ORG_A, "INSERT INTO public.authorization_decision "
                + "(id, organization_id, subject, action, resource_type, decision, reason_code, correlation_id) "
                + "VALUES ('33333333-3333-3333-3333-333333333333', '" + ORG_B + "', 'admin-a', "
                + "'FACILITY_WRITE', 'createFacility', 'allow', 'ALLOWED', 'corr-with-check')"));
        assertEquals(List.of(), rows(ORG_B, "SELECT correlation_id FROM public.authorization_decision "
                + "WHERE correlation_id = 'corr-with-check'"));
    }

    @Test
    void aTenantlessDecisionFailsClosedAndIsNotStored() throws Exception {
        ApiException failure = (ApiException) inTenant(ORG_A, "admin-a", () -> {
            try {
                authorization.authorize(new CommandRequest("createFacility", null, null),
                        new ServerIdentity(null, "admin-a", "corr-tenantless"));
                return null;
            } catch (ApiException caught) {
                return caught;
            }
        });
        assertNotNull(failure);
        assertEquals(500, failure.status());
        assertEquals("DECISION_NOT_RECORDED", failure.code());
        List<String> rows = rows(ORG_A, "SELECT correlation_id FROM public.authorization_decision "
                + "WHERE correlation_id = 'corr-tenantless'");
        assertEquals(List.of(), rows);
    }
}
