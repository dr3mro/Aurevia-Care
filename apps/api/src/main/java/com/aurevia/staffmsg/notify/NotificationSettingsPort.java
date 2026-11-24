package com.aurevia.staffmsg.notify;

import java.util.Optional;

import com.aurevia.staffmsg.notify.NotificationContract.AutoReplyReceipt;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationSettings;

/**
 * Boundary for per-person notification preferences and the auto-reply ledger.
 *
 * <p>The port is deliberately not named a store: persistence is owned by a later
 * slice. A durable adapter must read mute from the existing participant membership
 * (which carries the muted flag), bind every row to its tenant and subject, and
 * enforce a unique key on tenant, recipient, sender and UTC day so the atomic
 * reply check below survives concurrent requests and a restart.</p>
 */
public interface NotificationSettingsPort {

    /**
     * Read one subject's concealed preferences.
     *
     * @param organizationId owning tenant
     * @param subject        authenticated staff subject
     * @return the preferences, or empty when the subject has none in this tenant
     */
    Optional<NotificationSettings> findSettings(String organizationId, String subject);

    /**
     * Persist one subject's preferences, scoped by their own tenant and subject.
     *
     * @param settings preferences stamped with tenant and subject by the caller
     */
    void saveSettings(NotificationSettings settings);

    /**
     * Decide whether a recipient muted a conversation.
     *
     * @param organizationId owning tenant
     * @param conversationId opaque conversation handle
     * @param subject        authenticated staff subject
     * @return {@code true} when notifications for this conversation are suppressed
     */
    boolean isConversationMuted(String organizationId, String conversationId, String subject);

    /**
     * Atomically record the first out-of-office reply for this recipient, sender and day.
     *
     * @param receipt receipt carrying tenant, recipient, sender and UTC day
     * @return {@code true} only when this call recorded the first reply for the key
     */
    boolean tryRecordAutoReply(AutoReplyReceipt receipt);
}
