package com.aurevia.smartclinic.staffmsg.notify;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.aurevia.smartclinic.staffmsg.notify.NotificationContract.AutoReplyReceipt;
import com.aurevia.smartclinic.staffmsg.notify.NotificationContract.NotificationSettings;

/** Synthetic in-memory implementation of the settings port used only by unit tests. */
final class InMemoryNotificationSettingsPort implements NotificationSettingsPort {

    private final Map<String, NotificationSettings> settings = new ConcurrentHashMap<>();
    private final Set<String> muted = ConcurrentHashMap.newKeySet();
    private final Set<String> replyKeys = ConcurrentHashMap.newKeySet();
    private final List<AutoReplyReceipt> replies = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public Optional<NotificationSettings> findSettings(String organizationId, String subject) {
        return Optional.ofNullable(settings.get(key(organizationId, subject)));
    }

    @Override
    public void saveSettings(NotificationSettings value) {
        settings.put(key(value.organizationId(), value.subject()), value);
        for (String conversationId : value.mutedConversationIds()) {
            muted.add(key(value.organizationId(), value.subject(), conversationId));
        }
    }

    @Override
    public boolean isConversationMuted(String organizationId, String conversationId, String subject) {
        return muted.contains(key(organizationId, subject, conversationId));
    }

    @Override
    public boolean tryRecordAutoReply(AutoReplyReceipt receipt) {
        boolean first = replyKeys.add(key(receipt.organizationId(), receipt.recipientSubject(),
                receipt.senderSubject(), receipt.utcDay().toString()));
        if (first) {
            replies.add(receipt);
        }
        return first;
    }

    /** Return recorded auto-replies in order for assertions; synthetic values only. */
    List<AutoReplyReceipt> recordedReplies() {
        return List.copyOf(replies);
    }

    private static String key(String... parts) {
        return String.join("\u0000", parts);
    }
}
