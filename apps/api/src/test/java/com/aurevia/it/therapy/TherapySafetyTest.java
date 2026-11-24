package com.aurevia.it.therapy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.therapy.InMemoryTherapyStore;
import com.aurevia.therapy.TherapyContract.AckRequest;
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
import com.aurevia.therapy.TherapyContract.SummaryRequest;
import com.aurevia.therapy.TherapyService;

/**
 * S-G4-02 negative acceptance for PT-01: authorization, wrong-patient binding,
 * state and version preconditions, signed-note immutability, tenant isolation,
 * summary approval and revision-bound acknowledgment. All data is synthetic.
 */
class TherapySafetyTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String PATIENT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");

    private final Actor ptA = Actor.of(ORG_A, "pt-a", TherapyService.PT_CLINICIAN);
    private final Actor noRolesA = Actor.of(ORG_A, "none-a");
    private final Actor ptB = Actor.of(ORG_B, "pt-b", TherapyService.PT_CLINICIAN);

    private TherapyService service;

    /** Build the service with a fixed clock. */
    @BeforeEach
    void setUp() {
        service = new TherapyService(new InMemoryTherapyStore(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Episode accept(Actor actor, String referralId, String patient) {
        return service.acceptReferral(actor, new ReferralRequest(
                referralId, patient, "org-ref", "ref", "2026-11-18T09:00:00Z", "question"));
    }

    private Episode acceptAndEvaluate() {
        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        service.recordEvaluation(ptA, episode.id(), new EvaluationRequest(
                List.of("problem"), List.of(new Measurement("ROM", "90", "degrees", "2026-11-19T09:00:00Z")),
                List.of(new Goal("goal", "1", "2026-12-19")), 2, 6), episode.version());
        return service.get(ptA, episode.id()).episode();
    }

    @Test
    void onlyAuthorizedRolesMayAcceptAndMutate() {
        assertEquals(403, status(() -> accept(noRolesA, "REF-X", PATIENT_A)));
        assertEquals(403, status(() -> service.auditTrail(ptA)));
        assertEquals(403, status(() -> service.defineOutcomeScale(ptA,
                new com.aurevia.therapy.TherapyContract.ScaleRequest(
                        "s", "d", "u", List.of("1")))));
        Episode episode = acceptAndEvaluate();
        assertEquals(403, status(() -> service.recordEvaluation(noRolesA, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(), 1, 1), episode.version())));
    }

    @Test
    void wrongActiveRoleCannotReadClinicalAggregateOrScales() {
        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        assertEquals(403, status(() -> service.get(Actor.of(ORG_A, "fin", "finance"), episode.id())));
        assertEquals(403, status(() -> service.get(
                Actor.of(ORG_A, "rec", "reception"), episode.id())));
        assertEquals(403, status(() -> service.versions(
                Actor.of(ORG_A, "rec", "reception"), "koos")));
        // Legitimate clinical and governance readers still succeed.
        assertNotNull(service.get(ptA, episode.id()));
        assertNotNull(service.versions(Actor.of(ORG_A, "admin", TherapyService.TENANT_ADMIN), "koos"));
    }

    @Test
    void wrongPatientIsRejectedAtEveryPatientBearingCommand() {
        Episode episode = acceptAndEvaluate();
        int version = episode.version();

        assertEquals(422, status(() -> service.recordEvaluation(ptA, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(), 1, 1), PATIENT_B, version)));
        assertEquals(422, status(() -> service.recordNote(ptA, episode.id(),
                new NoteRequest(NoteKind.SESSION, "c", "2026-11-19T10:00:00Z", 1), PATIENT_B, version)));
        assertEquals(422, status(() -> service.recordOutcome(ptA, episode.id(),
                new com.aurevia.therapy.TherapyContract.OutcomeRequest(
                        "s", 1, "1", "u", "2026-11-19T10:00:00Z"), PATIENT_B, version)));
    }

    @Test
    void staleVersionIsRejectedWithPreconditionFailed() {
        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        service.recordEvaluation(ptA, episode.id(), new EvaluationRequest(
                List.of("p"), List.of(), List.of(), 1, 1), episode.version());

        assertEquals(412, status(() -> service.recordNote(ptA, episode.id(),
                new NoteRequest(NoteKind.SESSION, "c", "2026-11-19T10:00:00Z", 1), episode.version())));
    }

    @Test
    void unknownEpisodeAndCrossTenantEpisodeAreConcealed() {
        assertEquals(404, status(() -> service.get(ptA, "missing")));
        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        assertEquals(404, status(() -> service.get(ptB, episode.id())));
        assertEquals(404, status(() -> service.recordEvaluation(ptB, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(), 1, 1), 1)));
    }

    @Test
    void identicalLocalIdentifiersInTwoTenantsStayIsolated() {
        // Both tenants use the same local referral id and patient id value where legal.
        Episode a = accept(ptA, "REF-SAME", PATIENT_A);
        Episode b = accept(ptB, "REF-SAME", PATIENT_B);
        assertFalse(a.id().equals(b.id()));

        service.recordEvaluation(ptA, a.id(), new EvaluationRequest(
                List.of("problem-A"), List.of(), List.of(), 2, 6), a.version());

        assertEquals("problem-A", service.get(ptA, a.id()).evaluation().problems().get(0));
        assertEquals(null, service.get(ptB, b.id()).evaluation());
        assertEquals(404, status(() -> service.get(ptB, a.id())));
        assertEquals(404, status(() -> service.get(ptA, b.id())));

        assertTrue(service.auditTrail(Actor.of(ORG_A, "aud", TherapyService.PRIVACY_AUDITOR))
                .stream().allMatch(e -> ORG_A.equals(e.organizationId())));
        assertTrue(service.auditTrail(Actor.of(ORG_B, "aud", TherapyService.PRIVACY_AUDITOR))
                .stream().allMatch(e -> ORG_B.equals(e.organizationId())));
    }

    @Test
    void signedNoteChecksumIsSha256() {
        Episode episode = acceptAndEvaluate();
        int version = service.get(ptA, episode.id()).episode().version();
        service.recordNote(ptA, episode.id(), new NoteRequest(
                NoteKind.SESSION, "session", "2026-11-19T10:00:00Z", 1), version);
        version = service.get(ptA, episode.id()).episode().version();
        ClinicalNote signed = service.signNote(ptA, episode.id(), NoteKind.SESSION, 1,
                "attest", version);
        assertTrue(signed.contentChecksum().matches("[0-9a-f]{64}"),
                "signed-note checksum must be a SHA-256 hex digest");
    }

    @Test
    void signedNotesAreImmutableAndCannotBeSignedTwice() {
        Episode episode = acceptAndEvaluate();
        int version = service.get(ptA, episode.id()).episode().version();
        service.recordNote(ptA, episode.id(), new NoteRequest(
                NoteKind.SESSION, "session", "2026-11-19T10:00:00Z", 1), version);

        version = service.get(ptA, episode.id()).episode().version();
        ClinicalNote signed = service.signNote(ptA, episode.id(), NoteKind.SESSION, 1, "attest", version);
        String checksum = signed.contentChecksum();

        assertEquals(409, status(() -> service.signNote(ptA, episode.id(), NoteKind.SESSION, 1,
                "attest again", service.get(ptA, episode.id()).episode().version())));
        ClinicalNote unchanged = service.get(ptA, episode.id()).notes().get(0);
        assertTrue(unchanged.signed());
        assertEquals(checksum, unchanged.contentChecksum());
        assertEquals("attest", unchanged.attestation());
    }

    @Test
    void dischargeRequiresSignedEvaluationAndASignedSession() {
        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        // No evaluation at all: discharge note cannot be signed.
        int version = episode.version();
        service.recordNote(ptA, episode.id(), new NoteRequest(
                NoteKind.DISCHARGE, "d", "2026-12-19T10:00:00Z", 1), version);
        assertEquals(409, status(() -> service.signNote(ptA, episode.id(), NoteKind.DISCHARGE, 1,
                "attest", service.get(ptA, episode.id()).episode().version())));

        // Evaluation present but session still unsigned.
        service.recordEvaluation(ptA, episode.id(), new EvaluationRequest(
                List.of("p"), List.of(), List.of(), 1, 1),
                service.get(ptA, episode.id()).episode().version());
        version = service.get(ptA, episode.id()).episode().version();
        service.recordNote(ptA, episode.id(), new NoteRequest(
                NoteKind.SESSION, "s", "2026-11-19T10:00:00Z", 1), version);
        assertEquals(409, status(() -> service.signNote(ptA, episode.id(), NoteKind.DISCHARGE, 1,
                "attest", service.get(ptA, episode.id()).episode().version())));
    }

    @Test
    void summaryRequiresSignedDischargeAndApprovalBeforeSend() {
        Episode episode = acceptAndEvaluate();
        int version = service.get(ptA, episode.id()).episode().version();
        assertEquals(409, status(() -> service.sendSummary(ptA, episode.id(),
                new SummaryRequest("c", "org-ref", "ref"), version)));
    }

    @Test
    void returnAcknowledgmentMustBindTheExactSentSummaryRevision() {
        Episode episode = dischargeFully();
        int version = service.get(ptA, episode.id()).episode().version();
        assertEquals(409, status(() -> service.acknowledgeReturn(ptA, episode.id(),
                new AckRequest(99, "2026-12-20T08:00:00Z", "ref", "note"), version)));
        assertEquals(null, service.get(ptA, episode.id()).acknowledgment());
    }

    private Episode dischargeFully() {
        Episode episode = acceptAndEvaluate();
        int version = service.get(ptA, episode.id()).episode().version();
        service.recordNote(ptA, episode.id(), new NoteRequest(
                NoteKind.SESSION, "s", "2026-11-19T10:00:00Z", 1), version);
        version = service.get(ptA, episode.id()).episode().version();
        service.signNote(ptA, episode.id(), NoteKind.SESSION, 1, "attest", version);
        version = service.get(ptA, episode.id()).episode().version();
        service.recordNote(ptA, episode.id(), new NoteRequest(
                NoteKind.DISCHARGE, "d", "2026-12-19T10:00:00Z", 1), version);
        version = service.get(ptA, episode.id()).episode().version();
        service.signNote(ptA, episode.id(), NoteKind.DISCHARGE, 1, "attest", version);
        version = service.get(ptA, episode.id()).episode().version();
        service.sendSummary(Actor.of(ORG_A, "coord", TherapyService.PT_COORDINATOR), episode.id(),
                new SummaryRequest("c", "org-ref", "ref"), version);
        return service.get(ptA, episode.id()).episode();
    }

    @Test
    void invalidInputsAreRejected() {
        assertEquals(422, status(() -> service.acceptReferral(ptA, new ReferralRequest(
                " ", PATIENT_A, "org", "ref", "2026-11-18T09:00:00Z", "q"))));
        assertEquals(422, status(() -> service.acceptReferral(ptA, new ReferralRequest(
                "REF", "not-a-uuid", "org", "ref", "2026-11-18T09:00:00Z", "q"))));

        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        assertEquals(422, status(() -> service.recordEvaluation(ptA, episode.id(),
                new EvaluationRequest(List.of(), List.of(), List.of(), 0, 1), episode.version())));
        assertEquals(422, status(() -> service.recordEvaluation(ptA, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(new Measurement(
                        "ROM", "90", " ", "2026-11-19T09:00:00Z")), List.of(), 1, 1), episode.version())));
        assertEquals(422, status(() -> service.recordEvaluation(ptA, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(
                        new Goal(" ", "1", "2026-12-19")), 1, 1), episode.version())));
        assertEquals(422, status(() -> service.recordEvaluation(ptA, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(), 1, 0), episode.version())));

        service.recordEvaluation(ptA, episode.id(), new EvaluationRequest(
                List.of("p"), List.of(), List.of(), 1, 1), episode.version());
        int version = service.get(ptA, episode.id()).episode().version();
        assertEquals(422, status(() -> service.recordNote(ptA, episode.id(), new NoteRequest(
                NoteKind.SESSION, " ", "2026-11-19T10:00:00Z", 1), version)));
        assertEquals(422, status(() -> service.signNote(ptA, episode.id(), NoteKind.SESSION, 1,
                " ", service.get(ptA, episode.id()).episode().version())));
        assertEquals(404, status(() -> service.signNote(ptA, episode.id(), NoteKind.SESSION, 9,
                "attest", service.get(ptA, episode.id()).episode().version())));
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        List<String> problems = new ArrayList<>(List.of("p"));
        List<Measurement> baselines = new ArrayList<>(List.of(
                new Measurement("ROM", "90", "degrees", "2026-11-19T09:00:00Z")));
        List<Goal> goals = new ArrayList<>(List.of(new Goal("g", "1", "2026-12-19")));
        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        service.recordEvaluation(ptA, episode.id(), new EvaluationRequest(
                problems, baselines, goals, 1, 1), episode.version());
        problems.clear();
        baselines.clear();
        goals.clear();
        assertEquals(1, service.get(ptA, episode.id()).evaluation().problems().size());
        assertThrows(UnsupportedOperationException.class,
                () -> service.get(ptA, episode.id()).evaluation().goals().clear());
    }

    @Test
    void duplicateReferralAndDuplicateEvaluationAreRejected() {
        Episode episode = accept(ptA, "REF-1", PATIENT_A);
        assertEquals(409, status(() -> accept(ptA, "REF-1", PATIENT_A)));
        service.recordEvaluation(ptA, episode.id(), new EvaluationRequest(
                List.of("p"), List.of(), List.of(), 1, 1), episode.version());
        assertEquals(409, status(() -> service.recordEvaluation(ptA, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(), 1, 1),
                service.get(ptA, episode.id()).episode().version())));
    }

    /** Extract the HTTP status of an expected domain failure. */
    private static int status(ThrowingRunnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertNotNull(exception.code());
        assertFalse(exception.getMessage().isBlank());
        return exception.status();
    }

    /** Allow lambdas that throw to be passed to {@link #status(ThrowingRunnable)}. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
