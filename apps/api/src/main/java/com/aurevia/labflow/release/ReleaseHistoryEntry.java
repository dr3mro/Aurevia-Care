package com.aurevia.labflow.release;

import java.time.Instant;

import com.aurevia.exchange.release.ReleaseEvent;

/**
 * One entry in the complete, append-only history of a released report (XCH-06, PAT-02).
 *
 * <p>Release lifecycle decisions and viewer notifications are folded into a single
 * chronological record so a correction or withdrawal never erases the earlier events.</p>
 *
 * @param type       {@code RELEASE_EVENT} or {@code VIEWER_NOTIFICATION}
 * @param id         release or revision identifier the entry describes
 * @param action     decision action or notification type
 * @param actor      authenticated actor, or recipient scope for a notification
 * @param reason     non-sensitive reason, or {@code null}
 * @param occurredAt instant the fact was recorded
 * @return this entry
 */
public record ReleaseHistoryEntry(String type, String id, String action, String actor,
                                  String reason, Instant occurredAt) {

    /**
     * Fold a durable release decision event into a history entry.
     *
     * @param event persisted release event
     * @return the history entry
     */
    public static ReleaseHistoryEntry of(ReleaseEvent event) {
        return new ReleaseHistoryEntry("RELEASE_EVENT", event.releaseId(), event.action(),
                event.actorId(), event.reason(), event.occurredAt());
    }

    /**
     * Fold a viewer notification fact into a history entry.
     *
     * @param notification recorded viewer notification
     * @return the history entry
     */
    public static ReleaseHistoryEntry of(ViewerNotification notification) {
        return new ReleaseHistoryEntry("VIEWER_NOTIFICATION", notification.revisionId(),
                notification.type(), notification.recipientScope(), notification.reason(),
                notification.occurredAt());
    }
}
