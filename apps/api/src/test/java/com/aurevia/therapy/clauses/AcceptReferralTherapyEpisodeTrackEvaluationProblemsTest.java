package com.aurevia.therapy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
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
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.therapy.JdbcTherapyStore;
import com.aurevia.therapy.TherapyContract.Actor;
import com.aurevia.therapy.TherapyContract.ClinicalNote;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.Evaluation;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Intervention;
import com.aurevia.therapy.TherapyContract.InterventionRequest;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.NoteRequest;
import com.aurevia.therapy.TherapyContract.OutcomeReading;
import com.aurevia.therapy.TherapyContract.OutcomeRequest;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.ScaleRequest;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyController;
import com.aurevia.therapy.TherapyService;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * PT-01 clause 1 evidence against real PostgreSQL with synthetic fixtures only.
 *
 * <p>Referral acceptance and episode read are proven through the wired
 * {@link com.aurevia.therapy.TherapyController} HTTP entry point;
 * evaluation, intervention, outcome, progress and discharge are proven through the
 * wired {@link com.aurevia.therapy.TherapyService} over the same
 * durable tenant-scoped store. Negative tests pin the role, tenant, version,
 * input and state guards. The database name is unique to this class so parallel
 * workers never share fixtures.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/aurevia_therapy_clause1_it")
