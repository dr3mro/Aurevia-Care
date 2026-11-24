package com.aurevia.messaging.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.aurevia.messaging.service.ServiceProfileContract.SupervisorSnapshot;
import com.aurevia.messaging.service.ServiceProfileContract.WorkItemFact;
import com.aurevia.messaging.service.ServiceProfileContract.WorkState;

/**
 * Derives the MSG-06 supervisor counters from one immutable work snapshot.
 *
 * <p>Counters are computed on demand and never stored, so they cannot drift from
 * the underlying work facts. No patient identifier or clinical payload is read
 * or emitted. A single {@code now} value is threaded through every decision so
 * all counters share one definition of the present.</p>
 */
public final class SupervisorSnapshotCalculator {

    private SupervisorSnapshotCalculator() {
    }

    /**
     * Compute backlog, unassigned work, failed notifications and overdue
     * responses for one unit.
     *
     * @param organizationId       owning tenant
     * @param unitId               recipient unit
     * @param responseTargetMinutes configured first-response target
     * @param items                work facts already scoped to the unit
     * @param now                  single evaluation instant
     * @return the derived snapshot
     */
    public static SupervisorSnapshot compute(String organizationId, String unitId,
                                             int responseTargetMinutes, List<WorkItemFact> items, Instant now) {
        Duration target = Duration.ofMinutes(responseTargetMinutes);
        int backlog = 0;
        int unassigned = 0;
        int failedNotifications = 0;
        int overdueResponses = 0;
        for (WorkItemFact item : items) {
            // A dispatch failure remains relevant after closure: the patient may
            // never have received the reply, so it is never hidden by work state.
            if (item.notificationFailed()) {
                failedNotifications++;
            }
            if (!isOpen(item.state())) {
                continue;
            }
            backlog++;
            if (item.assignee() == null || item.assignee().isBlank()) {
                unassigned++;
            }
            if (item.state() == WorkState.AWAITING_STAFF && item.lastStaffResponseAt() == null
                    && !now.isBefore(item.createdAt().plus(target))) {
                overdueResponses++;
            }
        }
        return new SupervisorSnapshot(organizationId, unitId, backlog, unassigned,
                failedNotifications, overdueResponses, now);
    }

    private static boolean isOpen(WorkState state) {
        return state == WorkState.AWAITING_STAFF || state == WorkState.AWAITING_PATIENT;
    }
}
