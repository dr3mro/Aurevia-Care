package com.aurevia.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.journey.JourneyContract.ActorType;
import com.aurevia.journey.JourneyContract.JourneyEvent;
import com.aurevia.journey.JourneyContract.JourneyItemType;
import com.aurevia.journey.JourneyContract.JourneyStep;
import com.aurevia.journey.JourneyContract.JourneyView;
import com.aurevia.journey.JourneyContract.StepStatus;
import com.aurevia.journey.JourneyContract.StepView;
import com.aurevia.journey.JourneyContract.Viewer;

/**
 * Acceptance for the single ordered journey timeline read model.
 *
 * <p>Covers XCH-03, RX-02, LAB-01 and RAD-01. Events from each workflow source
 * are supplied as synthetic records and merged by the projector. Every case is
 * executed tool evidence; no clinician or simulated-user evidence is claimed.</p>
 */
class JourneyTimelineTest {

    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";
    private static final String ITEM = "item-1";
    private static final String OTHER_ITEM = "item-2";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Instant T0 = NOW.minusSeconds(600);

    private final JourneyTimeline timeline = new JourneyTimeline(Clock.fixed(NOW, ZoneOffset.UTC));

    private static JourneyEvent event(String id, String org, String item, JourneyItemType type, String source,
                                      JourneyStep step, ActorType actor, Instant at, boolean releasedToPatient) {
        return new JourneyEvent(id, org, item, type, source, step, actor, at, releasedToPatient);
    }

    private static StepView complete(JourneyStep step, String source, ActorType actor, Instant at) {
        return new StepView(step, StepStatus.COMPLETE, source, actor, at);
    }

    private static StepView pending(JourneyStep step) {
        return new StepView(step, StepStatus.PENDING, null, null, null);
    }

    @Test
    void eventsFromEachSourceMergeInChronologicalStepOrder() {
        JourneyEvent placed = event("e1", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "order",
                JourneyStep.PLACED, ActorType.CLINICIAN, T0, true);
        JourneyEvent delivered = event("e2", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "order",
                JourneyStep.DELIVERED, ActorType.SYSTEM, T0.plusSeconds(60), true);
        JourneyEvent accepted = event("e3", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "order",
                JourneyStep.ACCEPTED, ActorType.LABORATORY, T0.plusSeconds(120), false);
        JourneyEvent collected = event("e4", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "lab",
                JourneyStep.COLLECTED_OR_SCHEDULED, ActorType.LABORATORY, T0.plusSeconds(180), true);
        JourneyEvent resulted = event("e5", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "lab",
                JourneyStep.RESULTED, ActorType.LABORATORY, T0.plusSeconds(240), true);
        JourneyEvent released = event("e6", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "release",
                JourneyStep.RELEASED, ActorType.CLINICIAN, T0.plusSeconds(300), true);
        JourneyEvent acknowledged = event("e7", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "acknowledgment",
                JourneyStep.ACKNOWLEDGED, ActorType.PATIENT, T0.plusSeconds(360), true);

        // Supplied out of order to prove the read model merges by time, not by arrival.
        List<JourneyEvent> shuffled = new ArrayList<>(List.of(released, placed, resulted, delivered,
                acknowledged, collected, accepted));
        JourneyView view = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.LAB_ORDER, shuffled);
        shuffled.clear();

