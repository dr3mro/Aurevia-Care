package com.aurevia.staffmsg.references;

import com.aurevia.staffmsg.references.ReferenceContract.NotificationDraft;

/**
 * Port that dispatches the identifier-free notification for an opened reference.
 *
 * <p>The draft contains only a fixed event type, a fixed approved phrase and an
 * opaque reference id, so a patient-facing push payload can never carry chart
 * content, a subject line or a target identifier.</p>
 */
public interface ReferenceNotifier {

    /**
     * Dispatch one notification draft.
     *
     * @param draft immutable identifier-free draft
     */
    void dispatch(NotificationDraft draft);
}
