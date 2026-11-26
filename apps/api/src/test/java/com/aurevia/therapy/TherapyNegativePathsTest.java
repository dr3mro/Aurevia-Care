package com.aurevia.therapy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.therapy.TherapyContract.Actor;
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.NoteRequest;
import com.aurevia.therapy.TherapyContract.ReferralRequest;

/**
 * Negative-path and tenant-isolation coverage for the PT-01 therapy domain service (START-65).
 *
 * <p>All tests use the synthetic {@link InMemoryTherapyStore} and exercise
 * {@link TherapyService} directly. No production code, existing tests, contracts
 * or migrations are changed. Only behaviours that the production code has today
 * are asserted.</p>
 *
 * <p>Coverage: missing / too-long / malformed input; callers with the wrong role;
 * an identifier that belongs to another tenant (the response must look like NOT_FOUND,
 * never expose the other tenant's record); a stale optimistic-lock version; and a
 * repeated request carrying the same referral idempotency key.</p>
 */
class TherapyNegativePathsTest {

    // ── fixed synthetic identifiers ──────────────────────────────────────────
    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "pt-clinician-a";
    private static final String PATIENT = "33333333-3333-3333-3333-333333333333";
    private static final String REFERRAL = "referral-neg-1";

    // ── minimal valid referral fields ─────────────────────────────────────────
    private static final String REFERRER_ORG = "ref-org";
    private static final String REFERRER_SUBJECT = "referrer-a";
    private static final String RECEIVED_AT = "2026-11-20T09:00:00Z";
    private static final String QUESTION = "knee pain assessment";

    // ── actor helpers ─────────────────────────────────────────────────────────
    /** Clinician in ORG_A — allowed for all PT-01 write operations. */
    private static final Actor CLINICIAN_A = new Actor(ORG_A, SUBJECT, Set.of("pt_clinician"));
    /** Clinician in ORG_B — same local subject but a different tenant. */
    private static final Actor CLINICIAN_B = new Actor(ORG_B, SUBJECT, Set.of("pt_clinician"));
    /** An actor whose role set does not grant any PT-01 write permission. */
    private static final Actor NO_ROLE_A = new Actor(ORG_A, SUBJECT, Set.of());
    /** PT coordinator — allowed to accept and read, but not to record evaluations. */
    private static final Actor COORDINATOR_A = new Actor(ORG_A, SUBJECT, Set.of("pt_coordinator"));
    /** Tenant admin — allowed for scale administration, not for clinical episode reads. */
    private static final Actor TENANT_ADMIN_A = new Actor(ORG_A, SUBJECT, Set.of("tenant_admin"));

