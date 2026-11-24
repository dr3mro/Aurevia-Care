package com.aurevia.messaging.governance;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for MSG-07 messaging audit, retention, legal hold,
 * provenance, copy requests and revocation effects.
 *
 * <p>Every type here is a non-sensitive governance fact. No field carries a
 * message body, attachment bytes, a diagnosis or any other clinical payload:
 * those remain in the authorized conversation and chart stores. Each fact is
 * tenant-owned and carries its own server timestamp so it can be audited
 * independently of delivery or release events.</p>
 */
public final class MessagingAuditContract {

    private MessagingAuditContract() {
    }

    /**
     * The complete MSG-07 action vocabulary. The first nine actions cover
     * opening, viewing, sending, assigning, downloading, acknowledging, filing,
     * correcting and restricting conversations or files; the remainder record
     * the governance commands that own retention, holds, exports, provenance,
     * copy requests and audit review.
     */
    public enum AuditAction {
        /** A conversation or file was opened. */
        OPEN("open"),
        /** A conversation or file was viewed. */
        VIEW("view"),
        /** A message or instruction was sent. */
        SEND("send"),
        /** Work was assigned to a named owner. */
        ASSIGN("assign"),
        /** A file was downloaded through a mediated link. */
        DOWNLOAD("download"),
        /** Receipt of an exact revision was acknowledged. */
        ACKNOWLEDGE("acknowledge"),
        /** Clinically relevant material was filed in the chart. */
        FILE("file"),
        /** A correction replaced an earlier revision. */
        CORRECT("correct"),
        /** Access through a connection or to a file was restricted. */
        RESTRICT("restrict"),
        /** A scoped, expiring export was created. */
        EXPORT("export"),
        /** A legal hold was placed on a resource. */
        HOLD_PLACE("hold_place"),
        /** A legal hold was released. */
        HOLD_RELEASE("hold_release"),
        /** A message or instruction was linked to the chart with provenance. */
        CHART_LINK("chart_link"),
        /** A copy request was routed to the site record-access procedure. */
        COPY_REQUEST("copy_request"),
        /** Retention was applied to eligible resources. */
        RETENTION_APPLY("retention_apply"),
        /** The audit trail was read; reading audit is itself audited. */
        AUDIT_READ("audit_read");

        private final String wire;

