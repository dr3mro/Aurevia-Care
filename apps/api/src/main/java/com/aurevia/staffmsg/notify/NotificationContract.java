package com.aurevia.staffmsg.notify;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import com.aurevia.staffmsg.mail.MailInputs;

/**
 * Frozen value types for content-free staff notifications.
 *
 * <p>A notification draft carries only a fixed approved phrase and opaque handles.
 * No subject line, message body, sender name or patient identifier ever enters a
 * draft or the digest published to the outbox. Per-person preferences keep mute
 * handles, an optional quiet window and an out-of-office delegate; the durable
 * implementation sources mute from the existing participant record and binds the
 * auto-reply ledger to a tenant, recipient, sender and UTC day.</p>
 */
public final class NotificationContract {

    private static final int SECONDS_PER_DAY = 86_400;

    /** The single approved push phrase. It contains no identity or clinical content. */
    public static final String GENERIC_TEXT = "You have a new message";

    /** Fixed urgent wording: urgency is a flag, not an emergency escalation. */
    public static final String URGENT_NOTICE =
            "Urgent: this is not for emergencies; use the facility emergency procedure.";

    private static final String NEW_MESSAGE_EVENT = "staff-new-message";

    private NotificationContract() {
    }

    /** Why a recipient received no notification, while their unread count still advanced. */
    public enum SuppressionReason {
        /** A draft was produced and dispatched. */
        NONE,
        /** The recipient muted this conversation. */
        MUTED,
        /** The recipient's do-not-disturb window covers the current time. */
        DO_NOT_DISTURB
    }

    /**
     * A local quiet window. A window whose start equals its end covers the whole day.
     *
     * @param start inclusive time in the injected clock's zone (UTC in production)
     * @param end   exclusive time in the injected clock's zone (UTC in production)
     * @return this record
     */
    public record DndWindow(LocalTime start, LocalTime end) {

        /**
         * Create a quiet window, rejecting missing bounds.
         *
         * @param start inclusive start time
         * @param end   exclusive end time
         * @return the immutable window
         */
        public static DndWindow between(LocalTime start, LocalTime end) {
            return new DndWindow(java.util.Objects.requireNonNull(start, "start"),
                    java.util.Objects.requireNonNull(end, "end"));
        }

        /**
         * Test whether a local time falls inside the window, including windows that
         * cross midnight. Modular arithmetic keeps a circular interval to one branch.
         *
         * @param at local time to test
         * @return {@code true} when the time is quiet
         */
        public boolean contains(LocalTime at) {
            int duration = (end.toSecondOfDay() - start.toSecondOfDay() + SECONDS_PER_DAY) % SECONDS_PER_DAY;
            if (duration == 0) {
                return true;
            }
            int offset = (at.toSecondOfDay() - start.toSecondOfDay() + SECONDS_PER_DAY) % SECONDS_PER_DAY;
            return offset < duration;
        }
    }

    /**
     * Per-person notification preferences, always scoped to one tenant and subject.
     *
     * @param organizationId      owning tenant
     * @param subject             owning staff subject
     * @param mutedConversationIds opaque conversation handles the recipient muted
     * @param dndWindow           quiet window, or null outside do-not-disturb
     * @param outOfOffice         whether an automatic reply is active
     * @param delegateSubject     opaque subject named to handle mail while away
     */
    public record NotificationSettings(String organizationId, String subject,
                                       Set<String> mutedConversationIds, DndWindow dndWindow,
                                       boolean outOfOffice, String delegateSubject) {

        /** Freeze the mute set so a caller cannot widen it after the read. */
        public NotificationSettings {
            mutedConversationIds = mutedConversationIds == null
                    ? Set.of() : Set.copyOf(mutedConversationIds);
        }

        /**
         * Build the concealed default for a subject with no stored preferences.
         *
         * @param organizationId owning tenant
         * @param subject        owning staff subject
         * @return an unmuted, always-available default
         */
        public static NotificationSettings defaults(String organizationId, String subject) {
            return new NotificationSettings(organizationId, subject, Set.of(), null, false, null);
        }

        /**
         * Decide whether this recipient muted a conversation.
         *
         * @param conversationId opaque conversation handle
         * @return {@code true} when notifications for it are suppressed
         */
        public boolean mutes(String conversationId) {
            return mutedConversationIds.contains(conversationId);
        }
    }

