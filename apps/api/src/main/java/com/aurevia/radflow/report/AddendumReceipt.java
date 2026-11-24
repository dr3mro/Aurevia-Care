package com.aurevia.radflow.report;

import java.time.Duration;
import java.time.Instant;

import com.aurevia.radflow.report.ReportTypes.AcknowledgmentState;

/**
 * The acknowledgment receipt of one delivered addendum revision (RAD-01, ADM-02).
 *
 * <p>A receipt is created when an addendum is delivered and is replaced by an
 * acknowledged revision when the referring clinician confirms it. The receipt
 * binds the exact addendum version, so an acknowledgment can never vouch for a
 * different revision. Aging is derived from the notification time and the
 * configured acknowledgment window; an acknowledged receipt is never overdue.</p>
 *
 * @return this record
 */
public record AddendumReceipt(String organizationId, String orderId, int version, String referrer,
                              Instant notifiedAt, AcknowledgmentState state, Instant acknowledgedAt,
                              String acknowledgedBy) {

    /**
     * Whether this receipt is still awaiting an acknowledgment.
     *
     * @return {@code true} while the receipt is open
     */
    public boolean isOpen() {
        return state == AcknowledgmentState.OPEN;
    }

    /**
     * Whether an open receipt has aged past the acknowledgment window.
     *
     * @param now    current instant supplied by the injected clock
     * @param window configured acknowledgment window
     * @return {@code true} only for an open receipt past its deadline
     */
    public boolean isOverdue(Instant now, Duration window) {
        return isOpen() && now.isAfter(notifiedAt.plus(window));
    }
}
