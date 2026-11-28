package com.aurevia.scheduling.followup;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Append-only provenance for an authorized follow-up date override.
 *
 * <p>The actor is taken from the authenticated context, never from the caller's
 * payload. The original date stays visible so a reviewer can see what changed.</p>
 *
 * @param actor           authenticated subject that recorded the override
 * @param reason          required recorded reason
 * @param recordedAt      instant the override was recorded
 * @param originalDate    civil date before the override
 * @param replacementDate civil date after the override
 * @return this record
 */
public record FollowUpOverride(String actor, String reason, Instant recordedAt,
                               LocalDate originalDate, LocalDate replacementDate) {
}