@ActiveProfiles("synthetic-auth")
class AcceptReferralTherapyEpisodeTrackEvaluationProblemsTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "pt-shared";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String URL = "jdbc:postgresql://127.0.0.1:5432/aurevia_therapy_clause1_it";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    JdbcTherapyStore store;

    @Autowired
    TherapyService service;

    @Autowired
    TherapyController controller;

    private MockMvc mvc;
    private String referralBody;

    /** Create a fresh database, apply all migrations and seed two isolated tenants. */
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
            statement.execute("DROP DATABASE IF EXISTS aurevia_therapy_clause1_it WITH (FORCE)");
            statement.execute("CREATE DATABASE aurevia_therapy_clause1_it OWNER aurevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "aurevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(URL, "aurevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A);
            seedTenant(statement, ORG_B);
        }
    }

    /** Drop the throwaway database after the suite. */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                 "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS aurevia_therapy_clause1_it WITH (FORCE)");
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

    /** Build the MockMvc harness and a synthetic referral body before each test. */
    @BeforeEach
    void initMvc() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        referralBody = new ObjectMapper().writeValueAsString(
                new ReferralRequest("referral-clause-http", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain"));
    }

    /** Never leak a tenant context across tests. */
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

    private static EvaluationRequest validEvaluation() {
        return new EvaluationRequest(
                List.of("knee pain", "reduced ROM"),
                List.of(new Measurement("ROM", "60", "degrees", "2026-11-20T09:30:00Z"),
                        new Measurement("VAS", "7", "score_0_10", "2026-11-20T09:35:00Z")),
                List.of(new Goal("walk independently", "independent", "2026-12-20"),
                        new Goal("climb stairs", "assisted", "2026-12-27")),
                3, 6);
    }

    /** Read the current episode version so each command uses a fresh precondition. */
    private int versionOf(String id) {
        return as(ORG_A, () -> service.get(clinician(ORG_A), id)).episode().version();
    }

    @Test
    void acceptsReferralAndTracksEvaluationProblemsBaselinesGoalsAndPlan() throws Exception {
        String response = mvc.perform(post("/api/v1/therapy/episodes")
                        .contentType(MediaType.APPLICATION_JSON).content(referralBody)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referralId").value("referral-clause-http"))
                .andExpect(jsonPath("$.patientId").value(PATIENT))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(response).get("id").asString();

        mvc.perform(get("/api/v1/therapy/episodes/{id}", id)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episode.state").value("ACTIVE"))
                .andExpect(jsonPath("$.episode.patientId").value(PATIENT));

        Episode accepted = as(ORG_A, () -> store.find(ORG_A, id).orElseThrow().episode());
        assertEquals(EpisodeState.ACTIVE, accepted.state());
        assertEquals(1, accepted.version());

        Evaluation evaluation = as(ORG_A, () -> service.recordEvaluation(
                clinician(ORG_A), id, validEvaluation(), accepted.version()));
        assertEquals(List.of("knee pain", "reduced ROM"), evaluation.problems());
        assertEquals(2, evaluation.baselines().size());
        assertEquals("degrees", evaluation.baselines().get(0).unit());
        assertEquals("score_0_10", evaluation.baselines().get(1).unit());
        assertEquals("60", evaluation.baselines().get(0).value());
        assertEquals(2, evaluation.goals().size());
        assertEquals("walk independently", evaluation.goals().get(0).description());
        assertEquals("climb stairs", evaluation.goals().get(1).description());
        assertEquals(3, evaluation.frequencyPerWeek());
        assertEquals(6, evaluation.durationWeeks());
        assertEquals(SUBJECT, evaluation.author());

        TherapyAggregate tracked = as(ORG_A, () -> store.find(ORG_A, id).orElseThrow());
        assertEquals(EpisodeState.IN_TREATMENT, tracked.episode().state());
        assertEquals(2, tracked.episode().version());
        assertEquals(evaluation.problems(), tracked.evaluation().problems());
        assertEquals(evaluation.baselines(), tracked.evaluation().baselines());
        assertEquals(evaluation.goals(), tracked.evaluation().goals());
        assertEquals(3, tracked.evaluation().frequencyPerWeek());
        assertEquals(6, tracked.evaluation().durationWeeks());
    }

    @Test
    void tracksInterventionsOutcomesProgressAndDischarge() {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-track-all", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));
        String id = accepted.id();

        as(ORG_A, () -> {
            service.recordEvaluation(clinician(ORG_A), id, validEvaluation(), accepted.version());
            return null;
        });
        assertEquals(EpisodeState.IN_TREATMENT,
                as(ORG_A, () -> service.get(clinician(ORG_A), id)).episode().state());

        ScaleRequest scale = new ScaleRequest("KOOS", "Knee outcome score", "score",
                List.of("0", "25", "50", "75", "100"));
        as(ORG_A, () -> {
            service.defineOutcomeScale(Actor.of(ORG_A, "admin-a", TherapyService.TENANT_ADMIN), scale);
            return null;
        });
        as(ORG_A, () -> {
            service.approveOutcomeScale(Actor.of(ORG_A, "gov-a", TherapyService.CLINICAL_GOVERNANCE), "KOOS", 1);
            return null;
        });

        int outcomeVersion = versionOf(id);
        OutcomeReading reading = as(ORG_A, () -> service.recordOutcome(clinician(ORG_A), id,
                new OutcomeRequest("KOOS", 1, "75", "score", "2026-11-25T10:00:00Z"), outcomeVersion));
        assertEquals("75", reading.value());
        assertEquals("score", reading.unit());
        assertEquals(1, reading.scaleVersion());

        int interventionVersion = versionOf(id);
        Intervention intervention = as(ORG_A, () -> service.recordIntervention(clinician(ORG_A), id,
                new InterventionRequest("manual therapy and exercise", "2026-11-25T10:30:00Z"),
                interventionVersion));
        assertEquals("manual therapy and exercise", intervention.description());
        assertEquals(SUBJECT, intervention.author());

        int progressDraftVersion = versionOf(id);
        as(ORG_A, () -> {
            service.recordNote(clinician(ORG_A), id,
                    new NoteRequest(NoteKind.PROGRESS, "steady progress", "2026-11-26T10:00:00Z", 1),
                    progressDraftVersion);
            return null;
        });
        ClinicalNote progress = as(ORG_A, () -> service.signNote(
                clinician(ORG_A), id, NoteKind.PROGRESS, 1, "progress attested", progressDraftVersion + 1));
        assertTrue(progress.signed());
        assertEquals("progress attested", progress.attestation());
        assertTrue(progress.contentChecksum() != null && !progress.contentChecksum().isBlank());

        int dischargeDraftVersion = versionOf(id);
        as(ORG_A, () -> {
            service.recordNote(clinician(ORG_A), id,
                    new NoteRequest(NoteKind.DISCHARGE, "goals met", "2026-11-30T10:00:00Z", 1),
                    dischargeDraftVersion);
            return null;
        });
        ClinicalNote discharge = as(ORG_A, () -> service.signNote(
                clinician(ORG_A), id, NoteKind.DISCHARGE, 1, "discharge attested", dischargeDraftVersion + 1));
        assertTrue(discharge.signed());
        assertEquals(NoteKind.DISCHARGE, discharge.kind());

        TherapyAggregate finalState = as(ORG_A, () -> store.find(ORG_A, id).orElseThrow());
        assertEquals(EpisodeState.DISCHARGED, finalState.episode().state());
        assertEquals(8, finalState.episode().version());
        assertEquals(1, finalState.readings().size());
        assertEquals("75", finalState.readings().get(0).value());
        assertEquals(1, finalState.interventions().size());
        assertEquals(2, finalState.notes().size());
        assertTrue(finalState.notes().stream().allMatch(ClinicalNote::signed));
    }

    @Test
    void refusesAcceptanceFromClinicianWithoutPtRole() {
        ApiException denial = assertThrows(ApiException.class, () -> as(ORG_A, () ->
                service.acceptReferral(Actor.of(ORG_A, SUBJECT, "reception"),
                        new ReferralRequest("referral-wrong-role", PATIENT, "referring-org", "referrer-a",
                                "2026-11-20T09:00:00Z", "knee pain"))));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
    }

    @Test
    void refusesDuplicateReferral() {
        ReferralRequest request = new ReferralRequest("referral-duplicate", PATIENT, "referring-org",
                "referrer-a", "2026-11-20T09:00:00Z", "knee pain");
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A), request));

        ApiException duplicate = assertThrows(ApiException.class, () -> as(ORG_A, () ->
                service.acceptReferral(clinician(ORG_A), request)));
        assertEquals("referral-duplicate", accepted.referralId());
        assertEquals(409, duplicate.status());
        assertEquals("CONFLICT", duplicate.code());
        assertEquals(1, as(ORG_A, () -> store.find(ORG_A, accepted.id()).orElseThrow())
                .episode().version());
    }

    @Test
    void concealsEpisodeFromAnotherTenant() throws Exception {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-other-tenant", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));
        String id = accepted.id();

        mvc.perform(get("/api/v1/therapy/episodes/{id}", id)
                        .header("X-Synthetic-Subject", SUBJECT)
                        .header("X-Active-Organization", ORG_B))
                .andExpect(status().isNotFound());

        ApiException hidden = assertThrows(ApiException.class,
                () -> as(ORG_B, () -> service.get(clinician(ORG_B), id)));
        assertEquals(404, hidden.status());
        assertEquals("NOT_FOUND", hidden.code());
    }

    @Test
    void refusesEvaluationWithStaleVersion() {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-stale", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));
        ApiException stale = assertThrows(ApiException.class, () -> as(ORG_A, () ->
                service.recordEvaluation(clinician(ORG_A), accepted.id(), validEvaluation(),
                        accepted.version() + 1)));
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());
    }

    @Test
    void refusesEvaluationWithMissingListsOrBaselineUnit() {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-invalid-eval", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));
        String id = accepted.id();

        ApiException noProblems = assertThrows(ApiException.class, () -> as(ORG_A, () ->
                service.recordEvaluation(clinician(ORG_A), id,
                        new EvaluationRequest(null, List.of(), List.of(), 3, 6), accepted.version())));
        assertEquals(422, noProblems.status());
        assertEquals("INVALID_EVALUATION", noProblems.code());

        ApiException noBaselineUnit = assertThrows(ApiException.class, () -> as(ORG_A, () ->
                service.recordEvaluation(clinician(ORG_A), id,
                        new EvaluationRequest(List.of("knee pain"),
                                List.of(new Measurement("ROM", "60", " ", "2026-11-20T09:30:00Z")),
                                List.of(new Goal("walk", "independent", "2026-12-20")), 3, 6),
                        accepted.version())));
        assertEquals(422, noBaselineUnit.status());
        assertEquals("MISSING_UNIT", noBaselineUnit.code());

        assertEquals(EpisodeState.ACTIVE,
                as(ORG_A, () -> store.find(ORG_A, id).orElseThrow()).episode().state());
        assertEquals(1, as(ORG_A, () -> store.find(ORG_A, id).orElseThrow()).episode().version());
    }

    @Test
    void refusesInterventionBeforeEvaluation() {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-early-intervention", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));

        ApiException early = assertThrows(ApiException.class, () -> as(ORG_A, () ->
                service.recordIntervention(clinician(ORG_A), accepted.id(),
                        new InterventionRequest("manual therapy", "2026-11-20T10:00:00Z"),
                        accepted.version())));
        assertEquals(409, early.status());
        assertEquals("INVALID_TRANSITION", early.code());
        TherapyAggregate unchanged = as(ORG_A, () -> store.find(ORG_A, accepted.id()).orElseThrow());
        assertEquals(EpisodeState.ACTIVE, unchanged.episode().state());
        assertEquals(0, unchanged.interventions().size());
    }

    @Test
    void refusesEvaluationAfterDischarge() {
        Episode accepted = as(ORG_A, () -> service.acceptReferral(clinician(ORG_A),
                new ReferralRequest("referral-after-discharge", PATIENT, "referring-org", "referrer-a",
                        "2026-11-20T09:00:00Z", "knee pain")));
        String id = accepted.id();

        as(ORG_A, () -> {
            service.recordEvaluation(clinician(ORG_A), id, validEvaluation(), accepted.version());
            return null;
        });
        int sessionDraftVersion = versionOf(id);
        as(ORG_A, () -> {
            service.recordNote(clinician(ORG_A), id,
                    new NoteRequest(NoteKind.SESSION, "session", "2026-11-21T10:00:00Z", null),
                    sessionDraftVersion);
            return null;
        });
        int sessionSignVersion = versionOf(id);
        as(ORG_A, () -> {
            service.signNote(clinician(ORG_A), id, NoteKind.SESSION, 1, "I attest", sessionSignVersion);
            return null;
        });
        int dischargeDraftVersion = versionOf(id);
        as(ORG_A, () -> {
            service.recordNote(clinician(ORG_A), id,
                    new NoteRequest(NoteKind.DISCHARGE, "discharge", "2026-11-30T10:00:00Z", null),
                    dischargeDraftVersion);
            return null;
        });
        int dischargeSignVersion = versionOf(id);
        as(ORG_A, () -> {
            service.signNote(clinician(ORG_A), id, NoteKind.DISCHARGE, 1, "discharge attested",
                    dischargeSignVersion);
            return null;
        });

        assertEquals(EpisodeState.DISCHARGED,
                as(ORG_A, () -> store.find(ORG_A, id).orElseThrow()).episode().state());
        int dischargedVersion = versionOf(id);

        ApiException late = assertThrows(ApiException.class, () -> as(ORG_A, () ->
                service.recordEvaluation(clinician(ORG_A), id, validEvaluation(), dischargedVersion)));
        assertEquals(409, late.status());
        assertEquals("INVALID_TRANSITION", late.code());
    }
}
