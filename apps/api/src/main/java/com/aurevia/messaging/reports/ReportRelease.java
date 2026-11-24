package com.aurevia.messaging.reports;

import java.time.Instant;

/**
 * One immutable release decision for a report revision (XCH-06, PAT-02).
 *
 * <p>The revision itself is the immutable artifact identity; the decision adds the
 * release policy version it was made under, the time from which it takes effect and,
 * for a held release, the reason and review expiry. A withdrawal or correction appends
 * a new fact instead of rewriting an earlier one, so history survives process loss.</p>
 *
 * @param revision      immutable report revision whose access this decision governs
 * @param policyVersion version of the approved release policy applied by the decision
 * @param effectiveTime instant from which the decision is effective
 * @param holdReason    non-sensitive hold reason, or {@code null} for no hold
 * @param holdExpiresAt hold review expiry, or {@code null} for no hold
 * @param recordedAt    instant at which the decision was recorded
 * @return this immutable fact
 */
public record ReportRelease(ReleasedReport revision, int policyVersion, Instant effectiveTime,
                            String holdReason, Instant holdExpiresAt, Instant recordedAt) {
}
