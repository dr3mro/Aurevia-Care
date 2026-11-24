package com.aurevia.journey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
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
 * Contract checks for the journey read-model vocabulary and immutable views.
 *
 * <p>Every case uses synthetic identifiers only. Evidence comes from executed
 * unit tests, not from a clinician or a simulated user.</p>
 */
class JourneyContractTest {

    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");

    @Test
    void journeyStepWireKeysRoundTripAndRejectUnknown() {
        assertEquals("placed", JourneyStep.PLACED.wire());
        assertEquals("delivered", JourneyStep.DELIVERED.wire());
        assertEquals("accepted", JourneyStep.ACCEPTED.wire());
        assertEquals("collected_or_scheduled", JourneyStep.COLLECTED_OR_SCHEDULED.wire());
        assertEquals("resulted", JourneyStep.RESULTED.wire());
        assertEquals("released", JourneyStep.RELEASED.wire());
        assertEquals("acknowledged", JourneyStep.ACKNOWLEDGED.wire());

        for (JourneyStep step : JourneyStep.values()) {
            assertEquals(step, JourneyStep.from(step.wire()));
        }
        assertEquals("unknown journey step: bogus",
                assertThrows(IllegalArgumentException.class, () -> JourneyStep.from("bogus")).getMessage());
    }

    @Test
    void itemTypeWireKeysRoundTripAndRejectUnknown() {
        assertEquals("prescription", JourneyItemType.PRESCRIPTION.wire());
        assertEquals("lab_order", JourneyItemType.LAB_ORDER.wire());
        assertEquals("imaging_order", JourneyItemType.IMAGING_ORDER.wire());
        assertEquals("referral", JourneyItemType.REFERRAL.wire());

        for (JourneyItemType type : JourneyItemType.values()) {
            assertEquals(type, JourneyItemType.from(type.wire()));
        }
        assertEquals("unknown journey item type: bogus",
                assertThrows(IllegalArgumentException.class, () -> JourneyItemType.from("bogus")).getMessage());
    }

    @Test
    void actorTypeWireKeysRoundTripAndRejectUnknown() {
        assertEquals("prescriber", ActorType.PRESCRIBER.wire());
        assertEquals("clinician", ActorType.CLINICIAN.wire());
        assertEquals("pharmacy", ActorType.PHARMACY.wire());
        assertEquals("laboratory", ActorType.LABORATORY.wire());
        assertEquals("radiology", ActorType.RADIOLOGY.wire());
        assertEquals("referral_recipient", ActorType.REFERRAL_RECIPIENT.wire());
        assertEquals("patient", ActorType.PATIENT.wire());
        assertEquals("system", ActorType.SYSTEM.wire());

        for (ActorType type : ActorType.values()) {
            assertEquals(type, ActorType.from(type.wire()));
        }
        assertEquals("unknown journey actor type: bogus",
                assertThrows(IllegalArgumentException.class, () -> ActorType.from("bogus")).getMessage());
    }

    @Test
    void stepStatusAndViewerWireKeysRoundTripAndRejectUnknown() {
        assertEquals("pending", StepStatus.PENDING.wire());
        assertEquals("complete", StepStatus.COMPLETE.wire());
        for (StepStatus status : StepStatus.values()) {
            assertEquals(status, StepStatus.from(status.wire()));
        }
        assertEquals("unknown journey step status: bogus",
                assertThrows(IllegalArgumentException.class, () -> StepStatus.from("bogus")).getMessage());

        assertEquals("clinician", Viewer.CLINICIAN.wire());
        assertEquals("patient", Viewer.PATIENT.wire());
        for (Viewer viewer : Viewer.values()) {
            assertEquals(viewer, Viewer.from(viewer.wire()));
        }
        assertEquals("unknown journey viewer: bogus",
                assertThrows(IllegalArgumentException.class, () -> Viewer.from("bogus")).getMessage());
    }

