package com.aurevia.it.therapy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.aurevia.therapy.TherapyContract.Episode;
import com.aurevia.therapy.TherapyContract.EpisodeState;
import com.aurevia.therapy.TherapyContract.EvaluationRequest;
import com.aurevia.therapy.TherapyContract.Goal;
import com.aurevia.therapy.TherapyContract.Measurement;
import com.aurevia.therapy.TherapyContract.NoteKind;
import com.aurevia.therapy.TherapyContract.NoteRequest;
import com.aurevia.therapy.TherapyContract.OutcomeRequest;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.ReturnAcknowledgment;
import com.aurevia.therapy.TherapyContract.ScaleRequest;
import com.aurevia.therapy.TherapyContract.Summary;
import com.aurevia.therapy.TherapyContract.SummaryRequest;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;
import com.aurevia.therapy.TherapyService;

/**
 * S-G4-02 branch-completeness acceptance. Every case is a synthetic negative or
 * boundary scenario reached through the public service entry point (or a direct
 * domain-state assertion), so no production branch remains uncovered.
 */
class TherapyCoverageTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private final Actor pt = Actor.of(ORG, "pt", TherapyService.PT_CLINICIAN);
    private final Actor admin = Actor.of(ORG, "admin", TherapyService.TENANT_ADMIN);
    private final Actor governor = Actor.of(ORG, "gov", TherapyService.CLINICAL_GOVERNANCE);
    private final Actor auditor = Actor.of(ORG, "aud", TherapyService.PRIVACY_AUDITOR);

    private TherapyService service;

    /** Build the service with a fixed clock. */
    @BeforeEach
    void setUp() {
        service = new TherapyService(new InMemoryTherapyStore(),
                Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));
    }

    private Episode accept(String referralId) {
        return service.acceptReferral(pt, new ReferralRequest(
                referralId, PATIENT, "org-ref", "ref", "2026-11-18T09:00:00Z", "q"));
    }

    private Episode evaluate(String referralId) {
        Episode episode = accept(referralId);
        service.recordEvaluation(pt, episode.id(), new EvaluationRequest(
                List.of("p"), List.of(new Measurement("ROM", "90", "degrees", "2026-11-19T09:00:00Z")),
                List.of(new Goal("g", "1", "2026-12-19")), 2, 6), episode.version());
        return service.get(pt, episode.id()).episode();
    }

    /** Exercise every pair of episode-state transitions. */
    @Test
    void onlyTheTwoForwardEpisodeTransitionsAreLegal() {
        assertTrue(EpisodeState.ACTIVE.canTransitionTo(EpisodeState.IN_TREATMENT));
        assertTrue(EpisodeState.IN_TREATMENT.canTransitionTo(EpisodeState.DISCHARGED));
        assertFalse(EpisodeState.ACTIVE.canTransitionTo(EpisodeState.ACTIVE));
        assertFalse(EpisodeState.ACTIVE.canTransitionTo(EpisodeState.DISCHARGED));
        assertFalse(EpisodeState.IN_TREATMENT.canTransitionTo(EpisodeState.ACTIVE));
        assertFalse(EpisodeState.IN_TREATMENT.canTransitionTo(EpisodeState.IN_TREATMENT));
        assertFalse(EpisodeState.DISCHARGED.canTransitionTo(EpisodeState.ACTIVE));
        assertFalse(EpisodeState.DISCHARGED.canTransitionTo(EpisodeState.IN_TREATMENT));
        assertFalse(EpisodeState.DISCHARGED.canTransitionTo(EpisodeState.DISCHARGED));
    }

    /** Null and malformed request members are rejected at the validation guards. */
    @Test
    void validationGuardsRejectNullAndBlankMembers() {
        Episode episode = accept("REF-1");

        assertEquals(422, status(() -> service.recordEvaluation(pt, episode.id(), null, 1)));
        assertEquals(422, status(() -> service.recordEvaluation(pt, episode.id(),
                new EvaluationRequest(null, List.of(), List.of(), 1, 1), 1)));
        assertEquals(422, status(() -> service.recordEvaluation(pt, episode.id(),
                new EvaluationRequest(List.of("p"), null, List.of(), 1, 1), 1)));
        assertEquals(422, status(() -> service.recordEvaluation(pt, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), null, 1, 1), 1)));
        assertEquals(422, status(() -> service.recordEvaluation(pt, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(), 1, 0), 1)));
        assertEquals(422, status(() -> service.recordEvaluation(pt, episode.id(),
                new EvaluationRequest(List.of("p"), List.of(), List.of(), 0, 1), 1)));
    }

    /** Command records must copy caller-owned lists yet preserve null for validation. */
    @Test
    void commandRecordsCopyListsAndPreserveNull() {
        List<String> problems = new ArrayList<>(List.of("p1"));
        List<Measurement> baselines = new ArrayList<>(List.of(
                new Measurement("ROM", "90", "degrees", "2026-11-19T09:00:00Z")));
        List<Goal> goals = new ArrayList<>(List.of(new Goal("g", "1", "2026-12-19")));
        EvaluationRequest request = new EvaluationRequest(problems, baselines, goals, 1, 1);
        problems.clear();
        baselines.clear();
        goals.clear();
        assertEquals(List.of("p1"), request.problems());
        assertEquals(1, request.baselines().size());
        assertEquals(1, request.goals().size());
        assertThrows(UnsupportedOperationException.class, () -> request.problems().add("x"));

        List<String> values = new ArrayList<>(List.of("0", "50"));
        ScaleRequest scaleRequest = new ScaleRequest("k", "d", "u", values);
        values.clear();
        assertEquals(2, scaleRequest.permittedValues().size());
        assertThrows(UnsupportedOperationException.class,
                () -> scaleRequest.permittedValues().add("x"));

        assertNull(new EvaluationRequest(null, List.of(), List.of(), 1, 1).problems());
        assertNull(new ScaleRequest("k", "d", "u", null).permittedValues());
    }

    /** A correct explicit patient binding is accepted. */
    @Test
    void correctExplicitPatientBindingIsAccepted() {
        Episode episode = accept("REF-1");
        service.recordEvaluation(pt, episode.id(), new EvaluationRequest(
                List.of("p"), List.of(), List.of(), 1, 1), PATIENT, episode.version());
        assertNotNull(service.get(pt, episode.id()).evaluation());
    }

    /** Two referrals in one tenant stay distinct and exercise referral lookup. */
    @Test
    void distinctReferralsInOneTenantRemainSeparate() {
        Episode first = accept("REF-1");
        Episode second = accept("REF-2");
        assertFalse(first.id().equals(second.id()));
        assertEquals("REF-1", service.get(pt, first.id()).episode().referralId());
        assertEquals("REF-2", service.get(pt, second.id()).episode().referralId());
    }

    /** A pending scale version must not bind a reading. */
    @Test
    void pendingScaleVersionCannotBindAReading() {
        Episode episode = evaluate("REF-1");
        adminDefine("koos", "points", List.of("0", "50"));
        assertEquals(422, status(() -> service.recordOutcome(pt, episode.id(),
                new OutcomeRequest("koos", 1, "50", "points", "2026-11-19T10:30:00Z"),
                episode.version())));
    }

    /** Note revisions are per kind and signing replaces only the exact revision. */
    @Test
    void multipleNotesOfOneKindGetSequentialVersionsAndSignIndependently() {
        Episode episode = evaluate("REF-1");
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "first", "2026-11-19T10:15:00Z", 1), episode.version());
        Episode reloaded = service.get(pt, episode.id()).episode();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "second", "2026-11-19T10:30:00Z", 1), reloaded.version());

        reloaded = service.get(pt, episode.id()).episode();
        service.signNote(pt, episode.id(), NoteKind.SESSION, 2, "attest second", reloaded.version());
        var notes = service.get(pt, episode.id()).notes();
        assertEquals(2, notes.size());
        assertFalse(notes.get(0).signed());
        assertTrue(notes.get(1).signed());
        assertEquals("second", notes.get(1).content());
    }

    /** A summary may only be disclosed to the verified referrer of the episode. */
    @Test
    void summaryRejectsAnUnverifiedRecipient() {
        Episode episode = discharge("REF-1");
        Episode current = service.get(pt, episode.id()).episode();
        assertEquals(409, status(() -> service.sendSummary(pt, episode.id(),
                new SummaryRequest("c", "wrong-org", "ref"), current.version())));
        assertEquals(409, status(() -> service.sendSummary(pt, episode.id(),
                new SummaryRequest("c", "org-ref", "wrong-subject"), current.version())));
        service.sendSummary(pt, episode.id(), new SummaryRequest("c", "org-ref", "ref"), current.version());
        assertNotNull(service.get(pt, episode.id()).summary());
    }

    /** Acknowledgment requires a sent summary and is recorded only once. */
    @Test
    void acknowledgmentRequiresASentSummaryAndIsRecordedOnce() {
        Episode pending = evaluate("REF-1");
        assertEquals(409, status(() -> service.acknowledgeReturn(pt, pending.id(),
                new AckRequest(1, "2026-12-20T08:00:00Z", "ref", "note"), pending.version())));

        Episode episode = discharge("REF-2");
        service.sendSummary(pt, episode.id(), new SummaryRequest("c", "org-ref", "ref"),
                service.get(pt, episode.id()).episode().version());
        Episode beforeAck = service.get(pt, episode.id()).episode();
        service.acknowledgeReturn(pt, episode.id(),
                new AckRequest(1, "2026-12-20T08:00:00Z", "ref", "note"), beforeAck.version());
        Episode afterAck = service.get(pt, episode.id()).episode();
        assertEquals(409, status(() -> service.acknowledgeReturn(pt, episode.id(),
                new AckRequest(1, "2026-12-21T08:00:00Z", "ref", "again"), afterAck.version())));
    }

    /** Unsigned discharge notes and length/blank guards are enforced. */
    @Test
    void unsignedDischargeAndTextBoundariesAreRejected() {
        Episode episode = evaluate("REF-1");
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.DISCHARGE, "d", "2026-12-19T10:00:00Z", 1), episode.version());
        Episode current = service.get(pt, episode.id()).episode();
        // No signed session, so the unsigned discharge also fails the prerequisite check below.
        assertEquals(409, status(() -> service.sendSummary(pt, episode.id(),
                new SummaryRequest("c", "org-ref", "ref"), current.version())));

        assertEquals(422, status(() -> service.acceptReferral(pt, new ReferralRequest(
                null, PATIENT, "org", "ref", "2026-11-18T09:00:00Z", "q"))));
        assertEquals(422, status(() -> service.acceptReferral(pt, new ReferralRequest(
                "x".repeat(200), PATIENT, "org", "ref", "2026-11-18T09:00:00Z", "q"))));
        assertEquals(422, status(() -> service.recordNote(pt, episode.id(),
                new NoteRequest(null, "c", "2026-11-19T10:00:00Z", 1), current.version())));
        assertEquals(422, status(() -> service.recordNote(pt, episode.id(), null, current.version())));
    }

    /** Outcome-scale definition rejects null and blank members. */
    @Test
    void scaleDefinitionRejectsNullAndBlankMembers() {
        assertEquals(422, status(() -> service.defineOutcomeScale(admin,
                new ScaleRequest("x", "d", "u", null))));
        assertEquals(422, status(() -> service.defineOutcomeScale(admin,
                new ScaleRequest("x", "d", "u", List.of(" ")))));
        assertTrue(service.versions(admin, "x").isEmpty());
    }

    /** A closed episode is read-only: a leftover draft cannot be signed after discharge. */
    @Test
    void closedEpisodeCannotSignALeftoverDraft() {
        Episode episode = evaluate("REF-1");
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "first", "2026-11-19T10:15:00Z", 1), episode.version());
        Episode current = service.get(pt, episode.id()).episode();
        service.signNote(pt, episode.id(), NoteKind.SESSION, 1, "attest", current.version());
        current = service.get(pt, episode.id()).episode();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "leftover", "2026-11-19T10:30:00Z", 1), current.version());
        current = service.get(pt, episode.id()).episode();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.DISCHARGE, "d", "2026-12-19T10:00:00Z", 1), current.version());
        current = service.get(pt, episode.id()).episode();
        service.signNote(pt, episode.id(), NoteKind.DISCHARGE, 1, "attest", current.version());

        Episode closed = service.get(pt, episode.id()).episode();
        assertEquals(EpisodeState.DISCHARGED, closed.state());
        assertEquals(409, status(() -> service.signNote(pt, episode.id(),
                NoteKind.SESSION, 2, "attest", closed.version())));
        assertEquals(closed.version(), service.get(pt, episode.id()).episode().version());
    }

    /** A replacement summary reopens acknowledgment and increments the summary revision. */
    @Test
    void replacementSummaryClearsPriorAcknowledgment() {
        Episode episode = discharge("REF-1");
        service.sendSummary(pt, episode.id(), new SummaryRequest("original", "org-ref", "ref"),
                service.get(pt, episode.id()).episode().version());
        Episode current = service.get(pt, episode.id()).episode();
        service.acknowledgeReturn(pt, episode.id(),
                new AckRequest(1, "2026-12-20T08:00:00Z", "ref", "received"), current.version());
        assertNotNull(service.get(pt, episode.id()).acknowledgment());

        current = service.get(pt, episode.id()).episode();
        Summary replacement = service.sendSummary(pt, episode.id(),
                new SummaryRequest("revised", "org-ref", "ref"), current.version());
        assertEquals(2, replacement.version());
        assertEquals(null, service.get(pt, episode.id()).acknowledgment());

        current = service.get(pt, episode.id()).episode();
        ReturnAcknowledgment renewed = service.acknowledgeReturn(pt, episode.id(),
                new AckRequest(2, "2026-12-21T08:00:00Z", "ref", "received revised"), current.version());
        assertEquals(2, renewed.summaryVersion());
    }

    /** Acknowledgment must name the verified referrer, not arbitrary free text. */
    @Test
    void acknowledgmentMustNameTheVerifiedReferrer() {
        Episode episode = discharge("REF-1");
        service.sendSummary(pt, episode.id(), new SummaryRequest("c", "org-ref", "ref"),
                service.get(pt, episode.id()).episode().version());
        Episode current = service.get(pt, episode.id()).episode();
        assertEquals(422, status(() -> service.acknowledgeReturn(pt, episode.id(),
                new AckRequest(1, "2026-12-20T08:00:00Z", "someone-else", "note"), current.version())));
    }

    /** State, version and patient guard failures are written to the append-only audit. */
    @Test
    void guardFailuresAreAudited() {
        Episode episode = evaluate("REF-1");
        NoteRequest draft = new NoteRequest(NoteKind.SESSION, "s", "2026-11-19T10:00:00Z", 1);
        assertEquals(412, status(() -> service.recordNote(pt, episode.id(), draft, 1)));
        assertEquals(422, status(() -> service.recordNote(pt, episode.id(), draft,
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", episode.version())));

        Episode active = accept("REF-2");
        assertEquals(409, status(() -> service.recordOutcome(pt, active.id(),
                new OutcomeRequest("koos", 1, "50", "points", "2026-11-19T10:30:00Z"),
                active.version())));

        List<TherapyAuditEvent> trail = service.auditTrail(auditor);
        assertTrue(trail.stream().anyMatch(e -> "NOTE_RECORD".equals(e.action())
                && "CONFLICT".equals(e.outcome())));
        assertTrue(trail.stream().anyMatch(e -> "NOTE_RECORD".equals(e.action())
                && "INVALID".equals(e.outcome())));
        assertTrue(trail.stream().anyMatch(e -> "OUTCOME_RECORD".equals(e.action())
                && "CONFLICT".equals(e.outcome())));
    }

    /** Build a discharged episode with a signed session and discharge note. */
    private Episode discharge(String referralId) {
        Episode episode = evaluate(referralId);
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "s", "2026-11-19T10:00:00Z", 1), episode.version());
        Episode current = service.get(pt, episode.id()).episode();
        service.signNote(pt, episode.id(), NoteKind.SESSION, 1, "attest", current.version());
        current = service.get(pt, episode.id()).episode();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.DISCHARGE, "d", "2026-12-19T10:00:00Z", 1), current.version());
        current = service.get(pt, episode.id()).episode();
        service.signNote(pt, episode.id(), NoteKind.DISCHARGE, 1, "attest", current.version());
        return service.get(pt, episode.id()).episode();
    }

    /** Define a pending scale version with a synthetic key. */
    private void adminDefine(String key, String unit, List<String> values) {
        service.defineOutcomeScale(admin, new ScaleRequest(key, "d", unit, values));
    }

    /** Extract the HTTP status of an expected domain failure. */
    private static int status(ThrowingRunnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertNotNull(exception.code());
        assertTrue(exception.status() >= 400);
        return exception.status();
    }

    /** Allow lambdas that throw to be passed to {@link #status(ThrowingRunnable)}. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
