package com.aurevia.staffmsg.notify;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import com.aurevia.api.ApiException;
import com.aurevia.staffmsg.mail.MailInputs;
import com.aurevia.staffmsg.notify.NotificationContract.AutoReplyReceipt;
import com.aurevia.staffmsg.notify.NotificationContract.DndWindow;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationDraft;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationOutcome;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationSettings;
import com.aurevia.staffmsg.notify.NotificationContract.SettingsCommand;
import com.aurevia.staffmsg.notify.NotificationContract.SuppressionReason;
import com.aurevia.tenant.TenantContext;

/**
 * Domain decisions for staff notifications.
 *
 * <p>Tenant and actor are derived from the bound {@link com.aurevia.tenant.TenantContext}
 * on every call; a missing or blank organization fails closed. This service holds no
 * reference to a conversation store or read cursor: suppressing a draft is exactly
 * "do not call the notifier", so unread counts remain the sole responsibility of the
 * instant-messaging service and can never be altered here. Urgency only appends a fixed
 * notice to the same content-free draft and never starts an emergency escalation.</p>
 */
public final class NotificationService {

    private final Clock clock;
    private final NotificationSettingsPort settings;
    private final NotificationNotifier notifier;

    /**
     * Create the decision service with injected, deterministic boundaries.
     *
     * @param clock    application clock for quiet-window and UTC-day decisions
     * @param settings tenant-scoped preferences and reply ledger
     * @param notifier identifier-free dispatch boundary
     */
    public NotificationService(Clock clock, NotificationSettingsPort settings, NotificationNotifier notifier) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /**
     * Save the current subject's notification preferences.
     *
     * @param command preferences to store; organization and actor come from the tenant context
     * @return the stored, frozen preferences
     * @throws ApiException when the identity is unusable, the command is absent or an
     *         out-of-office flag has no delegate
     */
    public NotificationSettings saveSettings(SettingsCommand command) {
        TenantContext context = TenantContext.current();
        String organizationId = requireOrganization(context);
        String subject = requireActor(context);
        if (command == null) {
            throw new ApiException(422, "INVALID_SETTINGS", "settings are required");
        }
        String delegate = command.outOfOffice() ? requireDelegate(command.delegateSubject()) : null;
        NotificationSettings value = new NotificationSettings(organizationId, subject,
                command.mutedConversationIds(), command.dndWindow(), command.outOfOffice(), delegate);
        settings.saveSettings(value);
        return value;
    }

    /**
     * Read the current subject's preferences, concealed as defaults when none are stored.
     *
     * @return preferences owned by this tenant and subject
     * @throws ApiException when the tenant context is missing or unusable
     */
    public NotificationSettings settings() {
        TenantContext context = TenantContext.current();
        String organizationId = requireOrganization(context);
        String subject = requireActor(context);
        return settings.findSettings(organizationId, subject)
                .orElseGet(() -> NotificationSettings.defaults(organizationId, subject));
    }

    /**
     * Decide whether to notify a recipient about a new message.
     *
     * <p>Muted conversations and an active quiet window suppress only the dispatch;
     * no read cursor or message state is touched. Every produced draft uses the single
     * approved phrase, and urgency adds only the fixed emergency-procedure notice.</p>
     *
     * @param conversationId opaque conversation handle
     * @param urgent         whether the sender marked the message urgent
     * @return the decision and, when dispatched, the identifier-free draft
     * @throws ApiException when the identity or conversation handle is unusable
     */
    public NotificationOutcome notifyRecipient(String conversationId, boolean urgent) {
        TenantContext context = TenantContext.current();
        String organizationId = requireOrganization(context);
        String subject = requireActor(context);
        String conversation = requireConversation(conversationId);
        NotificationSettings current = settings.findSettings(organizationId, subject)
                .orElseGet(() -> NotificationSettings.defaults(organizationId, subject));
        if (settings.isConversationMuted(organizationId, conversation, subject)) {
            return NotificationOutcome.suppressed(SuppressionReason.MUTED);
        }
        DndWindow window = current.dndWindow();
        if (window != null && window.contains(clock.instant().atZone(clock.getZone()).toLocalTime())) {
            return NotificationOutcome.suppressed(SuppressionReason.DO_NOT_DISTURB);
        }
        NotificationDraft draft = NotificationDraft.of(organizationId, conversation, subject, urgent);
        notifier.dispatch(draft);
        return NotificationOutcome.dispatched(draft);
    }

    /**
     * Record one automatic out-of-office reply per sender per UTC day.
     *
     * @param conversationId opaque conversation handle
     * @param senderSubject  opaque subject that sent the mail
     * @return the receipt when this call was the first for the key, otherwise empty
     * @throws ApiException when the identity, sender or delegate is unusable
     */
    public Optional<AutoReplyReceipt> sendOutOfOfficeReply(String conversationId, String senderSubject) {
        TenantContext context = TenantContext.current();
        String organizationId = requireOrganization(context);
        String subject = requireActor(context);
        String conversation = requireConversation(conversationId);
        String sender = MailInputs.requireText(senderSubject, "INVALID_SENDER", "sender is required");
        NotificationSettings current = settings.findSettings(organizationId, subject)
                .orElseGet(() -> NotificationSettings.defaults(organizationId, subject));
        if (!current.outOfOffice()) {
            return Optional.empty();
        }
        String delegate = requireDelegate(current.delegateSubject());
        LocalDate day = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        AutoReplyReceipt receipt = AutoReplyReceipt.of(
                organizationId, conversation, subject, sender, day, delegate);
        if (!settings.tryRecordAutoReply(receipt)) {
            return Optional.empty();
        }
        return Optional.of(receipt);
    }

    private static String requireOrganization(TenantContext context) {
        String organizationId = context == null ? null : context.organizationId();
        if (organizationId == null || organizationId.isBlank()) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return organizationId;
    }

    private static String requireActor(TenantContext context) {
        return MailInputs.requireText(context.subject(), "INVALID_ACTOR", "actor is required");
    }

    private static String requireConversation(String conversationId) {
        return MailInputs.requireText(conversationId, "INVALID_CONVERSATION", "conversation id is required");
    }

    private static String requireDelegate(String delegateSubject) {
        return MailInputs.requireText(delegateSubject, "DELEGATE_REQUIRED",
                "an out-of-office delegate is required");
    }
}
