package com.aurevia.smartclinic.therapy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.therapy.JdbcTherapyStore;
import com.aurevia.smartclinic.therapy.TherapyContract.Actor;
import com.aurevia.smartclinic.therapy.TherapyContract.Episode;
import com.aurevia.smartclinic.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.smartclinic.therapy.TherapyContract.Goal;
import com.aurevia.smartclinic.therapy.TherapyContract.Measurement;
import com.aurevia.smartclinic.therapy.TherapyContract.OutcomeRequest;
import com.aurevia.smartclinic.therapy.TherapyContract.OutcomeScale;
import com.aurevia.smartclinic.therapy.TherapyContract.ReferralRequest;
import com.aurevia.smartclinic.therapy.TherapyContract.ScaleRequest;
import com.aurevia.smartclinic.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.smartclinic.therapy.TherapyService;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * PT-01 clause 2 evidence: outcome scales are versioned and each reading stores
 * the exact scale version and unit it used.
 *
 * <p>The tests drive {@link com.aurevia.smartclinic.therapy.TherapyService} through the real
 * {@link com.aurevia.smartclinic.therapy.JdbcTherapyStore} against a fresh PostgreSQL database migrated from
 * {@code db/migrations}. Two tenants share local identifiers so a scope escape
 * is observable, and every fixture is synthetic. The clause has no HTTP route
 * for scale or outcome commands; that missing surface is recorded as a gap in
 * {@code docs/spec/clauses/therapy/version-outcome-scales-store-units.md}.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/smartclinic_therapy_scales_it")
