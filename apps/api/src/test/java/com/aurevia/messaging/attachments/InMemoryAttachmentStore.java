package com.aurevia.messaging.attachments;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.AttachmentEvent;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;

/**
 * Thread-safe, synthetic in-memory implementation of {@code AttachmentStore}.
 *
 * <p>Keys embed the organization so two tenants may use identical local
 * identifiers without ever sharing a row. Reads return immutable snapshots;
 * the fact ledger is append-only from the caller's perspective.</p>
 */
public final class InMemoryAttachmentStore implements AttachmentStore {

    private final ConcurrentHashMap<String, ConnectionGrant> grants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Attachment> attachments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<AttachmentEvent>> events =
            new ConcurrentHashMap<>();

    /**
     * Create an empty store.
     */
    public InMemoryAttachmentStore() {
    }

    @Override
    public Optional<ConnectionGrant> grant(String organizationId, String connectionId) {
        return Optional.ofNullable(grants.get(key(organizationId, connectionId)));
    }

    @Override
    public void putGrant(ConnectionGrant grant) {
        grants.put(key(grant.organizationId(), grant.connectionId()), grant);
    }

    @Override
    public Optional<Attachment> attachment(String organizationId, String id) {
        return Optional.ofNullable(attachments.get(key(organizationId, id)));
    }

    @Override
    public void putAttachment(Attachment attachment) {
        attachments.put(key(attachment.organizationId(), attachment.id()), attachment);
    }

    @Override
    public List<Attachment> attachments(String organizationId) {
        return attachments.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(organizationId + "|"))
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    @Override
    public void appendEvent(AttachmentEvent event) {
        events.computeIfAbsent(event.organizationId(), ignored -> new CopyOnWriteArrayList<>()).add(event);
    }

    @Override
    public List<AttachmentEvent> events(String organizationId) {
        return List.copyOf(events.getOrDefault(organizationId, new CopyOnWriteArrayList<>()));
    }

    private static String key(String organizationId, String id) {
        return organizationId + "|" + id;
    }
}