    private TherapyService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        service = new TherapyService(new InMemoryTherapyStore(), clock);
    }

    // ── acceptReferral: input validation ─────────────────────────────────────

    @Test
    void acceptReferral_nullReferralIdIsRejected() {
        ReferralRequest request = new ReferralRequest(null, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(CLINICIAN_A, request));
        assertEquals(422, ex.status());
    }

    @Test
    void acceptReferral_blankReferralIdIsRejected() {
        ReferralRequest request = new ReferralRequest("   ", PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(CLINICIAN_A, request));
        assertEquals(422, ex.status());
    }

    @Test
    void acceptReferral_tooLongReferralIdIsRejected() {
        String tooLong = "x".repeat(129);
        ReferralRequest request = new ReferralRequest(tooLong, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(CLINICIAN_A, request));
        assertEquals(422, ex.status());
    }

    @Test
    void acceptReferral_nullPatientIdIsRejected() {
        ReferralRequest request = new ReferralRequest(REFERRAL, null, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(CLINICIAN_A, request));
        assertEquals(422, ex.status());
    }

    @Test
    void acceptReferral_malformedPatientIdIsRejected() {
        ReferralRequest request = new ReferralRequest(REFERRAL, "not-a-uuid", REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(CLINICIAN_A, request));
        assertEquals(422, ex.status());
    }

    // ── acceptReferral: role guard ────────────────────────────────────────────

    @Test
    void acceptReferral_noRoleIsForbidden() {
        ReferralRequest request = new ReferralRequest(REFERRAL, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(NO_ROLE_A, request));
        assertEquals(403, ex.status());
        assertEquals("FORBIDDEN", ex.code());
    }

    @Test
    void acceptReferral_tenantAdminIsForbidden() {
        ReferralRequest request = new ReferralRequest(REFERRAL, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(TENANT_ADMIN_A, request));
        assertEquals(403, ex.status());
    }

    // ── acceptReferral: duplicate (idempotency key) ───────────────────────────

    @Test
    void acceptReferral_duplicateReferralIdIsConflict() {
        ReferralRequest request = new ReferralRequest(REFERRAL, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        service.acceptReferral(CLINICIAN_A, request);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptReferral(CLINICIAN_A, request));
        assertEquals(409, ex.status());
        assertEquals("CONFLICT", ex.code());
    }

    @Test
    void acceptReferral_sameReferralIdInDifferentTenantSucceeds() {
        // The same referral token must NOT trigger a conflict when used in a separate tenant.
        ReferralRequest request = new ReferralRequest(REFERRAL, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        service.acceptReferral(CLINICIAN_A, request);
        Episode episode = service.acceptReferral(CLINICIAN_B, request);
        assertEquals(ORG_B, episode.organizationId());
    }

    // ── tenant isolation ──────────────────────────────────────────────────────

    @Test
    void getEpisode_otherTenantActorSeesNotFound() {
        // Episode belongs to ORG_A; actor from ORG_B must receive NOT_FOUND.
        ReferralRequest request = new ReferralRequest(REFERRAL, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        Episode episode = service.acceptReferral(CLINICIAN_A, request);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.get(CLINICIAN_B, episode.id()));
        assertEquals(404, ex.status());
        assertEquals("NOT_FOUND", ex.code());
    }

    @Test
    void getEpisode_tenantAdminFromSameTenantIsForbidden() {
        // tenant_admin does not hold the READ_EPISODE role; must receive 403, not leak the record.
        ReferralRequest request = new ReferralRequest(REFERRAL, PATIENT, REFERRER_ORG,
                REFERRER_SUBJECT, RECEIVED_AT, QUESTION);
        Episode episode = service.acceptReferral(CLINICIAN_A, request);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.get(TENANT_ADMIN_A, episode.id()));
        assertEquals(403, ex.status());
    }

    // ── recordEvaluation: input validation ───────────────────────────────────

    @Test
    void recordEvaluation_emptyProblemsIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        EvaluationRequest request = new EvaluationRequest(List.of(), List.of(), List.of(), 3, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordEvaluation(CLINICIAN_A, episode.id(), request, 1));
        assertEquals(422, ex.status());
    }

    @Test
    void recordEvaluation_zeroFrequencyIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        EvaluationRequest request = new EvaluationRequest(
                List.of("limited ROM"),
                List.of(new Measurement("FLEX", "90", "deg", RECEIVED_AT)),
                List.of(new Goal("restore ROM", "120 deg", null)),
                0, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordEvaluation(CLINICIAN_A, episode.id(), request, 1));
        assertEquals(422, ex.status());
    }

    @Test
    void recordEvaluation_zeroDurationIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        EvaluationRequest request = new EvaluationRequest(
                List.of("limited ROM"),
                List.of(new Measurement("FLEX", "90", "deg", RECEIVED_AT)),
                List.of(new Goal("restore ROM", "120 deg", null)),
                3, 0);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordEvaluation(CLINICIAN_A, episode.id(), request, 1));
        assertEquals(422, ex.status());
    }

    // ── recordEvaluation: role guard ─────────────────────────────────────────

    @Test
    void recordEvaluation_coordinatorIsForbidden() {
        // pt_coordinator is not allowed to record evaluations (pt_clinician only).
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        EvaluationRequest request = new EvaluationRequest(
                List.of("limited ROM"),
                List.of(new Measurement("FLEX", "90", "deg", RECEIVED_AT)),
                List.of(new Goal("restore ROM", "120 deg", null)),
                3, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordEvaluation(COORDINATOR_A, episode.id(), request, 1));
        assertEquals(403, ex.status());
    }

    // ── recordEvaluation: stale version ──────────────────────────────────────

    @Test
    void recordEvaluation_staleVersionIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        // Episode starts at version 1; passing 0 or 2 must be rejected.
        EvaluationRequest request = new EvaluationRequest(
                List.of("limited ROM"),
                List.of(new Measurement("FLEX", "90", "deg", RECEIVED_AT)),
                List.of(new Goal("restore ROM", "120 deg", null)),
                3, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordEvaluation(CLINICIAN_A, episode.id(), request, 0));
        assertEquals(412, ex.status());
        assertEquals("STALE_VERSION", ex.code());
    }

    @Test
    void recordEvaluation_aheadVersionIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        EvaluationRequest request = new EvaluationRequest(
                List.of("limited ROM"),
                List.of(new Measurement("FLEX", "90", "deg", RECEIVED_AT)),
                List.of(new Goal("restore ROM", "120 deg", null)),
                3, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordEvaluation(CLINICIAN_A, episode.id(), request, 2));
        assertEquals(412, ex.status());
    }

    // ── recordNote: input validation ─────────────────────────────────────────

    @Test
    void recordNote_nullKindIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        NoteRequest request = new NoteRequest(null, "session content", RECEIVED_AT, null);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordNote(CLINICIAN_A, episode.id(), request, 1));
        assertEquals(422, ex.status());
    }

    @Test
    void recordNote_blankContentIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        NoteRequest request = new NoteRequest(NoteKind.SESSION, "   ", RECEIVED_AT, null);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordNote(CLINICIAN_A, episode.id(), request, 1));
        assertEquals(422, ex.status());
    }

    // ── get on unknown episode ────────────────────────────────────────────────

    @Test
    void getEpisode_unknownIdIsNotFound() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.get(CLINICIAN_A, "00000000-0000-0000-0000-000000000000"));
        assertEquals(404, ex.status());
        assertEquals("NOT_FOUND", ex.code());
    }

    // ── patient-mismatch guard ────────────────────────────────────────────────

    @Test
    void recordEvaluation_wrongPatientIsRejected() {
        Episode episode = acceptInOrg(CLINICIAN_A, REFERRAL);
        String differentPatient = "44444444-4444-4444-4444-444444444444";
        EvaluationRequest request = new EvaluationRequest(
                List.of("limited ROM"),
                List.of(new Measurement("FLEX", "90", "deg", RECEIVED_AT)),
                List.of(new Goal("restore ROM", "120 deg", null)),
                3, 6);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.recordEvaluation(CLINICIAN_A, episode.id(), request,
                        differentPatient, 1));
        assertEquals(422, ex.status());
        assertEquals("PATIENT_MISMATCH", ex.code());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Accept one referral in the given actor's tenant using synthetic data.
     *
     * @param actor      actor whose tenant owns the new episode
     * @param referralId idempotency key for the referral
     * @return the created episode
     */
    private Episode acceptInOrg(Actor actor, String referralId) {
        return service.acceptReferral(actor, new ReferralRequest(
                referralId, PATIENT, REFERRER_ORG, REFERRER_SUBJECT, RECEIVED_AT, QUESTION));
    }
}
