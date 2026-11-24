package com.aurevia.messaging.enrollment.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import com.aurevia.messaging.enrollment.EnrollmentActions;
import com.aurevia.messaging.enrollment.EnrollmentContract.AccountState;
import com.aurevia.messaging.enrollment.EnrollmentContract.EnrollRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.EventType;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;
import com.aurevia.messaging.enrollment.EnrollmentContract.IssueInvitationRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.PatientAccount;
import com.aurevia.messaging.enrollment.EnrollmentContract.RecoverRequest;
import com.aurevia.messaging.enrollment.EnrollmentContract.RelationshipRef;
import com.aurevia.messaging.enrollment.JdbcPatientAccessStore;
import com.aurevia.messaging.enrollment.PatientAccessActor;
import com.aurevia.messaging.enrollment.PatientAccessService;
import com.aurevia.messaging.enrollment.PatientAccessStore;
import com.aurevia.tenant.TenantContext;

/**
 * PAT-03 / IAM-05 / ARC-02 acceptance for the PostgreSQL patient-access store.
 *
 * <p>Every value is synthetic and each test uses a fresh subject so tests cannot
 * contaminate one another. Two tenants contain charts with the same local
 * identifier and accounts with the same subject, so a read that escapes tenant
 * scope is observable. Reads and writes bind the tenant through
 * {@link com.aurevia.tenant.TenantContext} exactly as the HTTP
 * interceptor does; V83's forced row-level security is the isolation boundary
 * under test. Lockout and single-use state are read back through a freshly
 * constructed service to prove they are not process-local.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_patient_access_it")
@ActiveProfiles("synthetic-auth")
@Timeout(300)
class JdbcPatientAccessStoreIntegrationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String FACTS = "site-verified-facts";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_patient_access_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    PatientAccessStore store;

    @Autowired
    JdbcPatientAccessStore jdbcStore;

    /**
     * Create roles, a fresh database, apply all migrations and seed two tenants.
     *
     * <p>Role creation must happen in the same {@link org.junit.jupiter.api.BeforeAll}
     * method that creates the database owned by that role. JUnit does not guarantee the
     * relative order of several {@code @BeforeAll} methods in one class, so a separate
     * role-creation method can run after {@code prepare()} and leave the
     * {@code CREATE DATABASE ... OWNER orevia_migrate} statement with no role to
     * own it.</p>
     */

    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            // Roles must be created before CREATE DATABASE ... OWNER in the same
            // @BeforeAll: JUnit does not guarantee the order of multiple @BeforeAll
            // methods, so a separate roles() method could run after prepare().
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_access_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_patient_access_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "MRN-PA-A");
            seedTenant(statement, ORG_B, "MRN-PA-B");
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_patient_access_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, String mrn) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', 'synthetic-seeder', 'synthetic-seeder')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE subject = 'synthetic-seeder'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', '" + CHART + "', 1, '" + mrn + "', 'CLINIC', "
                + "'Synthetic Patient', 'synthetic patient', DATE '1990-05-20')");
    }

    /** Bind the active tenant on the calling thread, as the HTTP interceptor does. */
    private <T> T as(String org, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, subject, "PATIENT_ACCESS", "patient-access-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    private PatientAccessService service(Clock clock) {
        return new PatientAccessService(store, new EnrollmentActions(), clock);
    }

    private static PatientAccessActor staff(String org) {
        return PatientAccessActor.of(org, "reception", EnrollmentActions.RECEPTION);
    }

    private static PatientAccessActor patient(String org, String subject) {
        return PatientAccessActor.of(org, subject, EnrollmentActions.PATIENT);
    }

    private static String freshSubject() {
        return "syn-" + UUID.randomUUID();
    }

    private Invitation issue(PatientAccessService service, String org, Long ttl) {
        return as(org, "reception", () -> service.issueInvitation(staff(org),
                new IssueInvitationRequest(CHART, new RelationshipRef("enc-1", true), FACTS, ttl)));
    }

    private static int accountCount(String org, String subject) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            var rows = statement.executeQuery("SELECT count(*) FROM public.patient_access_account "
                    + "WHERE subject = '" + subject + "'");
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void productionBeanIsTheJdbcStore() {
        assertNotNull(jdbcStore);
        assertTrue(store instanceof JdbcPatientAccessStore,
                "the production PatientAccessStore bean must be the PostgreSQL adapter");
    }

    @Test
    void reusedInvitationFailsAndNeverCreatesASecondAccount() throws Exception {
        String subject = freshSubject();
        PatientAccessService service = service(Clock.systemUTC());
        Invitation invitation = issue(service, ORG_A, null);
        assertEquals(InvitationState.PENDING, invitation.state());

        as(ORG_A, subject, () -> service.enroll(patient(ORG_A, subject), new EnrollRequest(invitation.code(), FACTS)));

        ApiException reused = as(ORG_A, subject, () -> assertThrows(ApiException.class,
                () -> service.enroll(patient(ORG_A, subject), new EnrollRequest(invitation.code(), FACTS))));
        assertEquals(409, reused.status());
        assertEquals("INVITATION_REUSED", reused.code());
        assertEquals(1, accountCount(ORG_A, subject));
        assertEquals(InvitationState.REDEEMED,
                as(ORG_A, subject, () -> store.invitation(ORG_A, invitation.code()).orElseThrow().state()));
    }

    @Test
    void expiredInvitationFails() throws Exception {
        String subject = freshSubject();
        MutableClock clock = new MutableClock();
        PatientAccessService service = service(clock);
        Invitation invitation = issue(service, ORG_A, 60L);
        clock.advanceSeconds(61);

        ApiException expired = as(ORG_A, subject, () -> assertThrows(ApiException.class,
                () -> service.enroll(patient(ORG_A, subject), new EnrollRequest(invitation.code(), FACTS))));
        assertEquals(409, expired.status());
        assertEquals("INVITATION_EXPIRED", expired.code());
        assertEquals(0, accountCount(ORG_A, subject));
    }

    @Test
    void lockoutSurvivesAServiceRestart() {
        String subject = freshSubject();
        PatientAccessService first = service(Clock.systemUTC());
        Invitation invitation = issue(first, ORG_A, null);
        as(ORG_A, subject, () -> first.enroll(patient(ORG_A, subject), new EnrollRequest(invitation.code(), FACTS)));
        PatientAccount account = as(ORG_A, subject, () -> store.accountBySubject(ORG_A, subject).orElseThrow());
        PatientAccessActor privacy = PatientAccessActor.of(ORG_A, "privacy", EnrollmentActions.PRIVACY_AUDITOR);

        for (int attempt = 0; attempt < PatientAccessService.MAX_FAILURES; attempt++) {
            String code = as(ORG_A, "privacy", () -> first.issueRecovery(privacy, account.id()).token());
            as(ORG_A, subject, () -> assertThrows(ApiException.class, () -> first.recover(
                    patient(ORG_A, subject), new RecoverRequest(account.id(), code, "wrong-facts"))));
        }

        // A brand-new service over the same durable store observes the lockout.
        PatientAccessService restarted = service(new MutableClock());
        PatientAccount locked = as(ORG_A, subject, () -> store.account(ORG_A, account.id()).orElseThrow());
        assertEquals(AccountState.LOCKED, locked.state());
        assertEquals(PatientAccessService.MAX_FAILURES, locked.failedAttempts());
        ApiException stillLocked = as(ORG_A, "privacy", () -> assertThrows(ApiException.class,
                () -> restarted.issueRecovery(privacy, account.id())));
        assertEquals(423, stillLocked.status());
    }

    @Test
    void responsesDoNotRevealWhetherARecordExists() {
        String subjectA = freshSubject();
        String subjectB = freshSubject();
        PatientAccessService service = service(Clock.systemUTC());
        Invitation bInvitation = issue(service, ORG_B, null);
        as(ORG_B, subjectB, () -> service.enroll(patient(ORG_B, subjectB),
                new EnrollRequest(bInvitation.code(), FACTS)));
        PatientAccount bAccount = as(ORG_B, subjectB, () -> store.accountBySubject(ORG_B, subjectB).orElseThrow());

        PatientAccessActor admin = PatientAccessActor.of(ORG_A, "admin", EnrollmentActions.TENANT_ADMIN);
        ApiException unknownAccount = as(ORG_A, "admin", () -> assertThrows(ApiException.class,
                () -> service.unlock(admin, "33333333-3333-3333-3333-333333333333")));
        ApiException foreignAccount = as(ORG_A, "admin", () -> assertThrows(ApiException.class,
                () -> service.unlock(admin, bAccount.id())));
        assertEquals(unknownAccount.status(), foreignAccount.status());
        assertEquals(unknownAccount.code(), foreignAccount.code());
        assertEquals(unknownAccount.getMessage(), foreignAccount.getMessage());

        ApiException unknownInvitation = as(ORG_A, subjectA, () -> assertThrows(ApiException.class,
                () -> service.enroll(patient(ORG_A, subjectA), new EnrollRequest("missing-code", FACTS))));
        ApiException foreignInvitation = as(ORG_A, subjectA, () -> assertThrows(ApiException.class,
                () -> service.enroll(patient(ORG_A, subjectA), new EnrollRequest(bInvitation.code(), FACTS))));
        assertEquals(unknownInvitation.status(), foreignInvitation.status());
        assertEquals(unknownInvitation.code(), foreignInvitation.code());
        assertEquals(unknownInvitation.getMessage(), foreignInvitation.getMessage());

        // Generic notifications carry an event type only, never a chart or a reason.
        List<String> types = as(ORG_A, "support", () -> service.notifications(
                PatientAccessActor.of(ORG_A, "support", EnrollmentActions.SUPPORT_AGENT)))
                .stream().map(notification -> notification.eventType()).toList();
        assertTrue(types.stream().noneMatch(type -> type.contains(CHART) || type.contains(FACTS)));
    }

    @Test
    void tenantsWithIdenticalLocalIdentifiersStayIsolated() throws Exception {
        String subject = freshSubject();
        PatientAccessService service = service(Clock.systemUTC());
        Invitation invitation = issue(service, ORG_A, null);

        ApiException crossTenant = as(ORG_B, subject, () -> assertThrows(ApiException.class,
                () -> service.enroll(patient(ORG_B, subject), new EnrollRequest(invitation.code(), FACTS))));
        assertEquals(404, crossTenant.status());
        assertEquals(0, accountCount(ORG_B, subject));

        as(ORG_A, subject, () -> service.enroll(patient(ORG_A, subject), new EnrollRequest(invitation.code(), FACTS)));
        assertTrue(as(ORG_A, subject, () -> store.accountBySubject(ORG_A, subject)).isPresent());
        assertTrue(as(ORG_B, subject, () -> store.accountBySubject(ORG_B, subject)).isEmpty());
        assertEquals(1, accountCount(ORG_A, subject));
        assertEquals(0, accountCount(ORG_B, subject));

        // The same local chart identifier exists in both tenants but a read in
        // ORG_B cannot see ORG_A's invitation or its notifications.
        assertTrue(as(ORG_B, subject, () -> store.chartExists(ORG_B, CHART)));
        assertTrue(as(ORG_B, subject, () -> store.invitation(ORG_B, invitation.code()).isEmpty()));
        assertFalse(as(ORG_B, subject, () -> store.notifications(ORG_B)).stream()
                .anyMatch(notification -> notification.organizationId().equals(ORG_A)));
    }

    @Test
    void twoSimultaneousRequestsConsumeTheInvitationOnce() throws Exception {
        String subject = freshSubject();
        PatientAccessService service = service(Clock.systemUTC());
        Invitation invitation = issue(service, ORG_A, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<String> task = () -> {
                start.await();
                try {
                    TenantContext.set(new TenantContext(ORG_A, subject, "PATIENT_ACCESS", "patient-access-it"));
                    service.enroll(patient(ORG_A, subject), new EnrollRequest(invitation.code(), FACTS));
                    return "SUCCESS";
                } catch (ApiException exception) {
                    return exception.code();
                } finally {
                    TenantContext.clear();
                }
            };
            Future<String> first = pool.submit(task);
            Future<String> second = pool.submit(task);
            start.countDown();
            List<String> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("SUCCESS"::equals).count(), results.toString());
            assertEquals(1, results.stream().filter("INVITATION_REUSED"::equals).count(), results.toString());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, accountCount(ORG_A, subject));
        assertTrue(as(ORG_A, subject, () -> store.audit(ORG_A)).stream()
                .anyMatch(event -> event.eventType() == EventType.ENROLL_SUCCESS));
        assertEquals(Set.of(CHART), as(ORG_A, subject,
                () -> store.accountBySubject(ORG_A, subject).orElseThrow().linkedChartIds()));
    }

    /** Deterministic clock so expiry and recovery windows are reproducible (test only). */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-21T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
