package com.aurevia.events;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.aurevia.events.DurableEventContract.ConsumerReceipt;
import com.aurevia.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.events.DurableEventContract.DeliveryAuditEvent;
import com.aurevia.events.DurableEventContract.ExternalLink;
import com.aurevia.events.DurableEventContract.OutboxEvent;
import com.aurevia.events.DurableEventContract.ReplayRecord;

/**
 * Persistence boundary for ARC-05 delivery facts.
 *
 * <p>Every method carries the active organization explicitly, so two tenants
 * with identical local identifiers remain isolated. A production adapter must
 * commit the outbox event, its audit fact and its idempotency key in one
 * transaction under forced tenant row-level security; the synthetic adapter
 * used by the fast tests mirrors that contract in memory.</p>
 */
public interface DeliveryStore {

    /**
     * Check active membership in the organization.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return {@code true} when the membership is active
     */
    boolean hasActiveMembership(String organizationId, String subject);

    /**
     * List role keys currently in force for a subject.
     *
     * @param organizationId active organization
     * @param subject        authenticated subject
     * @return active role keys, empty when membership is inactive
     */
    List<String> activeRoles(String organizationId, String subject);

    /**
     * Find one outbox event by its immutable id.
     *
     * @param organizationId active organization
     * @param eventId        event id
     * @return the event, if present in this tenant
     */
    Optional<OutboxEvent> outboxEvent(String organizationId, String eventId);

    /**
     * Find the first event published under an idempotency key.
     *
     * @param organizationId active organization
     * @param sender         emitting sender key
     * @param eventType      event type key
     * @param key            idempotency key
     * @return the original event, if one was committed with this key
     */
    Optional<OutboxEvent> byIdempotencyKey(String organizationId, String sender, String eventType, String key);

    /**
     * Atomically commit an outbox event, its audit fact and its deduplication key.
     *
     * @param organizationId active organization
     * @param event          event to persist
     * @param audit          audit fact to persist with it
     * @return the persisted event, which may be the original for a repeated command
     */
    OutboxEvent publishAtomically(String organizationId, OutboxEvent event, DeliveryAuditEvent audit);

    /**
     * Persist a new state snapshot of an existing event.
     *
     * @param organizationId active organization
     * @param event          new snapshot with the same immutable event id
     */
    void putOutboxEvent(String organizationId, OutboxEvent event);

    /**
     * Append one immutable audit fact.
     *
     * @param organizationId active organization
     * @param audit          fact to append
     */
    void appendAudit(String organizationId, DeliveryAuditEvent audit);

    /**
     * Read the tenant's append-only audit trail.
     *
     * @param organizationId active organization
     * @return audit facts in insertion order
     */
    List<DeliveryAuditEvent> auditEvents(String organizationId);

    /**
     * Find one processing receipt inside a sender and type namespace.
     *
     * @param organizationId active organization
     * @param namespace      deduplication namespace
     * @param sender         partner sender key
     * @param eventType      partner event type key
     * @param externalEventId partner event id
     * @return the receipt, if this external event was processed
     */
    Optional<ConsumerReceipt> receipt(String organizationId, String namespace, String sender,
                                      String eventType, String externalEventId);

    /**
     * Find a processing receipt by external event id alone.
     *
     * @param organizationId  active organization
     * @param externalEventId partner event id
     * @return the receipt, if this external event was processed in this tenant
     */
    Optional<ConsumerReceipt> receiptById(String organizationId, String externalEventId);

    /**
     * Highest aggregate version already processed for an external event family.
     *
     * @param organizationId active organization
     * @param namespace      deduplication namespace
     * @param sender         partner sender key
     * @param eventType      partner event type key
     * @param aggregateId    local aggregate id
     * @return the highest version, or empty when no event was processed
     */
    OptionalInt maxVersion(String organizationId, String namespace, String sender,
                           String eventType, String aggregateId);

    /**
     * Persist one processing receipt.
     *
     * @param organizationId active organization
     * @param receipt        receipt to persist
     */
    void putReceipt(String organizationId, ConsumerReceipt receipt);

    /**
     * Persist one restricted dead-letter entry.
     *
     * @param organizationId active organization
     * @param entry          dead-letter entry
     */
    void putDeadLetter(String organizationId, DeadLetterEntry entry);

    /**
     * Find one dead-letter entry by original event id.
     *
     * @param organizationId active organization
     * @param eventId        original event id
     * @return the entry, if present
     */
    Optional<DeadLetterEntry> deadLetter(String organizationId, String eventId);

    /**
     * List the tenant's restricted dead-letter queue.
     *
     * @param organizationId active organization
     * @return entries in insertion order
     */
    List<DeadLetterEntry> deadLetters(String organizationId);

    /**
     * Persist one authorized replay fact.
     *
     * @param organizationId active organization
     * @param replay         replay fact
     */
    void putReplay(String organizationId, ReplayRecord replay);

    /**
     * Persist one externally-linked provenance fact.
     *
     * @param organizationId active organization
     * @param link           link fact
     */
    void putLink(String organizationId, ExternalLink link);

    /**
     * Confirm an order exists in the active tenant.
     *
     * @param organizationId active organization
     * @param orderId        order identifier
     * @return {@code true} when the order is present in this tenant
     */
    boolean orderExists(String organizationId, String orderId);

    /**
     * Confirm a patient exists in the active tenant.
     *
     * @param organizationId active organization
     * @param patientId      patient identifier
     * @return {@code true} when the patient is present in this tenant
     */
    boolean patientExists(String organizationId, String patientId);
}
