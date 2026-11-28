package com.aurevia.exchange.task;

import java.time.Instant;

/**
 * Immutable snapshot of the three independent XCH-02 state channels (ARC-01).
 *
 * <p>Actor, timestamp and revision evidence are only written by the clinical
 * review path. The actor is derived from the authenticated transaction context
 * by the database guard, never from a request body; the review instant and the
 * exact reviewed revision are bound to the command by the state machine.</p>
 *
 * @param id                 opaque tenant-local task identifier
 * @param version            optimistic version of this snapshot
 * @param orderRef           tenant-local order reference
 * @param workflowState      destination workflow channel
 * @param transmissionState  partner delivery channel
 * @param clinicalReviewState human clinical review channel
 * @param reason             recorded reason for reject or cancel, when any
 * @param reviewedBy         actor who changed clinical review, or {@code null}
 * @param reviewedAt         clock instant of the clinical review change, or {@code null}
 * @param reviewedRevision   exact version reviewed, or {@code null}
 * @param createdAt          creation instant
 * @return this record
 */
public record ExchangeTask(String id, int version, String orderRef,
                           WorkflowState workflowState,
                           TransmissionState transmissionState,
                           ClinicalReviewState clinicalReviewState,
                           String reason,
                           String reviewedBy,
                           Instant reviewedAt,
                           Integer reviewedRevision,
                           Instant createdAt) {
}
