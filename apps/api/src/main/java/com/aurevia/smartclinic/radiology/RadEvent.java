package com.aurevia.smartclinic.radiology;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.radiology.RadTypes.RadEventType;

/**
 * One immutable fact produced by the RAD-01 kernel (ARC-05, OPS-02).
 *
 * <p>The event identity is derived from the event type, tenant, order and
 * version so a replay of the same fact keeps the same identity while a new
 * revision or a different discriminator produces a new one. Consumers
 * deduplicate inside the sender/type namespace. This is a domain fact;
 * persistence and delivery belong to a later outbox story.</p>
 *
 * @param organizationId owning tenant
 * @param type           event type used as the dedup namespace
 * @param orderId        tenant-local imaging order identifier
 * @param orderVersion   order version that produced the event
 * @param patientId      patient identifier on the matched order
 * @param accession      issuer-scoped accession, or {@code null}
 * @param actor          authenticated subject that caused the fact
 * @param destination    referrer or recipient notified by the fact, or {@code null}
 * @param payloadDigest  SHA-256 digest of the immutable content the event refers to, never the
 *                       clinical narrative itself, or {@code null}
 * @param occurredAt     server time at which the fact was recorded
 */
public record RadEvent(String organizationId, RadEventType type, String orderId, int orderVersion,
                       String patientId, String accession, String actor, String destination,
                       String payloadDigest, Instant occurredAt) {

    /**
     * Reject an event without the identifiers a consumer needs to deduplicate it.
     *
     * @throws ApiException when tenant, type, order identity, version or time is missing
     */
    public RadEvent {
        if (organizationId == null || organizationId.isBlank()) {
            throw invalid();
        }
        if (type == null) {
            throw invalid();
        }
        if (orderId == null || orderId.isBlank()) {
            throw invalid();
        }
        if (orderVersion < 1) {
            throw invalid();
        }
        if (patientId == null || patientId.isBlank()) {
            throw invalid();
        }
        if (occurredAt == null) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(422, "INVALID_RAD_EVENT", "invalid radiology domain event");
    }

    /**
     * Return the stable identity used for deduplication within a sender/type namespace.
     *
     * @return event type, tenant, order identifier and version joined by separators
     */
    public String dedupKey() {
        return type.wire() + "|" + organizationId + "|" + orderId + "|" + orderVersion;
    }

    /**
     * Return the deterministic event identifier derived from the dedup identity.
     *
     * @return a name-based UUID stable across replays
     */
    public String eventId() {
        return UUID.nameUUIDFromBytes(dedupKey().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
