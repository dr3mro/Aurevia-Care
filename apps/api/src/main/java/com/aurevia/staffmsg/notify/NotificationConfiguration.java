package com.aurevia.staffmsg.notify;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aurevia.api.ApiException;
import com.aurevia.events.DurableEventContract.PublishRequest;
import com.aurevia.events.DurableEventService;
import com.aurevia.staffmsg.attachments.StaffAttachmentInputs;
import com.aurevia.staffmsg.notify.NotificationContract.AutoReplyReceipt;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationDraft;
import com.aurevia.staffmsg.notify.NotificationContract.NotificationSettings;
import com.aurevia.tenant.TenantContext;

/**
 * Wiring for the notification ports using existing domain beans (MSG-06, ARC-07).
 *
 * <p>The notifier publishes identifier-free drafts through the durable outbox, mirroring
 * the reference notifier. The settings adapter is process-local because persistence is
 * outside this story's scope; a durable, RLS-protected implementation belongs to the
 * follow-up task recorded in the hand-off notes. No production HTTP route is added, so
 * route parity with the out-of-scope contract is unaffected.</p>
 */
@Configuration
public class NotificationConfiguration {

    /**
     * Provide the tenant-scoped preferences and reply-ledger port.
     *
     * @return the process-local adapter
     */
    @Bean
    public NotificationSettingsPort notificationSettingsPort() {
        return new ProcessLocalSettings();
    }

    /**
     * Provide the identifier-free notifier backed by the durable outbox.
     *
     * @param events durable outbox service
     * @return the outbox adapter
     */
    @Bean
    public NotificationNotifier notificationNotifier(DurableEventService events) {
        return new OutboxNotifier(events);
    }

    /**
     * Provide the notification decision service with its injected boundaries.
     *
     * @param clock    application clock
     * @param settings preferences and reply ledger
     * @param notifier identifier-free dispatch
     * @return the wired decision service
     */
    @Bean
    public NotificationService notificationService(Clock clock, NotificationSettingsPort settings,
                                                   NotificationNotifier notifier) {
        return new NotificationService(clock, settings, notifier);
    }

    /** Process-local settings and reply ledger; not durable, replaced by a Jdbc store in a follow-up. */
    static final class ProcessLocalSettings implements NotificationSettingsPort {

        private static final String CROSS_TENANT = "CROSS_TENANT";

        private final Map<String, NotificationSettings> settings = new ConcurrentHashMap<>();
        private final Set<String> replies = ConcurrentHashMap.newKeySet();

        @Override
        public Optional<NotificationSettings> findSettings(String organizationId, String subject) {
            guard(organizationId);
            return Optional.ofNullable(settings.get(key(organizationId, subject)));
        }

        @Override
        public void saveSettings(NotificationSettings value) {
            guard(value.organizationId());
            settings.put(key(value.organizationId(), value.subject()), value);
        }

        @Override
        public boolean isConversationMuted(String organizationId, String conversationId, String subject) {
            guard(organizationId);
            return findSettings(organizationId, subject)
                    .map(value -> value.mutes(conversationId))
                    .orElse(false);
        }

        @Override
        public boolean tryRecordAutoReply(AutoReplyReceipt receipt) {
            guard(receipt.organizationId());
            return replies.add(key(receipt.organizationId(), receipt.recipientSubject(),
                    receipt.senderSubject(), receipt.utcDay().toString()));
        }

        /** Fail closed when the caller's active organization does not own the row. */
        private void guard(String organizationId) {
            TenantContext context = TenantContext.current();
            if (context == null || !organizationId.equals(context.organizationId())) {
                throw new ApiException(403, CROSS_TENANT, "tenant scope mismatch");
            }
        }

        private static String key(String... parts) {
            return String.join("\u0000", parts);
        }
    }

    /** Publishes the fixed digest of a content-free draft to the durable outbox. */
    static final class OutboxNotifier implements NotificationNotifier {

        private static final String SENDER = "staff-notification";
        private static final String NAMESPACE = "staff-notification";

        private final DurableEventService events;

        OutboxNotifier(DurableEventService events) {
            this.events = events;
        }

        @Override
        public void dispatch(NotificationDraft draft) {
            // The digest covers only the two fixed phrases, never a body or a name.
            String payload = draft.text() + "|" + draft.notice();
            String digest = StaffAttachmentInputs.checksum(payload.getBytes(StandardCharsets.UTF_8));
            // Key on the per-message id: identical text in one conversation must still
            // publish a distinct outbox event, and retries of that message dedupe safely.
            String idempotencyKey = draft.organizationId() + ":" + SENDER + ":"
                    + draft.eventType() + ":" + draft.messageId();
            events.publish(new PublishRequest(draft.messageId(), 1, SENDER, draft.eventType(),
                    NAMESPACE, digest, idempotencyKey));
        }
    }
}
