package com.aurevia.messaging.flows.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
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

import com.aurevia.messaging.flows.FlowPolicyStore;
import com.aurevia.messaging.flows.JdbcFlowPolicyStore;
import com.aurevia.messaging.flows.PatientFlowContract.AppLocale;
import com.aurevia.messaging.flows.PatientFlowContract.AppPlatform;
import com.aurevia.messaging.flows.PatientFlowContract.FlowAudit;
import com.aurevia.messaging.flows.PatientFlowContract.NoticeKind;
import com.aurevia.tenant.TenantContext;

/**
 * ARC-01 / ARC-02 / OPS-02 acceptance for the PostgreSQL flow-policy store.
 *
 * <p>Every value is synthetic. Two tenants store identical local policy keys,
 * notice kinds and audit subjects, so a read that escapes tenant scope is
 * observable. Reads and writes bind the tenant through
 * {@link com.aurevia.tenant.TenantContext} exactly as the HTTP
 * interceptor does; V61's forced row-level security is the isolation boundary
 * under test.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_flow_policy_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class JdbcFlowPolicyStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String SHARED_SUBJECT = "patient-shared";
    static final String INACTIVE_SUBJECT = "patient-inactive";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_flow_policy_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    FlowPolicyStore flowPolicyStore;

    @Autowired
    JdbcFlowPolicyStore store;

    /** Create a fresh database, apply all migrations and seed two tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_flow_policy_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_flow_policy_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, true, 7, 9, 42,
                    List.of("new_message", "reauth_required"));
            seedTenant(statement, ORG_B, false, 99, 55, 43,
                    List.of("new_instruction"));
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_flow_policy_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, boolean sharedActive,
                                   int enAndroid, int arIos, int arAndroid,
                                   List<String> notices) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + SHARED_SUBJECT + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + SHARED_SUBJECT + "', 'shared')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + INACTIVE_SUBJECT + "', 'inactive')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, " + (sharedActive ? "'active'" : "'revoked'")
                + " FROM public.user_identity WHERE subject = '" + SHARED_SUBJECT + "'");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'revoked' FROM public.user_identity WHERE subject = '"
                + INACTIVE_SUBJECT + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'patient', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + SHARED_SUBJECT + "'");

        statement.execute("INSERT INTO public.flow_policy (organization_id, locale, platform, version) VALUES ('"
                + org + "', 'en', 'android', " + enAndroid + ")");
        statement.execute("INSERT INTO public.flow_policy (organization_id, locale, platform, version) VALUES ('"
                + org + "', 'ar', 'ios', " + arIos + ")");
        statement.execute("INSERT INTO public.flow_policy (organization_id, locale, platform, version) VALUES ('"
                + org + "', 'ar', 'android', " + arAndroid + ")");
        for (String notice : notices) {
            statement.execute("INSERT INTO public.flow_approved_notice (organization_id, notice_kind) VALUES ('"
                    + org + "', '" + notice + "')");
        }
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "PATIENT_FLOW_PRESENTATION", "flow-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void productionBeanIsTheJdbcStore() {
        assertNotNull(store);
        assertTrue(flowPolicyStore instanceof JdbcFlowPolicyStore,
                "the production FlowPolicyStore bean must be the PostgreSQL adapter");
    }

    @Test
    void membershipComesFromTheAuthoritativeFold() {
        assertTrue(as(ORG_A, SHARED_SUBJECT,
                () -> store.hasActiveMembership(ORG_A, SHARED_SUBJECT)));
        assertFalse(as(ORG_B, SHARED_SUBJECT,
                () -> store.hasActiveMembership(ORG_B, SHARED_SUBJECT)),
                "the same subject is revoked in tenant B");
        assertFalse(as(ORG_A, SHARED_SUBJECT,
                () -> store.hasActiveMembership(ORG_A, INACTIVE_SUBJECT)));
    }

    @Test
    void policyVersionsRoundTripWithIdenticalLocalKeysButDistinctTenants() {
        assertEquals(Integer.valueOf(7), as(ORG_A, SHARED_SUBJECT,
                () -> store.policyVersion(ORG_A, AppLocale.EN, AppPlatform.ANDROID)));
        assertEquals(Integer.valueOf(9), as(ORG_A, SHARED_SUBJECT,
                () -> store.policyVersion(ORG_A, AppLocale.AR, AppPlatform.IOS)));
        assertEquals(Integer.valueOf(99), as(ORG_B, SHARED_SUBJECT,
                () -> store.policyVersion(ORG_B, AppLocale.EN, AppPlatform.ANDROID)));
        assertNull(as(ORG_A, SHARED_SUBJECT,
                () -> store.policyVersion(ORG_A, AppLocale.EN, AppPlatform.IOS)),
                "an unapproved local key returns null rather than another tenant's version");
        assertEquals(Integer.valueOf(42), as(ORG_A, SHARED_SUBJECT,
                () -> store.policyVersion(ORG_A, AppLocale.AR, AppPlatform.ANDROID)));
        assertEquals(Integer.valueOf(43), as(ORG_B, SHARED_SUBJECT,
                () -> store.policyVersion(ORG_B, AppLocale.AR, AppPlatform.ANDROID)));
    }

    @Test
    void approvedNoticesAreTenantScopedWithAnEmptyResult() {
        assertEquals(List.of(NoticeKind.NEW_MESSAGE, NoticeKind.REAUTH_REQUIRED),
                as(ORG_A, SHARED_SUBJECT, () -> store.approvedNotices(ORG_A)));
        assertEquals(List.of(NoticeKind.NEW_INSTRUCTION),
                as(ORG_B, SHARED_SUBJECT, () -> store.approvedNotices(ORG_B)));
        assertEquals(List.of(), as(ORG_A, SHARED_SUBJECT,
                () -> store.approvedNotices("33333333-3333-3333-3333-333333333333")),
                "a tenant with no approved notices gets an empty list");
    }

    @Test
    void auditFactsAreOrderedTenantScopedAndFailClosedOnCrossTenantWrite() {
        Instant firstAt = Instant.parse("2026-11-21T10:00:00Z");
        Instant secondAt = firstAt.plusSeconds(1);
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.appendAudit(audit(ORG_A, "FLOW_PRESENTATION", "SUCCESS", firstAt));
            store.appendAudit(audit(ORG_A, "FLOW_NOTICE", "INVALID_INPUT", secondAt));
            return null;
        });
        as(ORG_B, SHARED_SUBJECT, () -> {
            store.appendAudit(audit(ORG_B, "FLOW_PRESENTATION", "SUCCESS", firstAt));
            return null;
        });

        List<FlowAudit> a = as(ORG_A, SHARED_SUBJECT, () -> store.auditEvents(ORG_A));
        List<FlowAudit> b = as(ORG_B, SHARED_SUBJECT, () -> store.auditEvents(ORG_B));
        assertEquals(2, a.size());
        assertEquals(1, b.size());
        assertTrue(a.stream().allMatch(fact -> ORG_A.equals(fact.organizationId())));
        assertTrue(b.stream().allMatch(fact -> ORG_B.equals(fact.organizationId())));
        assertEquals("FLOW_PRESENTATION", a.get(0).action());
        assertEquals("SUCCESS", a.get(0).outcome());
        assertEquals(firstAt, a.get(0).occurredAt());
        assertEquals("FLOW_NOTICE", a.get(1).action());
        assertEquals("INVALID_INPUT", a.get(1).outcome());
        assertEquals(secondAt, a.get(1).occurredAt());
        assertEquals("PATIENT_FLOW_PRESENTATION", a.get(0).purpose());
        assertEquals("flow-it", a.get(0).correlationId());

        assertThrows(Exception.class, () -> as(ORG_A, SHARED_SUBJECT, () -> {
            store.appendAudit(audit(ORG_B, "FLOW_PRESENTATION", "SUCCESS", firstAt));
            return null;
        }), "WITH CHECK rejects a row whose organization_id is not the bound tenant");
    }

    @Test
    void auditFactsAreImmutableForTheMigrationOwnerToo() throws Exception {
        Instant at = Instant.parse("2026-11-21T11:00:00Z");
        as(ORG_A, SHARED_SUBJECT, () -> {
            store.appendAudit(audit(ORG_A, "FLOW_PRESENTATION", "SUCCESS", at));
            return null;
        });
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.flow_access_audit SET outcome = 'DENIED' WHERE action = 'FLOW_PRESENTATION'"),
                    "the append-only trigger rejects an UPDATE for every role, including the owner");
        }
    }

    private static FlowAudit audit(String org, String action, String outcome, Instant at) {
        return new FlowAudit(org, SHARED_SUBJECT, "PATIENT_FLOW_PRESENTATION", action, "flow-it", outcome, at);
    }
}