    /**
     * Settings edit command. Organization and actor are never accepted from the body.
     *
     * @param mutedConversationIds opaque conversation handles to mute
     * @param dndWindow            optional quiet window
     * @param outOfOffice          whether to enable the automatic reply
     * @param delegateSubject      opaque delegate for out-of-office mail
     */
    public record SettingsCommand(Set<String> mutedConversationIds, DndWindow dndWindow,
                                  boolean outOfOffice, String delegateSubject) {

        /** Freeze the client set so a later mutation cannot widen the saved mute list. */
        public SettingsCommand {
            mutedConversationIds = mutedConversationIds == null
                    ? Set.of() : Set.copyOf(mutedConversationIds);
        }

        /**
         * Return a defensive copy; the frozen field is never handed out directly.
         *
         * @return immutable mute set
         */
        @Override
        public Set<String> mutedConversationIds() {
            return Set.copyOf(mutedConversationIds);
        }
    }

    /**
     * Fixed-wording urgent notice appended to a draft when the sender marks a message urgent.
     *
     * @param text the fixed emergency-procedure wording
     * @return this record
     */
    public record UrgentNotice(String text) {

        /**
         * Return the approved wording; urgency must never replace the facility procedure.
         *
         * @return the immutable notice
         */
        public static UrgentNotice fixed() {
            return new UrgentNotice(URGENT_NOTICE);
        }
    }

    /**
     * A content-free notification for one recipient.
     *
     * @param organizationId  tenant stamped by the server
     * @param messageId       opaque per-message id that keeps distinct outbox events distinct
     * @param conversationId  opaque conversation handle
     * @param recipientSubject opaque recipient subject
     * @param text             the single fixed phrase
     * @param urgent           whether the sender marked the message urgent
     * @param notice           fixed urgent wording, or null when not urgent
     */
    public record NotificationDraft(String organizationId, String messageId, String conversationId,
                                    String recipientSubject, String text, boolean urgent,
                                    UrgentNotice notice) {

        /**
         * Build a draft free of clinical and patient identifiers with the single approved phrase.
         *
         * <p>A fresh opaque id is generated per draft so two messages in the same
         * conversation are two distinct outbox events even though their text is identical.</p>
         *
         * @param organizationId  owning tenant
         * @param conversationId  opaque conversation handle
         * @param recipientSubject opaque recipient subject
         * @param urgent           whether the sender marked the message urgent
         * @return the immutable draft
         */
        public static NotificationDraft of(String organizationId, String conversationId,
                                           String recipientSubject, boolean urgent) {
            return new NotificationDraft(organizationId, MailInputs.newId(),
                    MailInputs.requireText(conversationId, "INVALID_CONVERSATION",
                            "conversation id is required"),
                    recipientSubject, GENERIC_TEXT, urgent, urgent ? UrgentNotice.fixed() : null);
        }

        /**
         * Return the stable event type published to the outbox.
         *
         * @return the fixed event type key
         */
        public String eventType() {
            return NEW_MESSAGE_EVENT;
        }
    }

    /**
     * Proof that one out-of-office reply was recorded for a sender on a UTC day.
     *
     * @param organizationId  owning tenant
     * @param conversationId  opaque conversation handle
     * @param recipientSubject out-of-office owner
     * @param senderSubject    sender being answered
     * @param utcDay           UTC calendar day of the reply
     * @param delegateSubject  opaque delegate named by the owner
     */
    public record AutoReplyReceipt(String organizationId, String conversationId, String recipientSubject,
                                   String senderSubject, LocalDate utcDay, String delegateSubject) {

        /**
         * Create a dedupe-keyed receipt.
         *
         * @param organizationId   owning tenant
         * @param conversationId   opaque conversation handle
         * @param recipientSubject out-of-office owner
         * @param senderSubject    sender being answered
         * @param utcDay           UTC calendar day
         * @param delegateSubject  opaque delegate
         * @return the immutable receipt
         */
        public static AutoReplyReceipt of(String organizationId, String conversationId,
                                          String recipientSubject, String senderSubject, LocalDate utcDay,
                                          String delegateSubject) {
            return new AutoReplyReceipt(organizationId, conversationId, recipientSubject,
                    senderSubject, utcDay, delegateSubject);
        }
    }

    /**
     * Result of a notification decision, used to assert suppression without exposing content.
     *
     * @param dispatched whether the notifier was called
     * @param reason     the suppression reason, or NONE
     * @param draft      the dispatched draft, or null when suppressed
     * @return this record
     */
    public record NotificationOutcome(boolean dispatched, SuppressionReason reason, NotificationDraft draft) {

        /**
         * Record a suppressed notification; unread counts are unchanged by this service.
         *
         * @param reason why no draft was produced
         * @return the immutable outcome
         */
        public static NotificationOutcome suppressed(SuppressionReason reason) {
            return new NotificationOutcome(false, reason, null);
        }

        /**
         * Record a dispatched notification.
         *
         * @param draft the draft handed to the notifier
         * @return the immutable outcome
         */
        public static NotificationOutcome dispatched(NotificationDraft draft) {
            return new NotificationOutcome(true, SuppressionReason.NONE, draft);
        }
    }
}
