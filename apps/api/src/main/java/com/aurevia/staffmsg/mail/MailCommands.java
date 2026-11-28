package com.aurevia.staffmsg.mail;

import java.util.List;

import com.aurevia.staffmsg.mail.MailContract.Recipient;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;

/**
 * Command values of the staff mail API.
 *
 * <p>A command is the complete, explicit input of one state transition. The
 * active tenant and the actor are always supplied by the web adapter from the
 * request context, never from a client body. Client lists are frozen so a
 * caller cannot change a command after it has been accepted.</p>
 */
public final class MailCommands {

    private MailCommands() {
    }

    /**
     * Start a new private draft.
     *
     * @return the immutable command
     */
    public record CreateDraftCommand(String organizationId, String facilityId, String actor, String subject,
                                     String body, List<Recipient> to, List<Recipient> cc, List<String> bcc,
                                     List<String> attachmentDocumentIds) {

        /**
         * Freeze every client list.
         */
        public CreateDraftCommand {
            to = MailInputs.freeze(to);
            cc = MailInputs.freeze(cc);
            bcc = MailInputs.freeze(bcc);
            attachmentDocumentIds = MailInputs.freeze(attachmentDocumentIds);
        }

        /**
         * @return immutable to recipients
         */
        @Override
        public List<Recipient> to() {
            return MailInputs.freeze(to);
        }

        /**
         * @return immutable cc recipients
         */
        @Override
        public List<Recipient> cc() {
            return MailInputs.freeze(cc);
        }

        /**
         * @return immutable blind-copy field, always rejected by the service
         */
        @Override
        public List<String> bcc() {
            return MailInputs.freeze(bcc);
        }

        /**
         * @return immutable attachment identifiers
         */
        @Override
        public List<String> attachmentDocumentIds() {
            return MailInputs.freeze(attachmentDocumentIds);
        }
    }

    /**
     * Save a new version of an existing draft.
     *
     * @return the immutable command
     */
    public record SaveDraftCommand(String organizationId, String actor, String draftId, int expectedVersion,
                                   String subject, String body, List<Recipient> to, List<Recipient> cc,
                                   List<String> bcc, List<String> attachmentDocumentIds) {

        /**
         * Freeze every client list.
         */
        public SaveDraftCommand {
            to = MailInputs.freeze(to);
            cc = MailInputs.freeze(cc);
            bcc = MailInputs.freeze(bcc);
            attachmentDocumentIds = MailInputs.freeze(attachmentDocumentIds);
        }

        /**
         * @return immutable to recipients
         */
        @Override
        public List<Recipient> to() {
            return MailInputs.freeze(to);
        }

        /**
         * @return immutable cc recipients
         */
        @Override
        public List<Recipient> cc() {
            return MailInputs.freeze(cc);
        }

        /**
         * @return immutable blind-copy field, always rejected by the service
         */
        @Override
        public List<String> bcc() {
            return MailInputs.freeze(bcc);
        }

        /**
         * @return immutable attachment identifiers
         */
        @Override
        public List<String> attachmentDocumentIds() {
            return MailInputs.freeze(attachmentDocumentIds);
        }
    }

    /**
     * Publish a draft after the sender confirms its resolved recipient list.
     *
     * @return the immutable command
     */
    public record SendCommand(String organizationId, String actor, String draftId,
                              List<String> confirmedRecipients, String clientMessageId) {

        /**
         * Freeze the confirmation list, keeping an omitted field null.
         */
        public SendCommand {
            confirmedRecipients = confirmedRecipients == null ? null : List.copyOf(confirmedRecipients);
        }

        /**
         * @return immutable confirmed recipients, or null when not confirmed
         */
        @Override
        public List<String> confirmedRecipients() {
            return confirmedRecipients == null ? null : List.copyOf(confirmedRecipients);
        }
    }

    /**
     * Create a reply draft, optionally addressing every original participant.
     *
     * @return the immutable command
     */
    public record ReplyCommand(String organizationId, String actor, String messageId, boolean replyAll,
                               String body) {
    }

    /**
     * Create a forward draft that keeps the original's attachment links.
     *
     * @return the immutable command
     */
    public record ForwardCommand(String organizationId, String actor, String messageId, List<Recipient> to,
                                 List<Recipient> cc, String body) {

        /**
         * Freeze both recipient lists.
         */
        public ForwardCommand {
            to = MailInputs.freeze(to);
            cc = MailInputs.freeze(cc);
        }

        /**
         * @return immutable to recipients
         */
        @Override
        public List<Recipient> to() {
            return MailInputs.freeze(to);
        }

        /**
         * @return immutable cc recipients
         */
        @Override
        public List<Recipient> cc() {
            return MailInputs.freeze(cc);
        }
    }

    /**
     * Move a message into a folder of the acting person.
     *
     * @return the immutable command
     * @param organizationId organization identifier derived from the tenant context
     * @param actor value supplied by the caller
     * @param messageId message identifier in the active organization
     * @param folder value supplied by the caller
     */
    public record MoveCommand(String organizationId, String actor, String messageId, MailFolder folder) {
    }

    /**
     * Set or clear one flag on a visible message.
     *
     * @return the immutable command
     */
    public record FlagCommand(String organizationId, String actor, String messageId, String flag,
                              boolean enabled) {
    }
}