    @Test
    void eventAndStepViewsCarryTheirImmutableFacts() {
        JourneyEvent event = new JourneyEvent("event-1", "org-a", "item-1", JourneyItemType.LAB_ORDER,
                "lab", JourneyStep.RESULTED, ActorType.LABORATORY, AT, true);
        assertEquals("event-1", event.eventId());
        assertEquals("org-a", event.organizationId());
        assertEquals("item-1", event.itemId());
        assertEquals(JourneyItemType.LAB_ORDER, event.itemType());
        assertEquals("lab", event.source());
        assertEquals(JourneyStep.RESULTED, event.step());
        assertEquals(ActorType.LABORATORY, event.actorType());
        assertEquals(AT, event.occurredAt());
        assertEquals(true, event.releasedToPatient());
        assertEquals(event, new JourneyEvent("event-1", "org-a", "item-1", JourneyItemType.LAB_ORDER,
                "lab", JourneyStep.RESULTED, ActorType.LABORATORY, AT, true));
        assertEquals(event.hashCode(), new JourneyEvent("event-1", "org-a", "item-1", JourneyItemType.LAB_ORDER,
                "lab", JourneyStep.RESULTED, ActorType.LABORATORY, AT, true).hashCode());
        assertEquals(event.toString(), new JourneyEvent("event-1", "org-a", "item-1", JourneyItemType.LAB_ORDER,
                "lab", JourneyStep.RESULTED, ActorType.LABORATORY, AT, true).toString());
        assertNotEquals(event, new JourneyEvent("event-2", "org-a", "item-1", JourneyItemType.LAB_ORDER,
                "lab", JourneyStep.RESULTED, ActorType.LABORATORY, AT, true));

        StepView complete = new StepView(JourneyStep.RESULTED, StepStatus.COMPLETE, "lab",
                ActorType.LABORATORY, AT);
        assertEquals(JourneyStep.RESULTED, complete.step());
        assertEquals(StepStatus.COMPLETE, complete.status());
        assertEquals("lab", complete.source());
        assertEquals(ActorType.LABORATORY, complete.actorType());
        assertEquals(AT, complete.occurredAt());
        assertEquals(complete, new StepView(JourneyStep.RESULTED, StepStatus.COMPLETE, "lab",
                ActorType.LABORATORY, AT));
        assertEquals(complete.hashCode(), new StepView(JourneyStep.RESULTED, StepStatus.COMPLETE, "lab",
                ActorType.LABORATORY, AT).hashCode());
        assertEquals(complete.toString(), new StepView(JourneyStep.RESULTED, StepStatus.COMPLETE, "lab",
                ActorType.LABORATORY, AT).toString());

        StepView pending = new StepView(JourneyStep.ACCEPTED, StepStatus.PENDING, null, null, null);
        assertEquals(null, pending.source());
        assertEquals(null, pending.actorType());
        assertEquals(null, pending.occurredAt());
        assertNotEquals(complete, pending);
    }

    @Test
    void journeyViewFreezesStepsAgainstCallerMutation() {
        List<StepView> mutable = new ArrayList<>(List.of(
                new StepView(JourneyStep.PLACED, StepStatus.COMPLETE, "order", ActorType.CLINICIAN, AT)));
        JourneyView view = new JourneyView("org-a", "item-1", JourneyItemType.LAB_ORDER, Viewer.CLINICIAN,
                AT, mutable, JourneyStep.DELIVERED, Duration.ofMinutes(5));
        mutable.clear();

        assertEquals(1, view.steps().size());
        assertEquals("org-a", view.organizationId());
        assertEquals("item-1", view.itemId());
        assertEquals(JourneyItemType.LAB_ORDER, view.itemType());
        assertEquals(Viewer.CLINICIAN, view.viewer());
        assertEquals(AT, view.evaluatedAt());
        assertEquals(JourneyStep.DELIVERED, view.nextExpectedStep());
        assertEquals(Duration.ofMinutes(5), view.nextExpectedAge());
        assertThrows(UnsupportedOperationException.class, () -> view.steps().clear());

        JourneyView same = new JourneyView("org-a", "item-1", JourneyItemType.LAB_ORDER, Viewer.CLINICIAN,
                AT, List.of(new StepView(JourneyStep.PLACED, StepStatus.COMPLETE, "order",
                        ActorType.CLINICIAN, AT)), JourneyStep.DELIVERED, Duration.ofMinutes(5));
        assertEquals(same, view);
        assertEquals(same.hashCode(), view.hashCode());
        assertEquals(same.toString(), view.toString());
        assertNotEquals(new JourneyView("org-b", "item-1", JourneyItemType.LAB_ORDER, Viewer.CLINICIAN,
                AT, view.steps(), JourneyStep.DELIVERED, Duration.ofMinutes(5)), view);
    }
}
