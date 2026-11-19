package com.aurevia.smartclinic.g2.s_g2_09;

import com.aurevia.smartclinic.g2.s_g2_09.LabTypes.TriggerType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.aurevia.smartclinic.api.ApiException;

/**
 * One immutable event produced when a laboratory revision is issued (XCH-04, XCH-05, ARC-05).
 *
 * <p>The event identity is derived from the event type, tenant, aggregate and
 * version, so a replay of the same fact keeps the same identity and a new
 * correction gets a new one. Consumers deduplicate inside the sender/type
 * namespace. This record is a domain fact; persistence and delivery belong to a
 * later outbox story.</p>
 *
 * @param organizationId owning tenant
 * @param type           event type used as the dedup namespace
 * @param reportId       tenant-local report identifier
 * @param reportVersion  version of the revision that produced the event
 * @param patientId      matched patient identifier
 * @param specimenId     matched specimen identifier
 * @param orderId        matched order identifier
 * @param owner          named responsible owner for routing or escalation
 * @param queue          coverage queue that receives the work item
 * @param effectiveAt    clinical effective time of the revision
 * @param dueAfter       allowed window before the work item is overdue, or {@code null}
 * @param escalation     approved backstop channel, or {@code null}
 * @param payloadDigest  digest of the immutable content the event refers to
 * @param occurredAt     server time at which the fact was recorded
 * @param findingCode    test identity that discriminates multiple findings of one revision, or {@code null}
 */
public record TriggerEvent(String organizationId, TriggerType type, String reportId, int reportVersion,
                           String patientId, String specimenId, String orderId, String owner,
                           String queue, Instant effectiveAt, Duration dueAfter, String escalation,
                           String payloadDigest, Instant occurredAt, String findingCode) {

    /**
     * Reject an event without the identifiers a consumer needs to deduplicate it.
     *
     * @throws ApiException when the tenant, type, aggregate or patient identity is missing
     */
    public TriggerEvent {
        if (organizationId == null || organizationId.isBlank()) {
            throw invalid();
        }
        if (type == null) {
            throw invalid();
        }
        if (reportId == null || reportId.isBlank()) {
            throw invalid();
        }
        if (reportVersion < 1) {
            throw invalid();
        }
        if (patientId == null || patientId.isBlank()) {
            throw invalid();
        }
        if (effectiveAt == null) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(422, "INVALID_EVENT", "invalid laboratory trigger event");
    }

    /**
     * Return the stable identity used for deduplication within a sender/type namespace.
     *
     * @return event type, tenant, aggregate identifier and version joined by separators
     */
    public String dedupKey() {
        return type.wire() + "|" + organizationId + "|" + reportId + "|" + reportVersion
                + "|" + (findingCode == null ? "revision" : findingCode);
    }

    /**
     * Return the deterministic event identifier derived from the dedup identity.
     *
     * @return a name-based UUID stable across replays
     */
    public String eventId() {
        return UUID.nameUUIDFromBytes(dedupKey().getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * Return the deadline derived from the revision time and the approved window.
     *
     * @return the due instant, or {@code null} when no time limit was configured
     */
    public Instant dueAt() {
        return dueAfter == null ? null : effectiveAt.plus(dueAfter);
    }
}
