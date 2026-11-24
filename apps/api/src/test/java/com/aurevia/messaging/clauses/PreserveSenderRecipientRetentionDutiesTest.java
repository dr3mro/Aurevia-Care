package com.aurevia.messaging.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDuty;
import com.aurevia.messaging.governance.MessagingGovernanceService;
import com.aurevia.tenant.TenantContext;

/**
 * MSG-07 clause 6 (API evidence): a messaging resource's sender and recipient
 * retention windows are two distinct, durable values that survive a legal hold.
 *
 * <p>These active tests drive the real
 * {@link com.aurevia.messaging.governance.MessagingGovernanceService}
 * against a real PostgreSQL database migrated from {@code db/migrations} under
 * forced row-level security. The service is the authoritative application entry
 * point for this clause: it derives the tenant from
 * {@link com.aurevia.tenant.TenantContext},
 * re-checks active membership and role, reads the tenant-scoped duty pair and
 * conceals or refuses what the caller must not see. The HTTP adapter has no duty
 * route yet, so the proofs stop at the service boundary; the remaining exposure
 * work is recorded in the clause status document.</p>
 *
 * <p>Each test asserts a concrete value, status or code. All identifiers and
 * subjects are synthetic and every seeded duty row lives in a disposable
 * database dropped in {@link #cleanUp()}.</p>
 */
@Tag("integration")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_retention_duty_it")
@ActiveProfiles("synthetic-auth")
class PreserveSenderRecipientRetentionDutiesTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String RESOURCE_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String RESOURCE_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    static final String UNKNOWN_RESOURCE = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_retention_duty_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    MessagingGovernanceService service;

    /** Create a fresh database, apply migrations and seed two isolated tenants and their duties. */
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
            statement.execute("DROP DATABASE IF EXISTS orevia_retention_duty_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_retention_duty_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "auditor-a", "privacy_auditor");
            seed(statement, ORG_A, "reception-a", "reception");
            seed(statement, ORG_B, "auditor-b", "privacy_auditor");
            seedResource(statement, ORG_A, RESOURCE_A);
            seedResource(statement, ORG_B, RESOURCE_B);
            seedDuty(statement, ORG_A, RESOURCE_A, 30L, 90L);
            seedDuty(statement, ORG_B, RESOURCE_B, 10L, 20L);
        }
    }

    /** Drop the disposable synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_retention_duty_it WITH (FORCE)");
        }
    }

    /** Remove the thread-bound tenant context so a test never leaks identity into another. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Bind the synthetic subject and active organization used by the service's tenant and role checks. */
    private static void as(String subject, String organizationId) {
        TenantContext.set(new TenantContext(organizationId, subject, "MSG_GOVERNANCE", "corr-1"));
    }

    /** Create an organization, an active membership and one bounded role for a synthetic subject. */
    private static void seed(Statement statement, String org, String subject, String role) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Seed one tracked messaging resource inside its tenant's forced-RLS scope. */
    private static void seedResource(Statement statement, String org, String resource) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.messaging_resource (organization_id, id, resource_type) "
                + "VALUES ('" + org + "', '" + resource + "', 'conversation')");
    }

    /** Seed distinct sender and recipient windows directly; there is no HTTP duty writer yet. */
    private static void seedDuty(Statement statement, String org, String resource,
                                 long senderDays, long recipientDays) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.messaging_retention_duty "
                + "(organization_id, resource_id, sender_retention_days, recipient_retention_days) "
                + "VALUES ('" + org + "', '" + resource + "', " + senderDays + ", " + recipientDays + ")");
    }

    /**
     * Rule met: the two windows are distinct durable values on the read path and a
     * legal hold never waives either party's duty.
     */
    @Test
    void retentionDutyKeepsBothDistinctWindowsAfterALegalHold() {
        as("auditor-a", ORG_A);
        RetentionDuty before = service.retentionDuty(RESOURCE_A);
        assertEquals(30, before.senderRetentionDays());
        assertEquals(90, before.recipientRetentionDays());

        LegalHold hold = service.placeHold(new PlaceHoldRequest(RESOURCE_A, "Synthetic dispute"));
        assertTrue(hold.active(), "the hold must be active for the survival claim to mean anything");
        assertEquals(RESOURCE_A, hold.resourceId());
        assertEquals(ORG_A, hold.organizationId());

        RetentionDuty after = service.retentionDuty(RESOURCE_A);
        assertEquals(30, after.senderRetentionDays());
        assertEquals(90, after.recipientRetentionDays());

        // Release the hold so a rerun of this test does not hit 409 HOLD_EXISTS.
        assertFalse(service.releaseHold(hold.id(), "Synthetic dispute resolved").active());
    }

    /**
     * Negative: a subject of another tenant must not see the first tenant's windows,
     * and the refusal must not leak either value even though the duty exists there.
     */
    @Test
    void anotherTenantCannotReadTheDutyAndSeesNoWindowValues() {
        as("auditor-b", ORG_B);
        ApiException refused = assertThrows(ApiException.class,
                () -> service.retentionDuty(RESOURCE_A));
        assertEquals(404, refused.status());
        assertEquals("NOT_FOUND", refused.code());
        assertFalse(refused.getMessage().contains("RetentionDays"),
                "a cross-tenant refusal must not carry either duty field");
        assertFalse(refused.getMessage().contains("30") || refused.getMessage().contains("90"),
                "a cross-tenant refusal must not carry either duty window value");

        as("auditor-a", ORG_A);
        assertEquals(30, service.retentionDuty(RESOURCE_A).senderRetentionDays());
    }

    /**
     * Negative: AUDIT_READ is reserved for governance roles; a reception member is
     * refused.
     */
    @Test
    void receptionCannotReadTheRetentionDuty() {
        as("reception-a", ORG_A);
        ApiException denied = assertThrows(ApiException.class,
                () -> service.retentionDuty(RESOURCE_A));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
    }

    /**
     * Negative: an unknown resource is concealed as a not-found duty rather than
     * answering with an empty or default pair.
     */
    @Test
    void unknownResourceHasNoReadableDuty() {
        as("auditor-a", ORG_A);
        ApiException missing = assertThrows(ApiException.class,
                () -> service.retentionDuty(UNKNOWN_RESOURCE));
        assertEquals(404, missing.status());
        assertEquals("NOT_FOUND", missing.code());
    }
}
