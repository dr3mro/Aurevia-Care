package com.aurevia.staffmsg.attachments;

import java.util.List;
import java.util.Set;

/**
 * Narrow seam through which a staff message send publishes the files already
 * bound to its draft or conversation.
 *
 * <p>The seam has two phases.
 * {@link com.aurevia.staffmsg.attachments.StaffAttachmentPublication#requirePublishable(String, String, String)}
 * runs before the message row is committed so a quarantined or otherwise
 * unpublishable file prevents publication completely; it returns the validated
 * file identifiers so the second phase binds exactly those files.
 * {@link com.aurevia.staffmsg.attachments.StaffAttachmentPublication#publish(String, String, String, String, Set, List)}
 * runs after the message row exists, because the
 * durable attachment link carries a foreign key to the published message. The
 * audience is supplied by the caller from persisted membership or frozen mail
 * recipients; it is never taken from an attachment request body. Both phases
 * are no-ops when the draft holds no unsent files.</p>
 */
public interface StaffAttachmentPublication {

    /**
     * Reject a message send when any pending file of the draft cannot be sent.
     *
     * @param organizationId active organization
     * @param subject        authenticated sender
     * @param draftId        mail draft or conversation identifier
     * @return identifiers of the files that passed validation, possibly empty
     */
    List<String> requirePublishable(String organizationId, String subject, String draftId);

    /**
     * Bind the draft's publishable files to an already committed message.
     *
     * @param organizationId active organization
     * @param subject        authenticated sender
     * @param draftId        mail draft or conversation identifier
     * @param messageId      identifier returned by the message store
     * @param recipients     frozen recipient subjects authorized to download
     * @param attachmentIds  identifiers returned by the preflight phase
     */
    void publish(String organizationId, String subject, String draftId, String messageId,
                 Set<String> recipients, List<String> attachmentIds);

    /**
     * Return the publication used by callers that do not own a message send.
     *
     * @return a publication whose two phases do nothing
     */
    static StaffAttachmentPublication none() {
        return NoOp.INSTANCE;
    }

    /**
     * Inert publication for legacy entry points and unit fixtures.
     */
    final class NoOp implements StaffAttachmentPublication {

        /** The single inert instance. */
        static final NoOp INSTANCE = new NoOp();

        private NoOp() {
        }
        /**
         * Reject a message send when any pending file of the draft cannot be sent.
         *
         * @param organizationId active organization
         * @param subject        authenticated sender
         * @param draftId        mail draft or conversation identifier
         * @return identifiers of the files that passed validation, possibly empty
         */
        @Override
        public List<String> requirePublishable(String organizationId, String subject, String draftId) {
            // No message is being published, so there is nothing to validate.
            return List.of();
        }

        @Override
        public void publish(String organizationId, String subject, String draftId, String messageId,
                            Set<String> recipients, List<String> attachmentIds) {
            // No message is being published, so no attachment link can be created.
        }
    }
}
