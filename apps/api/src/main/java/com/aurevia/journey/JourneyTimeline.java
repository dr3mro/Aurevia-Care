package com.aurevia.journey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.aurevia.journey.JourneyContract.JourneyEvent;
import com.aurevia.journey.JourneyContract.JourneyItemType;
import com.aurevia.journey.JourneyContract.JourneyStep;
import com.aurevia.journey.JourneyContract.JourneyView;
import com.aurevia.journey.JourneyContract.StepStatus;
import com.aurevia.journey.JourneyContract.StepView;
import com.aurevia.journey.JourneyContract.Viewer;

/**
 * Projects normalized workflow facts into one ordered journey timeline (XCH-03,
 * RX-02, LAB-01, RAD-01).
 *
 * <p>The projector is a pure read model: it owns no state, stores no facts and
 * performs no I/O. A caller gathers immutable facts from the source ledgers and
 * supplies them to {@link com.aurevia.journey.JourneyTimeline#view(Viewer, String, String, JourneyItemType, Collection)}.
 * Facts are filtered to the requested tenant, item and item type, and further
 * filtered to released facts for a patient viewer. When more than one fact
 * completes the same step, the earliest one wins; ties are broken by event id so
 * the projection is deterministic across replays. The result always lists every
 * canonical step in order, with a pending step where no visible fact exists.</p>
 *
 * <p>The age of the next expected step is the time elapsed since the most recent
 * completed step before it, evaluated at the injected clock. It is clamped to
 * zero when the clock precedes that fact. This is a read model, not a command:
 * callers must authorize the item before supplying facts, and tenant scope is
 * re-applied here so a mixed collection cannot leak another organization's
 * facts into the view.</p>
 */
public final class JourneyTimeline {

    private final Clock clock;

    /**
     * Create a projector with an injected clock for deterministic ages.
     *
     * @param clock clock used as the evaluation instant
     */
    public JourneyTimeline(Clock clock) {
        this.clock = clock;
    }

    /**
     * Build the ordered timeline for one item and one viewer.
     *
     * @param viewer         audience of the view; a patient sees only released facts
     * @param organizationId tenant the caller is acting in
     * @param itemId         local identifier of the item
     * @param itemType       kind of the item
     * @param events         normalized facts gathered from every source ledger
     * @return the immutable ordered view with the next expected step and its age
     */
    public JourneyView view(Viewer viewer, String organizationId, String itemId, JourneyItemType itemType,
                            Collection<JourneyEvent> events) {
        Instant evaluatedAt = clock.instant();
        Map<JourneyStep, JourneyEvent> chosen = new EnumMap<>(JourneyStep.class);
        for (JourneyEvent event : events) {
            if (!organizationId.equals(event.organizationId())) {
                continue;
            }
            if (!itemId.equals(event.itemId())) {
                continue;
            }
            if (itemType != event.itemType()) {
                continue;
            }
            if (viewer == Viewer.PATIENT && !event.releasedToPatient()) {
                continue;
            }
            JourneyEvent current = chosen.get(event.step());
            if (current == null || earlier(event, current)) {
                chosen.put(event.step(), event);
            }
        }

        List<StepView> steps = new ArrayList<>();
        JourneyStep nextExpectedStep = null;
        Instant lastCompleteBeforeNext = null;
        Instant lastCompleteSeen = null;
        for (JourneyStep step : JourneyStep.values()) {
            JourneyEvent event = chosen.get(step);
            if (event == null) {
                steps.add(new StepView(step, StepStatus.PENDING, null, null, null));
                if (nextExpectedStep == null) {
                    nextExpectedStep = step;
                    // Every step before the first pending step is complete by definition.
                    lastCompleteBeforeNext = lastCompleteSeen;
                }
            } else {
                steps.add(new StepView(step, StepStatus.COMPLETE, event.source(), event.actorType(),
                        event.occurredAt()));
                lastCompleteSeen = event.occurredAt();
            }
        }

        Duration age = nextExpectedStep == null || lastCompleteBeforeNext == null
                ? Duration.ZERO
                : Duration.between(lastCompleteBeforeNext, evaluatedAt);
        if (age.isNegative()) {
            age = Duration.ZERO;
        }
        return new JourneyView(organizationId, itemId, itemType, viewer, evaluatedAt, steps,
                nextExpectedStep, age);
    }

    /**
     * Decide whether the candidate fact should replace the current fact for a step.
     *
     * @param candidate the fact just read
     * @param current   the fact already chosen for the step
     * @return {@code true} when the candidate occurred earlier, or at the same instant with a smaller id
     */
    private static boolean earlier(JourneyEvent candidate, JourneyEvent current) {
        int comparison = candidate.occurredAt().compareTo(current.occurredAt());
        if (comparison < 0) {
            return true;
        }
        if (comparison == 0) {
            return candidate.eventId().compareTo(current.eventId()) < 0;
        }
        return false;
    }
}
