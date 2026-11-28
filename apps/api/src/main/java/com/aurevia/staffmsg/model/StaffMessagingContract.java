package com.aurevia.staffmsg.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for staff-to-staff messaging records.
 *
 * <p>Staff messaging is a separate domain from the patient-to-unit conversations
 * of patient-to-unit conversations: a staff conversation is scoped to an
 * organization and a facility, never to a verified patient connection. Message content is immutable
 * once stored; a correction is a new, later message that points at the original.
 * Sequence allocation is strictly {@code last + 1} so a conversation has a
 * gap-free order, and mail folders belong to a participant rather than to the
 * This class owns only immutable value types; persistence and authorization
 * live in sibling packages of this domain.</p>
 */
public final class StaffMessagingContract {

    private StaffMessagingContract() {
    }

    /**
     * Kind of staff conversation. A mail thread is the email-like variant;
     * direct and group are the instant-chat variants.
     */
    public enum ConversationKind {
        /** One-to-one instant conversation. */
        DIRECT("direct"),
        /** Instant conversation with several participants. */
        GROUP("group"),
        /** Email-like thread with subject, recipients and folders. */
        MAIL_THREAD("mail_thread");

        private final String wire;

        ConversationKind(String wire) {
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
         * Parse a stored conversation kind.
         *
         * @param value stored wire value
         * @return the matching kind
         * @throws IllegalArgumentException when the value is unknown
         */
        public static ConversationKind from(String value) {
            for (ConversationKind candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown staff conversation kind: " + value);
        }
    }

    /**
     * A participant's role in a staff conversation. Owners may manage the
     * conversation; members may read and send within it.
     */
    public enum ParticipantRole {
        /** Participant who created or owns the conversation. */
        OWNER("owner"),
        /** Ordinary participant. */
        MEMBER("member");

        private final String wire;

        ParticipantRole(String wire) {
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
         * Parse a stored participant role.
         *
         * @param value stored wire value
         * @return the matching role
         * @throws IllegalArgumentException when the value is unknown
         */
        public static ParticipantRole from(String value) {
            for (ParticipantRole candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown staff participant role: " + value);
        }
    }

    /**
     * Draft state of the mail metadata attached to a message.
     */
    public enum DraftState {
        /** Private, not yet published draft. */
        DRAFT("draft"),
        /** Published message. */
        SENT("sent");

        private final String wire;

        DraftState(String wire) {
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
         * Parse a stored draft state.
         *
         * @param value stored wire value
         * @return the matching state
         * @throws IllegalArgumentException when the value is unknown
         */
        public static DraftState from(String value) {
            for (DraftState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown mail draft state: " + value);
        }
    }

    /**
     * Scan verdict for an attachment link. Only a clean file may be
     * downloaded after the message is published.
     */
    public enum ScanState {
        /** Uploaded but not scanned yet. */
        PENDING("pending"),
        /** Scanned with no finding. */
        CLEAN("clean"),
        /** Scanned with a finding; must not be attached to a sent message. */
        QUARANTINED("quarantined");

        private final String wire;

        ScanState(String wire) {
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
         * Parse a stored scan state.
         *
         * @param value stored wire value
         * @return the matching state
         * @throws IllegalArgumentException when the value is unknown
         */
        public static ScanState from(String value) {
            for (ScanState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown attachment scan state: " + value);
        }
    }

    /**
     * Mail folder, held per participant so the same message can be in different
     * folders for different recipients.
     */
    public enum MailFolder {
        /** Inbox of the participant. */
        INBOX("inbox"),
        /** Sent items of the participant. */
        SENT("sent"),
        /** Drafts of the participant. */
        DRAFTS("drafts"),
        /** Archived items of the participant. */
        ARCHIVE("archive"),
        /** Hidden by the participant and retained under the retention policy. */
        TRASH("trash");

        private final String wire;

        MailFolder(String wire) {
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
         * Parse a stored mail folder.
         *
         * @param value stored wire value
         * @return the matching folder
         * @throws IllegalArgumentException when the value is unknown
         */
        public static MailFolder from(String value) {
            for (MailFolder candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown mail folder: " + value);
        }
    }

    /**
     * A staff conversation, scoped to one tenant and one facility.
     *
     * @return the immutable value
     */
    public record StaffConversation(String organizationId, String facilityId, String id,
                                    ConversationKind kind, String topic, String createdBySubject,
                                    Instant createdAt) {

        /**
         * Report whether this conversation is the email-like variant.
         *
         * @return {@code true} only for a mail thread
         */
        public boolean isMailThread() {
            return kind == ConversationKind.MAIL_THREAD;
        }
    }

    /**
     * One membership of a staff subject in a conversation. A left timestamp is
     * history, not deletion: earlier messages stay visible to a former member.
     *
     * @return the immutable value
     */
    public record StaffParticipant(String organizationId, String conversationId, String subject,
                                   ParticipantRole role, Instant joinedAt, Instant leftAt,
                                   boolean muted) {

        /**
         * Decide whether this membership was active at an instant.
         *
         * @param at instant to evaluate
         * @return {@code true} from the join instant up to, but not including, the leave instant
         */
        public boolean isActiveAt(Instant at) {
            return !at.isBefore(joinedAt) && (leftAt == null || at.isBefore(leftAt));
        }
    }

    /**
     * Per-conversation sequence cursor. A new message takes
     * {@link com.aurevia.staffmsg.model.StaffMessagingContract.MessageSequence#next()}
     * and advances the cursor by exactly one, so the stored sequence cannot skip.
     *
     * @return the immutable value
     * @param conversationId conversation identifier in the active organization
     * @param lastSequence value supplied by the caller
     */
    public record MessageSequence(String conversationId, long lastSequence) {

        /**
         * Create the cursor of a conversation with no messages yet.
         *
         * @param conversationId owning conversation
         * @return an empty cursor
         */
        public static MessageSequence initial(String conversationId) {
            return new MessageSequence(conversationId, 0L);
        }

        /**
         * Return the sequence a message would take from this cursor.
         *
         * @return the next one-based sequence
         * @throws IllegalArgumentException when the cursor was built from a negative sequence
         */
        public long next() {
            if (lastSequence < 0L) {
                throw new IllegalArgumentException("sequence cannot be negative");
            }
            return lastSequence + 1L;
        }

        /**
         * Advance the cursor by exactly one.
         *
         * @return the advanced cursor
         * @throws IllegalArgumentException when the cursor was built from a negative sequence
         */
        public MessageSequence advance() {
            return new MessageSequence(conversationId, next());
        }
    }

    /**
     * One immutable message in a staff conversation. A correction is a new row
     * with the next sequence and a reference to the message it replaces; the
     * original is never changed.
     *
     * @return the immutable value
     */
    public record StaffMessage(String organizationId, String id, String conversationId,
                               long sequence, String senderSubject, String senderRole,
                               String body, Instant sentAt, String correctedMessageId) {

        /**
         * Report whether this message corrects an earlier one.
         *
         * @return {@code true} when a corrected message is referenced
         */
        public boolean isCorrection() {
            return correctedMessageId != null;
        }

        /**
         * Create the correction of this message as a new, later message.
         *
         * @param newBody corrected content
         * @param actorSubject subject publishing the correction
         * @param at server publication time
         * @param sequence cursor carrying the next sequence for the conversation
         * @return a new message pointing at this one
         */
        public StaffMessage correct(String newBody, String actorSubject, Instant at,
                                    MessageSequence sequence) {
            return new StaffMessage(organizationId, UUID.randomUUID().toString(), conversationId,
                    sequence.next(), actorSubject, senderRole, newBody, at, id);
        }
    }

    /**
     * Mail-specific metadata for a sent or draft message. Recipients and flags
     * are frozen so a projection cannot be mutated after it leaves the store.
     *
     * @return the immutable value
     */
    public record MailMetadata(String organizationId, String messageId, String subject,
                               List<String> toRecipients, List<String> ccRecipients,
                               List<String> flags, DraftState draftState) {

        /**
         * Freeze all three lists on construction.
         */
        public MailMetadata {
            toRecipients = List.copyOf(toRecipients);
            ccRecipients = List.copyOf(ccRecipients);
            flags = List.copyOf(flags);
        }

        /**
         * Return the immutable recipient list.
         *
         * @return to recipients
         */
        @Override
        public List<String> toRecipients() {
            return List.copyOf(toRecipients);
        }

        /**
         * Return the immutable copy list.
         *
         * @return cc recipients
         */
        @Override
        public List<String> ccRecipients() {
            return List.copyOf(ccRecipients);
        }

        /**
         * Return the immutable flag list.
         *
         * @return flags
         */
        @Override
        public List<String> flags() {
            return List.copyOf(flags);
        }

        /**
         * Report whether the message is still a draft.
         *
         * @return {@code true} only for a draft
         */
        public boolean isDraft() {
            return draftState == DraftState.DRAFT;
        }

        /**
         * Report whether a named flag is set.
         *
         * @param flag flag to look for
         * @return {@code true} when present
         */
        public boolean hasFlag(String flag) {
            return flags.contains(flag);
        }
    }

    /**
     * One participant's folder placement for one mail message.
     *
     * @return the immutable value
     */
    public record MailFolderAssignment(String organizationId, String messageId,
                                       String participantSubject, MailFolder folder) {
    }

    /**
     * Link from a message to a scanned document. Checksum and scan state are
     * carried so a reader can enforce the clean-verdict rule before download.
     *
     * @return the immutable value
     */
    public record AttachmentLink(String organizationId, String id, String messageId,
                                 String documentId, String checksum, ScanState scanState) {

        /**
         * Report whether the file may be downloaded after publication.
         *
         * @return {@code true} only for a clean scan
         */
        public boolean isDownloadable() {
            return scanState == ScanState.CLEAN;
        }

        /**
         * Return a copy carrying a new scan verdict.
         *
         * @param next the updated scan state
         * @return a new link; this value is unchanged
         */
        public AttachmentLink withScanState(ScanState next) {
            return new AttachmentLink(organizationId, id, messageId, documentId, checksum, next);
        }
    }
}
