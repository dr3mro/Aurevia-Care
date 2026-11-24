package com.aurevia.therapy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.therapy.TherapyContract.Actor;
import com.aurevia.therapy.TherapyContract.ClinicalNote;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.NoteRequest;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * PT-01 / ARC-02 acceptance for the JDBC therapy store against real PostgreSQL.
 *
 * <p>The Spring-wired controller and service reach the JDBC adapter over the
 * reserved V85 schema. Two tenants store identical local identifiers so a read
 * that escapes tenant scope is observable. A signed note is immutable at both
 * the service and database layers, and a discharge closes the referral episode.
 * Every fixture is synthetic.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_therapy_store_it")
@ActiveProfiles("synthetic-auth")
class JdbcTherapyStoreIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "pt-shared";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_therapy_store_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    JdbcTherapyStore store;

    @Autowired
    TherapyService service;

    @Autowired
    TherapyController controller;

    private MockMvc mvc;
    private String referralBody;

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
            statement.execute("DROP DATABASE IF EXISTS orevia_therapy_store_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_therapy_store_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
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
            statement.execute("DROP DATABASE IF EXISTS orevia_therapy_store_it WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = 'bootstrap-" + org.substring(0, 4) + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', 'bootstrap-" + org.substring(0, 4) + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + SUBJECT + "', 'synthetic clinician')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + SUBJECT + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'pt_clinician', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + SUBJECT + "'");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, id, version, mrn, mrn_issuer, display_name, normalized_name, dob, created_by) "
                + "SELECT '" + org + "', '" + PATIENT + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient', "
                + "'synthetic patient', '1990-05-20', id FROM public.user_identity WHERE subject = '" + SUBJECT + "'");
    }

    @BeforeEach
    void initMvc() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        referralBody = new ObjectMapper().writeValueAsString(
                new ReferralRequest("referral-shared", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain"));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private Actor clinician(String org) {
        return Actor.of(org, SUBJECT, "pt_clinician");
    }

    private <T> T as(String org, Supplier<T> action) {
        TenantContext.set(new TenantContext(org, SUBJECT, "THERAPY", "therapy-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void httpAcceptAndReadUseTheDurableTenantScopedStore() throws Exception {
        String response = mvc.perform(post("/api/v1/therapy/episodes")
                        .contentType("application/json").content(referralBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referralId").value("referral-shared"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String episodeId = new ObjectMapper().readTree(response).get("id").asString();

        mvc.perform(get("/api/v1/therapy/episodes/{id}", episodeId)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episode.patientId").value(PATIENT));

        assertEquals(PATIENT, as(ORG_A, () -> store.find(ORG_A, episodeId).orElseThrow()
                .episode().patientId()));

        mvc.perform(get("/api/v1/therapy/episodes/{id}", episodeId)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_B))
                .andExpect(status().isNotFound());
    }

    @Test
    void signedNoteIsImmutableAndDischargeClosesTheReferral() throws Exception {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-discharge", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));
        String id = accepted.id();
        as(ORG_A, () -> {
            service.recordEvaluation(clinician(ORG_A), id, new EvaluationRequest(
                    List.of("knee pain"), List.of(new Measurement("ROM", "60", "degrees", "2026-11-20T09:30:00Z")),
                    List.of(new Goal("walk", "independent", "2026-12-20")), 3, 6), accepted.version());
            return null;
        });
        TherapyAggregate inTreatment = as(ORG_A, () -> service.get(clinician(ORG_A), id));
        ClinicalNote session = as(ORG_A, () -> {
            service.recordNote(clinician(ORG_A), id,
                    new NoteRequest(NoteKind.SESSION, "session", "2026-11-21T10:00:00Z", null),
                    inTreatment.episode().version());
            return service.signNote(clinician(ORG_A), id, NoteKind.SESSION, 1, "I attest",
                    inTreatment.episode().version() + 1);
        });
        assertEquals(true, session.signed());

        int beforeDischarge = as(ORG_A, () -> service.get(clinician(ORG_A), id)).episode().version();
        as(ORG_A, () -> {
            service.recordNote(clinician(ORG_A), id,
                    new NoteRequest(NoteKind.DISCHARGE, "discharge", "2026-11-30T10:00:00Z", null),
                    beforeDischarge);
            return null;
        });
        ClinicalNote discharge = as(ORG_A, () -> service.signNote(clinician(ORG_A), id, NoteKind.DISCHARGE,
                1, "discharge attested", beforeDischarge + 1));
        assertEquals(true, discharge.signed());

        TherapyAggregate closed = as(ORG_A, () -> store.find(ORG_A, id).orElseThrow());
        assertEquals(EpisodeState.DISCHARGED, closed.episode().state());
        assertThrows(ApiException.class, () -> as(ORG_A, () -> {
            service.signNote(clinician(ORG_A), id, NoteKind.SESSION, 1, "I attest", closed.episode().version());
            return null;
        }));

        try (Connection connection = DriverManager.getConnection(URL, "orevia_app", "");
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE public.therapy_clinical_note SET content = 'tampered' "
                            + "WHERE episode_id = '" + id + "' AND kind = 'session' AND version = 1"));
            assertEquals("42501", failure.getSQLState());
        }
    }

    @Test
    void goalsPersistInClinicalOrderIncludingDuplicateDescriptions() {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-goals", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));
        int version = accepted.version();
        as(ORG_A, () -> {
            service.recordEvaluation(clinician(ORG_A), accepted.id(), new EvaluationRequest(
                    List.of("knee pain"), List.of(),
                    List.of(new Goal("walk without pain", "independent", "2026-12-20"),
                            new Goal("climb stairs", "assisted", "2026-12-27"),
                            new Goal("walk without pain", "community", "2027-01-10")),
                    3, 6), version);
            return null;
        });
        TherapyAggregate stored = as(ORG_A, () -> store.find(ORG_A, accepted.id()).orElseThrow());
        assertEquals(List.of("walk without pain", "climb stairs", "walk without pain"),
                stored.evaluation().goals().stream().map(Goal::description).toList());
    }

    @Test
    void tenantIsolationHoldsForScalesAndAudit() {
        assertEquals(1, as(ORG_A, () -> store.allocateScaleVersion(ORG_A, "KOOS")));
        assertEquals(2, as(ORG_A, () -> store.allocateScaleVersion(ORG_A, "KOOS")));
        assertEquals(1, as(ORG_B, () -> store.allocateScaleVersion(ORG_B, "KOOS")));
        assertEquals(0, as(ORG_B, () -> store.scales(ORG_B, "KOOS").size()));

        as(ORG_A, () -> {
            store.appendAudit(new TherapyAuditEvent(ORG_A, SUBJECT, "EPISODE_ACCEPT", "a", "SUCCESS"));
            return null;
        });
        List<TherapyAuditEvent> audits = as(ORG_A, () -> store.audit(ORG_A));
        assertFalse(audits.isEmpty());
        assertTrueTenantScoped(audits, ORG_A);
        List<TherapyAuditEvent> otherTenant = as(ORG_B, () -> store.audit(ORG_B));
        assertFalse(otherTenant.isEmpty());
        assertTrueTenantScoped(otherTenant, ORG_B);
        assertEquals(0, otherTenant.stream().filter(event -> "SUCCESS".equals(event.outcome())).count());
    }

    private static void assertTrueTenantScoped(List<TherapyAuditEvent> audits, String organization) {
        assertEquals(organization, audits.get(0).organizationId());
        assertEquals(true, audits.stream().allMatch(event -> organization.equals(event.organizationId())));
    }
}
