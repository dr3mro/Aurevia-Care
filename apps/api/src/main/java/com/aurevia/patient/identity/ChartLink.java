package com.aurevia.patient.identity;

/**
 * One append-only account-to-chart link event (IAM-05).
 *
 * <p>The current link for a chart is the event with the highest database-owned
 * sequence. An {@code unlinked} event therefore ends future account access
 * without editing or deleting the earlier {@code linked} event.</p>
 *
 * @param id           opaque event identifier
 * @param accountSubject account that owns the link
 * @param chartId      immutable patient chart identifier
 * @param event        {@code linked} or {@code unlinked}
 * @param method       provenance of the event (for example INVITATION or REVERIFICATION)
 * @param reason       optional reason for an unlink or re-verification
 * @param occurredAt   ISO instant when the event was recorded
 * @return this record
 */
public record ChartLink(String id, String accountSubject, String chartId, String event,
                        String method, String reason, String occurredAt) {
}
