package com.aurevia.staffmsg.notify;

import com.aurevia.staffmsg.notify.NotificationContract.NotificationDraft;

/**
 * Port that publishes an identifier-free notification for a recipient.
 *
 * <p>Shaped like the existing reference notifier so a draft can only carry the fixed
 * phrase and opaque handles. No subject, body, patient name or clinical identifier
 * can reach this boundary.</p>
 */
public interface NotificationNotifier {

    /**
     * Dispatch one immutable, content-free draft.
     *
     * @param draft the approved draft to publish
     */
    void dispatch(NotificationDraft draft);
}