        /**
         * Create an action with its stable wire key.
         *
         * @param wire lowercase action key
         */
        AuditAction(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase action key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored or supplied action key.
         *
         * @param value stored key
         * @return the matching action
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AuditAction from(String value) {
            for (AuditAction candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown audit action: " + value);
        }
    }

    /**
     * Recorded outcome of an audited action. Failure facts are appended in the
     * same way as successes so denials are never silently dropped (OPS-02).
     */
    public enum Outcome {
        /** The action completed. */
        SUCCESS("success"),
        /** The actor or resource was not authorized. */
        DENIED("denied"),
        /** The resource was concealed or absent in this tenant. */
        NOT_FOUND("not_found"),
        /** The action conflicted with current state or a duplicate. */
        CONFLICT("conflict");

        private final String wire;

        /**
         * Create an outcome with its stable wire key.
         *
         * @param wire lowercase outcome key
         */
        Outcome(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire representation.
         *
         * @return stable lowercase outcome key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored outcome key.
         *
         * @param value stored key
         * @return the matching outcome
         * @throws IllegalArgumentException when the key is unknown
         */
        public static Outcome from(String value) {
            for (Outcome candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown audit outcome: " + value);
        }
    }

    /**
     * One append-only, non-sensitive audit fact (OPS-02).
     *
     * @param id             opaque fact identifier
     * @param organizationId owning organization
     * @param subject        authenticated actor
     * @param purpose        recorded business purpose
     * @param action         action key
     * @param resourceId     affected resource identifier
     * @param unitId         care unit or system scope
     * @param correlationId  request correlation identifier
     * @param outcome        recorded outcome
     * @param occurredAt     server timestamp
     * @return the immutable fact
     */
    public record AuditEvent(String id, String organizationId, String subject, String purpose,
                             AuditAction action, String resourceId, String unitId, String correlationId,
                             Outcome outcome, Instant occurredAt) {
    }

    /**
     * Tenant-approved retention window for one resource type.
     *
     * @param organizationId owning organization
     * @param resourceType   messaging resource family
     * @param retentionDays  days before a resource becomes retention-eligible
     * @return the immutable policy
     */
    public record RetentionPolicy(String organizationId, String resourceType, long retentionDays) {
    }

    /**
     * A synthetic messaging resource tracked for retention and holds.
     *
     * @param id             resource identifier
     * @param organizationId owning organization
     * @param resourceType   resource family used to find its retention policy
     * @param createdAt      server creation instant
     * @return the immutable value
     */
    public record MessagingResource(String id, String organizationId, String resourceType, Instant createdAt) {
    }

    /**
     * A legal hold that suspends retention and deletion for one resource.
     *
     * @param id             hold identifier
     * @param organizationId owning organization
     * @param resourceId     resource under hold
     * @param reason         non-sensitive reason for the hold
     * @param placedBy       authorized actor who placed it
     * @param placedAt       server placement instant
     * @param releasedBy     actor who released it, or {@code null} while active
     * @param releasedAt     server release instant, or {@code null} while active
     * @param releasedReason non-sensitive release reason, or {@code null} while active;
     *                       the original placement reason is always preserved
     * @return the immutable value
     */
    public record LegalHold(String id, String organizationId, String resourceId, String reason,
                            String placedBy, Instant placedAt, String releasedBy, Instant releasedAt,
                            String releasedReason) {
        /**
         * Report whether the hold still blocks retention.
         *
         * @return {@code true} until a release instant is recorded
         */
        public boolean active() {
            return releasedAt == null;
        }
    }

    /**
     * Verified patient record-access policy used after a connection is revoked.
     *
     * @param organizationId              owning organization
     * @param priorConversationsReadable   whether earlier conversations remain readable
     * @param policyVersion               approved policy version recorded as provenance
     * @return the immutable policy
     */
    public record RecordAccessPolicy(String organizationId, boolean priorConversationsReadable,
                                     String policyVersion) {
    }

    /**
     * Provenance of a clinically relevant message or instruction filed in a chart.
     *
     * <p>The link stores source identity and revision; it never copies the
     * clinical payload into the audit trail.</p>
     *
     * @param id              link identifier
     * @param organizationId  owning organization
     * @param conversationId  source conversation
     * @param messageId       source message or instruction
     * @param patientId       verified chart
     * @param chartId         destination chart resource
     * @param sourceType      message or instruction
     * @param sourceReference stable source reference
     * @param revision        immutable source revision
     * @param linkedBy        authorized staff actor
     * @param linkedAt        server link instant
     * @return the immutable link
     */
    public record ProvenanceLink(String id, String organizationId, String conversationId, String messageId,
                                 String patientId, String chartId, String sourceType, String sourceReference,
                                 String revision, String linkedBy, Instant linkedAt) {
    }

    /**
     * Non-sensitive provenance carried by a conversation export.
     *
     * <p>The export keeps a stable pointer to the source material, the exact
     * revision it was derived from and the purpose of the copy, so a later
     * review can trace a copy back to its origin. No message body, attachment
     * bytes or clinical payload is duplicated into the governance record.
     * Exports created before provenance was introduced read back as empty
     * values; only exports created through the current command carry validated
     * references.</p>
     *
     * @param sourceReference stable non-sensitive source reference
     * @param sourceRevision  immutable source revision at export time
     * @param purpose         non-sensitive purpose of the copy
     * @return the immutable provenance
     */
    public record ExportProvenance(String sourceReference, String sourceRevision, String purpose) {
    }

    /**
     * A scoped, expiring export record (OPS-07).
     *
     * <p>The provenance is part of the durable record: an authorized review can
     * establish where the exported copy came from even after the source
     * conversation has left the retention window.</p>
     *
     * @param id             export identifier
     * @param organizationId owning organization
     * @param scope          explicit resource scope
     * @param createdBy      authorized actor
     * @param createdAt      server creation instant
     * @param expiresAt      server expiry instant
     * @param provenance     non-sensitive source provenance for the copy
     * @return the immutable record
     */
    public record ExportRecord(String id, String organizationId, String scope, String createdBy,
                               Instant createdAt, Instant expiresAt, ExportProvenance provenance) {
    }

    /**
     * A request for copies routed to the site's record-access procedure.
     *
     * @param id             request identifier
     * @param organizationId owning organization
     * @param resourceId     resource the copy concerns
     * @param requestedBy    authenticated actor
     * @param purpose        non-sensitive purpose
     * @param status         workflow status
     * @param createdAt      server timestamp
     * @return the immutable request
     */
    public record CopyRequest(String id, String organizationId, String resourceId, String requestedBy,
                              String purpose, String status, Instant createdAt) {
    }

    /**
     * The recorded consequences of revoking a patient-unit connection.
     *
     * @param id                        effect identifier
     * @param organizationId            owning organization
     * @param connectionId              revoked connection
     * @param messagingBlocked          new messaging is refused
     * @param attachmentAccessBlocked   future attachment access is refused
     * @param priorConversationsReadable whether earlier conversations stay readable
     * @param recordAccessBasis         policy version used for the readability decision
     * @param recordedAt                server timestamp
     * @return the immutable effect
     */
    public record RevocationEffect(String id, String organizationId, String connectionId,
                                   boolean messagingBlocked, boolean attachmentAccessBlocked,
                                   boolean priorConversationsReadable, String recordAccessBasis,
                                   Instant recordedAt) {
    }

    /**
     * Distinct retention duties owed to the sender and the recipient of a
     * messaging resource (MSG-07). A legal hold on one party never waives the
     * other party's duty; both are preserved as immutable facts.
     *
     * @param organizationId       owning organization
     * @param resourceId           resource the duties apply to
     * @param senderRetentionDays  sender's retention window
     * @param recipientRetentionDays recipient's retention window
     * @return the immutable duty pair
     */
    public record RetentionDuty(String organizationId, String resourceId, long senderRetentionDays,
                                long recipientRetentionDays) {
    }

    /**
     * The decision for one retention-eligibility query.
     *
     * @param resourceId resource that was checked
     * @param eligible   whether retention may remove it
     * @param reason     stable reason code
     * @return the immutable decision
     */
    public record RetentionDecision(String resourceId, boolean eligible, String reason) {
    }

    /**
     * A tenant-scoped connection reference used for revocation effects.
     *
     * @param id             connection identifier
     * @param organizationId owning organization
     * @param unitId         recipient unit
     * @param patientId      verified chart
     * @param revokedAt      revocation instant, or {@code null} while active
     * @return the immutable reference
     */
    public record ConnectionReference(String id, String organizationId, String unitId,
                                      String patientId, Instant revokedAt) {
    }

    /**
     * Record one audited messaging action performed by an authorized workflow.
     *
     * <p>The outcome is deliberately absent: the service derives it from the
     * enforced command so a caller cannot self-report a success for an action
     * that never occurred. Only the fact that the action was performed is
     * supplied; the server stamps actor, purpose, correlation and outcome.</p>
     *
     * @param action     action wire key
     * @param resourceId affected resource
     * @param unitId     unit or system scope
     * @return the command
     */
    public record RecordActionRequest(String action, String resourceId, String unitId) {
    }

    /**
     * Place a legal hold on one resource.
     *
     * @param resourceId resource to hold
     * @param reason     non-sensitive reason
     * @return the command
     */
    public record PlaceHoldRequest(String resourceId, String reason) {
    }

    /**
     * Link a source message or instruction to a chart with provenance.
     *
     * @param conversationId source conversation
     * @param messageId      source message or instruction
     * @param patientId      verified chart
     * @param chartId        destination chart resource
     * @param sourceType     message or instruction
     * @param sourceReference stable source reference
     * @param revision       immutable source revision
     * @return the command
     */
    public record LinkToChartRequest(String conversationId, String messageId, String patientId,
                                     String chartId, String sourceType, String sourceReference, String revision) {
    }

    /**
     * Create a scoped, expiring export that keeps its source provenance.
     *
     * @param scope      explicit resource scope
     * @param ttlSeconds requested validity window, or {@code null} for the default
     * @param provenance non-sensitive source provenance for the copy
     * @return the command
     */
    public record CreateExportRequest(String scope, Long ttlSeconds, ExportProvenance provenance) {
    }

    /**
     * Route a copy request to the site record-access procedure.
     *
     * @param resourceId resource the copy concerns
     * @param purpose    non-sensitive purpose
     * @return the command
     */
    public record RequestCopyRequest(String resourceId, String purpose) {
    }
}
