package com.aurevia.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.ReplayRecord;

/**
 * Synthetic, tenant-keyed in-memory adapter for ARC-05 delivery facts.
 *
 * <p>The adapter lets the durable-delivery rules be exercised deterministically
 * without a database. It is not authoritative: a production adapter persists
 * the same facts with forced tenant row-level security and an atomic outbox
 * commit. Every method takes the active organization and never merges rows
 * across tenants. This is a test fixture only; production uses
 * {@link com.aurevia.events.JdbcDeliveryStore}.</p>
 */
public class InMemoryDeliveryStore implements DeliveryStore {

    private final Map<String, String> roles = new ConcurrentHashMap<>();
    private final Set<String> activeSubjects = ConcurrentHashMap.newKeySet();
    private final Map<String, OutboxEvent> outbox = new ConcurrentHashMap<>();
    private final Map<String, OutboxEvent> idempotency = new ConcurrentHashMap<>();
    private final Map<String, List<DeliveryAuditEvent>> audits = new ConcurrentHashMap<>();
    private final Map<String, ConsumerReceipt> receipts = new ConcurrentHashMap<>();
    private final Map<String, DeadLetterEntry> deadLetters = new ConcurrentHashMap<>();
    private final List<ReplayRecord> replays = new CopyOnWriteArrayList<>();
    private final List<ExternalLink> links = new CopyOnWriteArrayList<>();
    private final Set<String> orders = ConcurrentHashMap.newKeySet();
    private final Set<String> patients = ConcurrentHashMap.newKeySet();

    /**
     * Seed a synthetic membership and role.
     *
     * @param organizationId owning organization
     * @param subject        synthetic subject
     * @param role           active role key
     * @param active         whether the membership is active
     */
    public void addMember(String organizationId, String subject, String role, boolean active) {
        roles.put(key(organizationId, subject), role);
        if (active) {
            activeSubjects.add(key(organizationId, subject));
        } else {
            activeSubjects.remove(key(organizationId, subject));
        }
    }

    /**
     * Seed a synthetic order reference with its patient binding.
     *
     * @param organizationId owning organization
     * @param orderId        order identifier
     * @param patientId      bound patient identifier
     */
    public void addOrder(String organizationId, String orderId, String patientId) {
        orders.add(key(organizationId, orderId + "|" + patientId));
    }

    /**
     * Seed a synthetic patient reference.
     *
     * @param organizationId owning organization
     * @param patientId      patient identifier
     */
    public void addPatient(String organizationId, String patientId) {
        patients.add(key(organizationId, patientId));
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return activeSubjects.contains(key(organizationId, subject));
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        String role = roles.get(key(organizationId, subject));
        if (role == null || !hasActiveMembership(organizationId, subject)) {
            return List.of();
        }
        return List.of(role);
    }

    @Override
    public Optional<OutboxEvent> outboxEvent(String organizationId, String eventId) {
        return Optional.ofNullable(outbox.get(key(organizationId, eventId)));
    }

    @Override
    public Optional<OutboxEvent> byIdempotencyKey(String organizationId, String sender,
                                                   String eventType, String keyValue) {
        return Optional.ofNullable(idempotency.get(key(organizationId, sender + "|" + eventType + "|" + keyValue)));
    }

    @Override
    public synchronized OutboxEvent publishAtomically(String organizationId, OutboxEvent event,
                                                       DeliveryAuditEvent audit) {
        OutboxEvent existing = outbox.get(key(organizationId, event.eventId()));
        if (existing != null) {
            return existing;
        }
        String dedupKey = key(organizationId, event.sender() + "|" + event.eventType() + "|"
                + event.idempotencyKey());
        OutboxEvent first = idempotency.get(dedupKey);
        if (first != null) {
            return first;
        }
        outbox.put(key(organizationId, event.eventId()), event);
        idempotency.put(dedupKey, event);
        audits.computeIfAbsent(organizationId, ignored -> new CopyOnWriteArrayList<>()).add(audit);
        return event;
    }

    @Override
    public void putOutboxEvent(String organizationId, OutboxEvent event) {
        outbox.put(key(organizationId, event.eventId()), event);
    }

    @Override
    public void appendAudit(String organizationId, DeliveryAuditEvent audit) {
        audits.computeIfAbsent(organizationId, ignored -> new CopyOnWriteArrayList<>()).add(audit);
    }

    @Override
    public List<DeliveryAuditEvent> auditEvents(String organizationId) {
        return List.copyOf(audits.getOrDefault(organizationId, List.of()));
    }

    @Override
    public Optional<ConsumerReceipt> receipt(String organizationId, String namespace, String sender,
                                              String eventType, String externalEventId) {
        return Optional.ofNullable(receipts.get(
                key(organizationId, namespace + "|" + sender + "|" + eventType + "|" + externalEventId)));
    }

    @Override
    public Optional<ConsumerReceipt> receiptById(String organizationId, String externalEventId) {
        for (ConsumerReceipt receipt : receipts.values()) {
            if (organizationId.equals(receipt.organizationId())
                    && externalEventId.equals(receipt.externalEventId())) {
                return Optional.of(receipt);
            }
        }
        return Optional.empty();
    }

    @Override
    public OptionalInt maxVersion(String organizationId, String namespace, String sender,
                                  String eventType, String aggregateId) {
        int highest = -1;
        for (ConsumerReceipt receipt : receipts.values()) {
            if (organizationId.equals(receipt.organizationId())
                    && namespace.equals(receipt.namespace())
                    && sender.equals(receipt.sender())
                    && eventType.equals(receipt.eventType())
                    && aggregateId.equals(receipt.aggregateId())) {
                highest = Math.max(highest, receipt.aggregateVersion());
            }
        }
        return highest < 0 ? OptionalInt.empty() : OptionalInt.of(highest);
    }

    @Override
    public void putReceipt(String organizationId, ConsumerReceipt receipt) {
        receipts.put(key(organizationId, receipt.namespace() + "|" + receipt.sender() + "|"
                + receipt.eventType() + "|" + receipt.externalEventId()), receipt);
    }

    @Override
    public void putDeadLetter(String organizationId, DeadLetterEntry entry) {
        deadLetters.put(key(organizationId, entry.eventId()), entry);
    }

    @Override
    public Optional<DeadLetterEntry> deadLetter(String organizationId, String eventId) {
        return Optional.ofNullable(deadLetters.get(key(organizationId, eventId)));
    }

    @Override
    public List<DeadLetterEntry> deadLetters(String organizationId) {
        List<DeadLetterEntry> found = new ArrayList<>();
        for (Map.Entry<String, DeadLetterEntry> entry : deadLetters.entrySet()) {
            if (entry.getKey().startsWith(organizationId + "|")) {
                found.add(entry.getValue());
            }
        }
        return List.copyOf(found);
    }

    @Override
    public void putReplay(String organizationId, ReplayRecord replay) {
        replays.add(replay);
    }

    @Override
    public void putLink(String organizationId, ExternalLink link) {
        links.add(link);
    }

    @Override
    public boolean orderExists(String organizationId, String orderId) {
        String prefix = key(organizationId, orderId + "|");
        for (String candidate : orders) {
            if (candidate.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean patientExists(String organizationId, String patientId) {
        return patients.contains(key(organizationId, patientId));
    }

    private static String key(String organizationId, String identifier) {
        return organizationId + "|" + identifier;
    }
}