@ActiveProfiles("synthetic-auth")
class VersionOutcomeScalesStoreUnitsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "pt-shared";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/smartclinic_therapy_scales_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);

    private final Actor admin = Actor.of(ORG_A, "scale-admin", TherapyService.TENANT_ADMIN);
    private final Actor governor = Actor.of(ORG_A, "scale-governor", TherapyService.CLINICAL_GOVERNANCE);
    private final Actor pt = Actor.of(ORG_A, SUBJECT, TherapyService.PT_CLINICIAN);
    private final Actor coordinator = Actor.of(ORG_A, "pt-coordinator", TherapyService.PT_COORDINATOR);

    @Autowired
    JdbcTherapyStore store;

    private TherapyService service;
    private String episodeId;

    /** Create a fresh database, apply all migrations and seed two tenants. */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS smartclinic_therapy_scales_it WITH (FORCE)");
            statement.execute("CREATE DATABASE smartclinic_therapy_scales_it OWNER smartclinic_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "smartclinic_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "smartclinic_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Drop the synthetic database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                     "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS smartclinic_therapy_scales_it WITH (FORCE)");
        }
    }

    /** Seed one tenant with its patient and the synthetic clinical actors. */
    private static void seedTenant(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        seedSubject(statement, org, SUBJECT, "pt_clinician");
        seedSubject(statement, org, "scale-admin", "tenant_admin");
        seedSubject(statement, org, "scale-governor", "clinical_governance");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                + "SELECT '" + org + "', '" + PATIENT + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20', id FROM public.user_identity WHERE subject = '" + SUBJECT + "'");
    }

    /** Seed one synthetic subject with an active membership and a single role. */
    private static void seedSubject(Statement statement, String org, String subject, String role)
            throws Exception {
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', 'synthetic " + role + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    /** Build the service over the durable store and a fresh writable episode. */
    @BeforeEach
    void init() {
        service = new TherapyService(store, CLOCK);
        Episode accepted = as(ORG_A, () -> service.acceptReferral(pt, new ReferralRequest(
                "referral-" + UUID.randomUUID(), PATIENT, "referring-org", "referrer-a",
                "2026-11-20T09:00:00Z", "knee pain")));
        episodeId = accepted.id();
        as(ORG_A, () -> {
            service.recordEvaluation(pt, episodeId, new EvaluationRequest(
                    List.of("knee pain"), List.of(new Measurement("ROM", "60", "degrees", "2026-11-20T09:30:00Z")),
                    List.of(new Goal("walk", "independent", "2026-12-20")), 3, 6), accepted.version());
            return null;
        });
    }

    /** Clear the tenant context after each test so a scope cannot leak. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /**
     * Define and approve a scale version in ORG_A.
     *
     * @param key     synthetic scale key
     * @param version version to approve
     * @return the approved scale definition
     */
    private OutcomeScale approve(String key, int version) {
        return as(ORG_A, () -> service.approveOutcomeScale(governor, key, version));
    }

    /**
     * Define a pending scale version in ORG_A.
     *
     * @param key    synthetic scale key
     * @param unit   the scale's unit
     * @param values permitted coded values
     */
    private void define(String key, String unit, List<String> values) {
        as(ORG_A, () -> {
            service.defineOutcomeScale(admin, new ScaleRequest(key, "score " + key, unit, values));
            return null;
        });
    }

    /**
     * Read one outcome reading from the durable store.
     *
     * @return the persisted clinical aggregate
     */
    private TherapyAggregate stored() {
        return as(ORG_A, () -> store.find(ORG_A, episodeId).orElseThrow());
    }

    /**
     * Read the current episode version inside ORG_A.
     *
     * @return the version a caller must present to write next
     */
    private int version() {
        return as(ORG_A, () -> service.get(pt, episodeId)).episode().version();
    }

    /** Bind the tenant context for one JDBC-backed operation, then clear it. */
    private <T> T as(String org, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, SUBJECT, "THERAPY", "therapy-scales-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Assert a domain failure's status and stable error code. */
    private static void assertFailure(int expectedStatus, String expectedCode, ThrowingRunnable action) {
        ApiException failure = assertThrows(ApiException.class, action::run);
        assertEquals(expectedStatus, failure.status());
        assertEquals(expectedCode, failure.code());
    }

    @Test
    void versionsAndStoredReadingsKeepTheirExactScaleVersionAndUnit() {
        String key = "koos-" + UUID.randomUUID();
        define(key, "points", List.of("0", "50", "100"));
        approve(key, 1);
        define(key, "degrees", List.of("0", "100"));
        OutcomeScale v2 = approve(key, 2);

        // The history keeps both definitions; a new version never rewrites its predecessor.
        List<OutcomeScale> versions = as(ORG_A, () -> service.versions(admin, key));
        assertEquals(2, versions.size());
        assertEquals(1, versions.get(0).version());
        assertEquals("points", versions.get(0).unit());
        assertTrue(versions.get(0).approved());
        assertEquals(2, versions.get(1).version());
        assertEquals("degrees", versions.get(1).unit());
        assertEquals(v2.version(), versions.get(1).version());
        assertEquals(v2.description(), versions.get(1).description());
        assertNotEquals(versions.get(0).unit(), versions.get(1).unit());

        // Each reading is written against the version the clinician actually used,
        // re-reading the episode version after the first successful write.
        int firstVersion = version();
        as(ORG_A, () -> {
            service.recordOutcome(pt, episodeId,
                    new OutcomeRequest(key, 1, "50", "points", "2026-11-20T10:30:00Z"), firstVersion);
            return null;
        });
        int secondVersion = version();
        assertNotEquals(firstVersion, secondVersion);
        as(ORG_A, () -> {
            service.recordOutcome(pt, episodeId,
                    new OutcomeRequest(key, 2, "100", "degrees", "2026-11-20T11:30:00Z"), secondVersion);
            return null;
        });

        // Reload from PostgreSQL, not from the in-memory aggregate.
        TherapyAggregate persisted = stored();
        assertEquals(2, persisted.readings().size());
        assertEquals(1, persisted.readings().get(0).scaleVersion());
        assertEquals("points", persisted.readings().get(0).unit());
        assertEquals("50", persisted.readings().get(0).value());
        assertEquals(2, persisted.readings().get(1).scaleVersion());
        assertEquals("degrees", persisted.readings().get(1).unit());
        assertEquals("100", persisted.readings().get(1).value());
        assertNotEquals(persisted.readings().get(0).unit(), persisted.readings().get(1).unit());
        assertEquals("degrees", as(ORG_A, () -> store.scale(ORG_A, key, 2).orElseThrow().unit()));
    }

    @Test
    void refusesUnapprovedScaleWrongUnitAndNonPermittedValue() {
        String key = "koos-" + UUID.randomUUID();
        define(key, "points", List.of("0", "50", "100"));
        int current = version();

        // Failed writes do not advance the episode version, so one precondition
        // covers all three refusals deterministically.
        assertFailure(422, "SCALE_NOT_APPROVED", () -> as(ORG_A, () -> service.recordOutcome(pt, episodeId,
                new OutcomeRequest(key, 1, "50", "points", "2026-11-20T10:30:00Z"), current)));
        approve(key, 1);
        assertFailure(422, "VALUE_NOT_PERMITTED", () -> as(ORG_A, () -> service.recordOutcome(pt, episodeId,
                new OutcomeRequest(key, 1, "37", "points", "2026-11-20T10:30:00Z"), current)));
        assertFailure(422, "UNIT_MISMATCH", () -> as(ORG_A, () -> service.recordOutcome(pt, episodeId,
                new OutcomeRequest(key, 1, "50", "degrees", "2026-11-20T10:30:00Z"), current)));

        assertEquals(current, version());
        assertTrue(stored().readings().isEmpty());
    }

    @Test
    void refusesOutcomeByWrongRoleAndStaleVersion() {
        String key = "koos-" + UUID.randomUUID();
        define(key, "points", List.of("0", "100"));
        approve(key, 1);
        int current = version();

        // A coordinator may accept and summarize but not record an outcome reading.
        assertFailure(403, "FORBIDDEN", () -> as(ORG_A, () -> service.recordOutcome(coordinator, episodeId,
                new OutcomeRequest(key, 1, "100", "points", "2026-11-20T10:30:00Z"), current)));

        as(ORG_A, () -> {
            service.recordOutcome(pt, episodeId,
                    new OutcomeRequest(key, 1, "100", "points", "2026-11-20T10:30:00Z"), current);
            return null;
        });
        assertEquals(current + 1, version());

        assertFailure(412, "STALE_VERSION", () -> as(ORG_A, () -> service.recordOutcome(pt, episodeId,
                new OutcomeRequest(key, 1, "100", "points", "2026-11-20T11:30:00Z"), current)));
    }

    @Test
    void concealsScaleAndReadingFromAnotherTenant() {
        String key = "koos-" + UUID.randomUUID();
        define(key, "points", List.of("0", "100"));
        approve(key, 1);
        int current = version();
        as(ORG_A, () -> {
            service.recordOutcome(pt, episodeId,
                    new OutcomeRequest(key, 1, "100", "points", "2026-11-20T10:30:00Z"), current);
            return null;
        });

        Actor governorB = Actor.of(ORG_B, "scale-governor", TherapyService.CLINICAL_GOVERNANCE);
        Actor ptB = Actor.of(ORG_B, SUBJECT, TherapyService.PT_CLINICIAN);
        assertTrue(as(ORG_B, () -> service.versions(governorB, key)).isEmpty());
        assertTrue(as(ORG_B, () -> store.scale(ORG_B, key, 1)).isEmpty());
        assertFailure(404, "NOT_FOUND", () -> as(ORG_B, () -> service.get(ptB, episodeId)));
        assertEquals(1, stored().readings().size());
    }

    /** Allow lambdas that throw to be passed to {@link #assertFailure(int, String, ThrowingRunnable)}. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
