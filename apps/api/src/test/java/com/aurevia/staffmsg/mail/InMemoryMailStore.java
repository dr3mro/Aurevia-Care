package com.aurevia.staffmsg.mail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.aurevia.staffmsg.mail.MailContract.DraftView;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;

/**
 * Test-only mail store that keeps the same invariants as the JDBC adapter in a
 * process-local map. Only unit and HTTP tests outside PostgreSQL use it; the
 * durable adapter is exercised by the integration tests.
 */
final class InMemoryMailStore implements MailStore {

    private final Map<String, DraftView> drafts = new LinkedHashMap<>();
    private final Map<String, SentMail> messages = new LinkedHashMap<>();
    private final Map<String, Map<String, MailFolder>> folders = new HashMap<>();
    private final Map<String, String> idempotency = new HashMap<>();

    @Override
    public void saveDraft(String organizationId, DraftView draft) {
        drafts.put(key(organizationId, draft.id()), draft);
    }

    @Override
    public Optional<DraftView> draft(String organizationId, String draftId, String actor) {
        DraftView value = drafts.get(key(organizationId, draftId));
        if (value == null || !value.ownerSubject().equals(actor)) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    @Override
    public Optional<SentMail> message(String organizationId, String messageId) {
        return Optional.ofNullable(messages.get(key(organizationId, messageId)));
    }

    @Override
    public List<SentMail> messages(String organizationId) {
        List<SentMail> result = new ArrayList<>();
        messages.forEach((storedKey, message) -> {
            if (storedKey.startsWith(organizationId + "|")) {
                result.add(message);
            }
        });
        return List.copyOf(result);
    }

    @Override
    public void setFlags(String organizationId, String messageId, List<String> flags) {
        SentMail message = messages.get(key(organizationId, messageId));
        if (message != null) {
            messages.put(key(organizationId, messageId), new SentMail(message.organizationId(), message.id(),
                    message.facilityId(), message.threadId(), message.subject(), message.body(),
                    message.senderSubject(), message.senderRole(), message.sentAt(), message.to(), message.cc(),
                    message.resolvedRecipients(), message.attachmentDocumentIds(), List.copyOf(flags)));
        }
    }

    @Override
    public Map<String, MailFolder> folders(String organizationId, String actor) {
        return new HashMap<>(folders.getOrDefault(viewerKey(organizationId, actor), Map.of()));
    }

    @Override
    public void setFolder(String organizationId, String messageId, String actor, MailFolder folder) {
        folders.computeIfAbsent(viewerKey(organizationId, actor), ignored -> new HashMap<>())
                .put(messageId, folder);
    }

    @Override
    public Optional<String> replay(String organizationId, String actor, String clientMessageId) {
        return Optional.ofNullable(idempotency.get(retryKey(organizationId, actor, clientMessageId)));
    }

    @Override
    public String publish(String organizationId, String actor, String clientMessageId, String draftId,
                          SentMail message, List<String> recipients) {
        String replayKey = retryKey(organizationId, actor, clientMessageId);
        String existing = idempotency.get(replayKey);
        if (existing != null) {
            return existing;
        }
        messages.put(key(organizationId, message.id()), message);
        setFolder(organizationId, message.id(), actor, MailFolder.SENT);
        for (String recipient : recipients) {
            setFolder(organizationId, message.id(), recipient, MailFolder.INBOX);
        }
        drafts.remove(key(organizationId, draftId));
        idempotency.put(replayKey, message.id());
        return message.id();
    }

    private static String key(String organizationId, String id) {
        return organizationId + "|" + id;
    }

    private static String viewerKey(String organizationId, String actor) {
        return organizationId + "|" + actor;
    }

    private static String retryKey(String organizationId, String actor, String clientMessageId) {
        return organizationId + "|" + actor + "|" + clientMessageId;
    }
}
