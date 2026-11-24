package com.aurevia.it.therapy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.therapy.InMemoryTherapyStore;
import com.aurevia.therapy.TherapyContract.TherapyAggregate;
import com.aurevia.therapy.TherapyContract.AckRequest;
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
import com.aurevia.therapy.TherapyContract.OutcomeScale;
import com.aurevia.therapy.TherapyContract.ReferralRequest;
import com.aurevia.therapy.TherapyContract.ReturnAcknowledgment;
import com.aurevia.therapy.TherapyContract.ScaleRequest;
import com.aurevia.therapy.TherapyContract.Summary;
import com.aurevia.therapy.TherapyContract.SummaryRequest;
import com.aurevia.therapy.TherapyContract.TherapyAuditEvent;
import com.aurevia.therapy.TherapyService;

/**
 * S-G4-02 happy-path acceptance for PT-01: a synthetic patient is accepted into
 * a therapy episode, evaluated, measured, treated, documented with signed notes,
 * discharged and summarised to the referrer with a revision-bound return
 * acknowledgment. All fixtures are synthetic and the module is exercised only
 * through its public domain-service entry point.
 */
class TherapyEpisodeFlowTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private final Actor pt = Actor.of(ORG, "pt-1", TherapyService.PT_CLINICIAN);
    private final Actor coordinator = Actor.of(ORG, "coord-1", TherapyService.PT_COORDINATOR);
    private final Actor auditor = Actor.of(ORG, "aud-1", TherapyService.PRIVACY_AUDITOR);

    private TherapyService service;

    /** Build the service with a fixed clock so system timestamps are deterministic. */
    @BeforeEach
    void setUp() {
        service = new TherapyService(new InMemoryTherapyStore(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Accept a referral and read back the resulting active episode. */
    private Episode accept() {
        return service.acceptReferral(pt, new ReferralRequest(
                "REF-1", PATIENT, "org-ref", "ref-clinician", "2026-11-18T09:00:00Z", "knee OA rehab"));
    }

    /** Record the baseline evaluation used by most flows. */
    private Evaluation evaluate(Episode episode) {
        return service.recordEvaluation(pt, episode.id(), new EvaluationRequest(
                        List.of("knee OA", "reduced ROM"),
                        List.of(new Measurement("ROM", "95", "degrees", "2026-11-19T09:30:00Z")),
                        List.of(new Goal("walk 500 m", "500", "2026-12-19")),
                        2, 6),
                episode.version());
    }

    @Test
    void acceptsReferralIntoAnActiveOwnedEpisode() {
        Episode episode = accept();

        assertEquals(ORG, episode.organizationId());
        assertEquals(PATIENT, episode.patientId());
        assertEquals("REF-1", episode.referralId());
        assertEquals("org-ref", episode.referrerOrg());
        assertEquals("ref-clinician", episode.referrerSubject());
        assertEquals(EpisodeState.ACTIVE, episode.state());
        assertEquals(1, episode.version());
        assertEquals(NOW, episode.createdAt());
        assertEquals("2026-11-18T09:00:00Z", episode.receivedAt());
        assertEquals("knee OA rehab", episode.clinicalQuestion());

        TherapyAggregate view = service.get(pt, episode.id());
        assertEquals(episode, view.episode());
        assertTrue(view.notes().isEmpty());
        assertTrue(view.readings().isEmpty());
        assertTrue(view.interventions().isEmpty());
        assertEquals(null, view.evaluation());
        assertEquals(null, view.summary());
        assertEquals(null, view.acknowledgment());
    }

    @Test
    void recordsEvaluationWithProblemsBaselinesGoalsAndPlan() {
        Episode episode = accept();
        Evaluation evaluation = evaluate(episode);

        assertEquals(episode.id(), evaluation.episodeId());
        assertEquals(PATIENT, evaluation.patientId());
        assertEquals(List.of("knee OA", "reduced ROM"), evaluation.problems());
        assertEquals(1, evaluation.baselines().size());
        assertEquals("degrees", evaluation.baselines().get(0).unit());
        assertEquals(List.of("500"), List.of(evaluation.goals().get(0).target()));
        assertEquals(2, evaluation.frequencyPerWeek());
        assertEquals(6, evaluation.durationWeeks());
        assertEquals("pt-1", evaluation.author());
        assertEquals(1, evaluation.version());

        TherapyAggregate view = service.get(pt, episode.id());
        assertEquals(evaluation, view.evaluation());
        assertEquals(2, view.episode().version());
    }

    @Test
    void definesAndApprovesAVersionedOutcomeScaleThenRecordsAUnitBoundReading() {
        Episode episode = accept();
        evaluate(episode);
        OutcomeScale scale = service.defineOutcomeScale(
                Actor.of(ORG, "admin-1", TherapyService.TENANT_ADMIN),
                new ScaleRequest("koos", "Knee outcome score", "points", List.of("0", "25", "50", "100")));
        assertFalse(scale.approved());
        assertEquals(1, scale.version());

        OutcomeScale approved = service.approveOutcomeScale(
                Actor.of(ORG, "gov-1", TherapyService.CLINICAL_GOVERNANCE), "koos", 1);
        assertTrue(approved.approved());
        assertEquals("gov-1", approved.approvedBy());

        int version = service.get(pt, episode.id()).episode().version();
        OutcomeReading reading = service.recordOutcome(pt, episode.id(), new OutcomeRequest(
                "koos", 1, "50", "points", "2026-11-19T10:30:00Z"), version);

        assertEquals("koos", reading.scaleKey());
        assertEquals(1, reading.scaleVersion());
        assertEquals("50", reading.value());
        assertEquals("points", reading.unit());
        assertEquals("2026-11-19T10:30:00Z", reading.observedAt());
        assertEquals(List.of(reading), service.get(pt, episode.id()).readings());
    }

    @Test
    void recordsInterventionAndSignsSessionAndProgressNotes() {
        Episode episode = accept();
        evaluate(episode);

        int version = service.get(pt, episode.id()).episode().version();
        Intervention intervention = service.recordIntervention(pt, episode.id(),
                new InterventionRequest("manual therapy", "2026-11-19T10:30:00Z"), version);
        assertEquals("manual therapy", intervention.description());
        assertEquals("pt-1", intervention.author());
        assertEquals(List.of(intervention), service.get(pt, episode.id()).interventions());

        version = service.get(pt, episode.id()).episode().version();
        ClinicalNote sessionDraft = service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "initial session", "2026-11-19T10:45:00Z", 1), version);
        assertFalse(sessionDraft.signed());
        assertEquals(NoteKind.SESSION, sessionDraft.kind());
        assertEquals(1, sessionDraft.version());
        assertEquals(PATIENT, sessionDraft.patientId());

        version = service.get(pt, episode.id()).episode().version();
        ClinicalNote signed = service.signNote(pt, episode.id(), NoteKind.SESSION, 1,
                "I attest this session note is accurate", version);
        assertTrue(signed.signed());
        assertEquals("pt-1", signed.signedBy());
        assertEquals(NOW, signed.signedAt());
        assertEquals("I attest this session note is accurate", signed.attestation());
        assertNotNull(signed.contentChecksum());
        assertTrue(signed.contentChecksum().length() > 0);

        version = service.get(pt, episode.id()).episode().version();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.PROGRESS, "good progress", "2026-11-26T10:00:00Z", 1), version);
        version = service.get(pt, episode.id()).episode().version();
        ClinicalNote progress = service.signNote(pt, episode.id(), NoteKind.PROGRESS, 1,
                "I attest this progress note is accurate", version);
        assertTrue(progress.signed());
        assertEquals(2, service.get(pt, episode.id()).notes().size());
    }

    @Test
    void signsDischargeTransitionsEpisodeAndSendsApprovedSummaryWithReturnAcknowledgment() {
        Episode episode = accept();
        evaluate(episode);
        int version = service.get(pt, episode.id()).episode().version();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "session", "2026-11-19T10:00:00Z", 1), version);
        version = service.get(pt, episode.id()).episode().version();
        service.signNote(pt, episode.id(), NoteKind.SESSION, 1, "attest session", version);

        version = service.get(pt, episode.id()).episode().version();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.DISCHARGE, "discharged with home programme", "2026-12-19T10:00:00Z", 1), version);
        version = service.get(pt, episode.id()).episode().version();
        ClinicalNote discharge = service.signNote(pt, episode.id(), NoteKind.DISCHARGE, 1,
                "I attest this discharge note is accurate", version);
        assertTrue(discharge.signed());
        assertEquals(EpisodeState.DISCHARGED, service.get(pt, episode.id()).episode().state());

        version = service.get(pt, episode.id()).episode().version();
        Summary summary = service.sendSummary(coordinator, episode.id(), new SummaryRequest(
                "summary of care", "org-ref", "ref-clinician"), version);
        assertTrue(summary.approved());
        assertEquals("coord-1", summary.approvedBy());
        assertEquals(NOW, summary.sentAt());
        assertEquals(1, summary.version());
        assertEquals("org-ref", summary.referrerOrg());

        version = service.get(pt, episode.id()).episode().version();
        ReturnAcknowledgment ack = service.acknowledgeReturn(pt, episode.id(), new AckRequest(
                1, "2026-12-20T08:00:00Z", "ref-clinician", "received, patient returned to care"), version);
        assertEquals(1, ack.summaryVersion());
        assertEquals("ref-clinician", ack.receivedBy());
        assertEquals("received, patient returned to care", ack.note());
        assertEquals(ack, service.get(pt, episode.id()).acknowledgment());
    }

    @Test
    void auditTrailRecordsActorAndOutcomePerCommandAndIsAppendOnly() {
        Episode episode = accept();
        evaluate(episode);

        List<TherapyAuditEvent> first = service.auditTrail(auditor);
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertTrue(first.stream().allMatch(e -> ORG.equals(e.organizationId())));
        assertTrue(first.stream().anyMatch(e -> "EPISODE_ACCEPT".equals(e.action())
                && "SUCCESS".equals(e.outcome()) && "pt-1".equals(e.actorSubject())));
        assertTrue(first.stream().anyMatch(e -> "EVALUATION_RECORD".equals(e.action())
                && "SUCCESS".equals(e.outcome())));

        int version = service.get(pt, episode.id()).episode().version();
        service.recordNote(pt, episode.id(), new NoteRequest(
                NoteKind.SESSION, "session", "2026-11-19T10:00:00Z", 1), version);
        List<TherapyAuditEvent> second = service.auditTrail(auditor);
        assertTrue(second.size() > first.size());
        assertTrue(second.containsAll(first));
    }
}
