package com.aurevia.staffmsg.mail;

import java.time.Instant;
import java.util.List;

import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Immutable value types of the email-like staff mail domain.
 *
 * <p>These are in-memory values shaped after the staff-messaging tables of the
 * earlier migration: a conversation of kind {@code mail_thread}, an immutable
 * message, mail metadata with recipients and flags, a folder per participant
 * and links to scanned documents. This slice does not project or persist them;
 * a durable store is a follow-up. Every collection is frozen on construction so
 * a value cannot be mutated after it leaves the service.</p>
 */
public final class MailContract {

    private MailContract() {
    }

    /**
     * Kind of a mail recipient. There is deliberately no blind-copy kind: every
     * recipient is visible to every other recipient on the confirmation step.
     */
    public enum RecipientKind {
        /** An individual staff subject. */
        PERSON("person"),
        /** All current holders of a role in a facility, expanded at send time. */
        ROLE("role"),
        /** The shared mailbox of a unit. */
        UNIT_MAILBOX("unit_mailbox");

        private final String wire;

        RecipientKind(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable stored key.
         *
         * @return lowercase wire value
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored recipient kind.
         *
         * @param value stored wire value
         * @return the matching kind
         * @throws IllegalArgumentException when the value is unknown
         */
        @JsonCreator
        public static RecipientKind from(String value) {
            for (RecipientKind candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown mail recipient kind: " + value);
        }
    }

    /**
     * A mail recipient as entered by the composer.
     *
     * @return the immutable value
     * @param kind value supplied by the caller
     * @param value value to persist in the active organization
     */
    public record Recipient(RecipientKind kind, String value) {

        /**
         * Reject missing recipient values at the trust boundary.
         */
        public Recipient {
            MailInputs.requireText(value, "INVALID_RECIPIENT", "recipient value is required");
        }

        /**
         * Address an individual staff subject.
         *
         * @param subject staff subject
         * @return a person recipient
         */
        public static Recipient person(String subject) {
            return new Recipient(RecipientKind.PERSON, subject);
        }

        /**
         * Address all current holders of a role in a facility.
         *
         * @param role role key
         * @return a role recipient
         */
        public static Recipient role(String role) {
            return new Recipient(RecipientKind.ROLE, role);
        }

        /**
         * Address the shared mailbox of a unit.
         *
         * @param mailbox unit mailbox subject
         * @return a unit mailbox recipient
         */
        public static Recipient unitMailbox(String mailbox) {
            return new Recipient(RecipientKind.UNIT_MAILBOX, mailbox);
        }
    }

    /**
     * A private, versioned mail draft owned by one subject.
     *
     * @return the immutable value
     */
    public record DraftView(String organizationId, String id, String facilityId, String ownerSubject,
                            int version, String subject, String body, List<Recipient> to, List<Recipient> cc,
                            List<String> attachmentDocumentIds, String threadId, Instant updatedAt) {

        /**
         * Freeze the three recipient and attachment lists.
         */
        public DraftView {
            to = List.copyOf(to);
            cc = List.copyOf(cc);
            attachmentDocumentIds = List.copyOf(attachmentDocumentIds);
        }

        /**
         * Return the immutable to-recipient list.
         *
         * @return to recipients
         */
        @Override
        public List<Recipient> to() {
            return List.copyOf(to);
        }

        /**
         * Return the immutable cc-recipient list.
         *
         * @return cc recipients
         */
        @Override
        public List<Recipient> cc() {
            return List.copyOf(cc);
        }

        /**
         * Return the immutable attachment list.
         *
         * @return attached document identifiers
         */
        @Override
        public List<String> attachmentDocumentIds() {
            return List.copyOf(attachmentDocumentIds);
        }
    }

    /**
     * A published, immutable mail message with the recipients resolved at send time.
     *
     * @return the immutable value
     */
    public record SentMail(String organizationId, String id, String facilityId, String threadId,
                           String subject, String body, String senderSubject, String senderRole,
                           Instant sentAt, List<Recipient> to, List<Recipient> cc,
                           List<String> resolvedRecipients, List<String> attachmentDocumentIds,
                           List<String> flags) {

        /**
         * Freeze every collection carried by the projection.
         */
        public SentMail {
            to = List.copyOf(to);
            cc = List.copyOf(cc);
            resolvedRecipients = List.copyOf(resolvedRecipients);
            attachmentDocumentIds = List.copyOf(attachmentDocumentIds);
            flags = List.copyOf(flags);
        }

        /**
         * Return the immutable to-recipient list.
         *
         * @return to recipients
         */
        @Override
        public List<Recipient> to() {
            return List.copyOf(to);
        }

        /**
         * Return the immutable cc-recipient list.
         *
         * @return cc recipients
         */
        @Override
        public List<Recipient> cc() {
            return List.copyOf(cc);
        }

        /**
         * Return the concrete recipients recorded at send time.
         *
         * @return resolved recipient subjects
         */
        @Override
        public List<String> resolvedRecipients() {
            return List.copyOf(resolvedRecipients);
        }

        /**
         * Return the immutable attachment list.
         *
         * @return attached document identifiers
         */
        @Override
        public List<String> attachmentDocumentIds() {
            return List.copyOf(attachmentDocumentIds);
        }

        /**
         * Return the immutable flag list.
         *
         * @return flags set on the message
         */
        @Override
        public List<String> flags() {
            return List.copyOf(flags);
        }
    }

    /**
     * One message with the folder placement of the person reading it.
     *
     * <p>The folder is per participant; the message content and its flags are
     * shared, matching the message-level flags of the staff-mail schema.</p>
     *
     * @return the immutable value
     * @param message value supplied by the caller
     * @param viewerFolder value supplied by the caller
     */
    public record MailView(SentMail message, MailFolder viewerFolder) {
    }

    /**
     * A thread grouped by normalized subject and a shared reference message.
     *
     * @return the immutable value
     * @param threadId value supplied by the caller
     * @param subject authenticated subject derived from the tenant context
     * @param messages value supplied by the caller
     */
    public record ThreadView(String threadId, String subject, List<MailView> messages) {

        /**
         * Freeze the message list.
         */
        public ThreadView {
            messages = List.copyOf(messages);
        }

        /**
         * Return the immutable message list.
         *
         * @return messages in this thread
         */
        @Override
        public List<MailView> messages() {
            return List.copyOf(messages);
        }
    }
}
