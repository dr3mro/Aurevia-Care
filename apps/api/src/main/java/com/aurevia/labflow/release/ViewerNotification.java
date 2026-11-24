package com.aurevia.labflow.release;

import java.time.Instant;

/**
 * An append-only notification fact telling an earlier viewer that a released report changed
 * (XCH-06, PAT-02).
 *
 * <p>The fact records only a recipient scope and a notification type, never clinical content.
 * It is an auditable evidence record for the correction/withdrawal event, not a claim that a
 * message was delivered to an external channel.</p>
 *
 * @param releaseId      release whose change is published
 * @param revisionId     revision the notification refers to
 * @param recipientScope policy-defined recipient class, for example {@code ordering-clinician}
 * @param type           notification type such as {@code CORRECTION_NOTICE}
 * @param reason         non-sensitive change reason, or {@code null}
 * @param occurredAt     instant the fact was recorded
 * @param actor          authenticated actor that caused the change
 * @return this notification
 */
public record ViewerNotification(String releaseId, String revisionId, String recipientScope,
                                 String type, String reason, Instant occurredAt, String actor) {
}
