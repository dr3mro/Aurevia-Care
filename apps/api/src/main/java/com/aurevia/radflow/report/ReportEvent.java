package com.aurevia.radflow.report;

import java.time.Instant;

import com.aurevia.radflow.report.ReportTypes.ReportEventType;

/**
 * One immutable fact produced by the RAD-01 report delivery kernel (ARC-05, OPS-02).
 *
 * <p>The event carries only identifiers and the actor. The destination names the
 * referring clinician who received a delivery, so the fact proves that a signed
 * revision reached its intended reader without carrying any clinical narrative.</p>
 *
 * @return this record
 */
public record ReportEvent(String organizationId, ReportEventType type, String orderId, int version,
                          String patientId, String referrer, String actor, String destination,
                          Instant occurredAt) {
}