        assertEquals(List.of(
                complete(JourneyStep.PLACED, "order", ActorType.CLINICIAN, T0),
                complete(JourneyStep.DELIVERED, "order", ActorType.SYSTEM, T0.plusSeconds(60)),
                complete(JourneyStep.ACCEPTED, "order", ActorType.LABORATORY, T0.plusSeconds(120)),
                complete(JourneyStep.COLLECTED_OR_SCHEDULED, "lab", ActorType.LABORATORY, T0.plusSeconds(180)),
                complete(JourneyStep.RESULTED, "lab", ActorType.LABORATORY, T0.plusSeconds(240)),
                complete(JourneyStep.RELEASED, "release", ActorType.CLINICIAN, T0.plusSeconds(300)),
                complete(JourneyStep.ACKNOWLEDGED, "acknowledgment", ActorType.PATIENT, T0.plusSeconds(360))),
                view.steps());
        assertNull(view.nextExpectedStep());
        assertEquals(Duration.ZERO, view.nextExpectedAge());
        assertEquals(NOW, view.evaluatedAt());
    }

    @Test
    void missingStepIsShownAsPendingWithAgeSinceLastCompleteStep() {
        List<JourneyEvent> events = List.of(
                event("e1", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "order",
                        JourneyStep.PLACED, ActorType.CLINICIAN, T0, true),
                event("e2", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "order",
                        JourneyStep.DELIVERED, ActorType.SYSTEM, T0.plusSeconds(60), true),
                event("e5", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "lab",
                        JourneyStep.RESULTED, ActorType.LABORATORY, T0.plusSeconds(300), true));

        JourneyView view = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.LAB_ORDER, events);

        assertEquals(List.of(
                complete(JourneyStep.PLACED, "order", ActorType.CLINICIAN, T0),
                complete(JourneyStep.DELIVERED, "order", ActorType.SYSTEM, T0.plusSeconds(60)),
                pending(JourneyStep.ACCEPTED),
                pending(JourneyStep.COLLECTED_OR_SCHEDULED),
                complete(JourneyStep.RESULTED, "lab", ActorType.LABORATORY, T0.plusSeconds(300)),
                pending(JourneyStep.RELEASED),
                pending(JourneyStep.ACKNOWLEDGED)), view.steps());
        assertEquals(JourneyStep.ACCEPTED, view.nextExpectedStep());
        // ACCEPTED is pending since DELIVERED at T0+60; now is T0+600.
        assertEquals(Duration.ofSeconds(540), view.nextExpectedAge());
    }

    @Test
    void patientSeesOnlyReleasedSteps() {
        List<JourneyEvent> events = List.of(
                event("e1", ORG_A, ITEM, JourneyItemType.PRESCRIPTION, "prescription",
                        JourneyStep.PLACED, ActorType.PRESCRIBER, T0, true),
                event("e2", ORG_A, ITEM, JourneyItemType.PRESCRIPTION, "prescription",
                        JourneyStep.DELIVERED, ActorType.SYSTEM, T0.plusSeconds(60), false),
                event("e3", ORG_A, ITEM, JourneyItemType.PRESCRIPTION, "prescription",
                        JourneyStep.ACCEPTED, ActorType.PHARMACY, T0.plusSeconds(120), false),
                event("e5", ORG_A, ITEM, JourneyItemType.PRESCRIPTION, "prescription",
                        JourneyStep.RESULTED, ActorType.PHARMACY, T0.plusSeconds(180), true),
                event("e6", ORG_A, ITEM, JourneyItemType.PRESCRIPTION, "release",
                        JourneyStep.RELEASED, ActorType.CLINICIAN, T0.plusSeconds(240), true),
                event("e7", ORG_A, ITEM, JourneyItemType.PRESCRIPTION, "acknowledgment",
                        JourneyStep.ACKNOWLEDGED, ActorType.PATIENT, T0.plusSeconds(300), true));

        JourneyView clinician = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.PRESCRIPTION, events);
        assertEquals(StepStatus.COMPLETE, clinician.steps().get(1).status());
        assertEquals(ActorType.SYSTEM, clinician.steps().get(1).actorType());

        JourneyView patient = timeline.view(Viewer.PATIENT, ORG_A, ITEM, JourneyItemType.PRESCRIPTION, events);
        assertEquals(List.of(
                complete(JourneyStep.PLACED, "prescription", ActorType.PRESCRIBER, T0),
                pending(JourneyStep.DELIVERED),
                pending(JourneyStep.ACCEPTED),
                pending(JourneyStep.COLLECTED_OR_SCHEDULED),
                complete(JourneyStep.RESULTED, "prescription", ActorType.PHARMACY, T0.plusSeconds(180)),
                complete(JourneyStep.RELEASED, "release", ActorType.CLINICIAN, T0.plusSeconds(240)),
                complete(JourneyStep.ACKNOWLEDGED, "acknowledgment", ActorType.PATIENT, T0.plusSeconds(300))),
                patient.steps());
        // Hidden steps carry no actor, source or time for the patient.
        assertNull(patient.steps().get(1).actorType());
        assertNull(patient.steps().get(1).source());
        assertNull(patient.steps().get(1).occurredAt());
        assertEquals(JourneyStep.DELIVERED, patient.nextExpectedStep());
        assertEquals(Duration.ofSeconds(600), patient.nextExpectedAge());
    }

    @Test
    void tenantIsolationExcludesAnotherOrganizationsEvents() {
        List<JourneyEvent> events = List.of(
                event("a1", ORG_A, ITEM, JourneyItemType.IMAGING_ORDER, "imaging",
                        JourneyStep.PLACED, ActorType.CLINICIAN, T0, true),
                event("a2", ORG_A, ITEM, JourneyItemType.IMAGING_ORDER, "imaging",
                        JourneyStep.COLLECTED_OR_SCHEDULED, ActorType.RADIOLOGY, T0.plusSeconds(60), false),
                event("b1", ORG_B, ITEM, JourneyItemType.IMAGING_ORDER, "imaging",
                        JourneyStep.RESULTED, ActorType.RADIOLOGY, T0.plusSeconds(30), true));

        JourneyView viewA = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.IMAGING_ORDER, events);
        assertEquals(ORG_A, viewA.organizationId());
        assertEquals(StepStatus.COMPLETE, viewA.steps().get(0).status());
        assertEquals(ActorType.CLINICIAN, viewA.steps().get(0).actorType());
        assertEquals(StepStatus.PENDING, viewA.steps().get(4).status());

        JourneyView viewB = timeline.view(Viewer.CLINICIAN, ORG_B, ITEM, JourneyItemType.IMAGING_ORDER, events);
        assertEquals(ORG_B, viewB.organizationId());
        assertEquals(StepStatus.PENDING, viewB.steps().get(0).status());
        assertEquals(StepStatus.COMPLETE, viewB.steps().get(4).status());
        assertEquals(ActorType.RADIOLOGY, viewB.steps().get(4).actorType());
    }

    @Test
    void eventsForAnotherItemOrTypeAreExcluded() {
        List<JourneyEvent> events = List.of(
                event("e1", ORG_A, OTHER_ITEM, JourneyItemType.REFERRAL, "referral",
                        JourneyStep.PLACED, ActorType.CLINICIAN, T0, true),
                event("e2", ORG_A, ITEM, JourneyItemType.PRESCRIPTION, "prescription",
                        JourneyStep.PLACED, ActorType.PRESCRIBER, T0.plusSeconds(10), true));

        JourneyView view = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.REFERRAL, events);
        assertEquals(List.of(
                pending(JourneyStep.PLACED),
                pending(JourneyStep.DELIVERED),
                pending(JourneyStep.ACCEPTED),
                pending(JourneyStep.COLLECTED_OR_SCHEDULED),
                pending(JourneyStep.RESULTED),
                pending(JourneyStep.RELEASED),
                pending(JourneyStep.ACKNOWLEDGED)), view.steps());
        assertEquals(JourneyStep.PLACED, view.nextExpectedStep());
        assertEquals(Duration.ZERO, view.nextExpectedAge());
    }

    @Test
    void earliestEventForAStepWinsWithDeterministicTieBreak() {
        JourneyEvent later = event("c", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "source-c",
                JourneyStep.ACCEPTED, ActorType.PHARMACY, T0.plusSeconds(100), true);
        JourneyEvent base = event("b", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "source-b",
                JourneyStep.ACCEPTED, ActorType.LABORATORY, T0.plusSeconds(50), true);
        JourneyEvent earlierTime = event("m", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "source-m",
                JourneyStep.ACCEPTED, ActorType.CLINICIAN, T0.plusSeconds(10), true);
        JourneyEvent sameTimeSmallerId = event("a", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "source-a",
                JourneyStep.ACCEPTED, ActorType.SYSTEM, T0.plusSeconds(50), true);
        JourneyEvent sameTimeLargerId = event("z", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "source-z",
                JourneyStep.ACCEPTED, ActorType.RADIOLOGY, T0.plusSeconds(50), true);
        JourneyEvent sameIdAgain = event("a", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "source-duplicate",
                JourneyStep.ACCEPTED, ActorType.PATIENT, T0.plusSeconds(50), true);

        JourneyView view = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.LAB_ORDER,
                List.of(base, later, sameTimeSmallerId, sameTimeLargerId, sameIdAgain, earlierTime));

        // "a" wins the tie at T+50 against "b", "z" and a repeated "a"; then the
        // genuinely earlier fact "m" at T+10 wins by time.
        StepView accepted = view.steps().get(2);
        assertEquals(StepStatus.COMPLETE, accepted.status());
        assertEquals("source-m", accepted.source());
        assertEquals(ActorType.CLINICIAN, accepted.actorType());
        assertEquals(T0.plusSeconds(10), accepted.occurredAt());
    }

    @Test
    void completedJourneyHasNoNextExpectedStep() {
        List<JourneyEvent> events = new ArrayList<>();
        int index = 0;
        for (JourneyStep step : JourneyStep.values()) {
            events.add(event("e" + index++, ORG_A, ITEM, JourneyItemType.REFERRAL, "referral",
                    step, ActorType.CLINICIAN, T0.plusSeconds(index), true));
        }

        JourneyView view = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.REFERRAL, events);

        assertNull(view.nextExpectedStep());
        assertEquals(Duration.ZERO, view.nextExpectedAge());
    }

    @Test
    void emptyJourneyIsAllPendingWithPlacedNext() {
        JourneyView view = timeline.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.REFERRAL, List.of());

        assertEquals(7, view.steps().size());
        for (StepView step : view.steps()) {
            assertEquals(StepStatus.PENDING, step.status());
        }
        assertEquals(JourneyStep.PLACED, view.nextExpectedStep());
        assertEquals(Duration.ZERO, view.nextExpectedAge());
    }

    @Test
    void ageIsClampedWhenClockPrecedesTheLastCompleteStep() {
        JourneyTimeline earlyClock = new JourneyTimeline(Clock.fixed(T0, ZoneOffset.UTC));
        List<JourneyEvent> events = List.of(
                event("e1", ORG_A, ITEM, JourneyItemType.LAB_ORDER, "order",
                        JourneyStep.PLACED, ActorType.CLINICIAN, T0.plusSeconds(60), true));

        JourneyView view = earlyClock.view(Viewer.CLINICIAN, ORG_A, ITEM, JourneyItemType.LAB_ORDER, events);

        assertEquals(JourneyStep.DELIVERED, view.nextExpectedStep());
        assertEquals(Duration.ZERO, view.nextExpectedAge());
    }
}
