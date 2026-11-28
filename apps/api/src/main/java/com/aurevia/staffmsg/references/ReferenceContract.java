package com.aurevia.staffmsg.references;

import java.time.Instant;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Value types for typed references carried in a staff message.
 *
 * <p>A reference points at a patient, encounter, order or document without
 * copying any chart content into the message. The label is deliberately
 * non-identifying; the target and connection identifiers are opaque handles that
 * are only resolved after the recipient's access check passes. These are
 * immutable value types only: a reference is embedded in message content rather
 * than stored as an independent row.</p>
 */
public final class ReferenceContract {

    private ReferenceContract() {
    }

    /** Business meaning of a reference target, with its stable wire key. */
    public enum ReferenceKind {
        /** A verified patient record. */
        PATIENT("patient"),
        /** One encounter or visit. */
        ENCOUNTER("encounter"),
        /** A clinical order. */
        ORDER("order"),
        /** A scanned or stored document. */
        DOCUMENT("document");

        private final String wire;

        ReferenceKind(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire key.
         *
         * @return lowercase wire value
     */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Resolve a wire key to a kind.
         *
         * @param value wire key
         * @return the matching kind
         * @throws IllegalArgumentException when the key is not supported
         */
        public static ReferenceKind from(String value) {
            for (ReferenceKind kind : values()) {
                if (kind.wire.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("unsupported reference kind");
        }
    }

    /** Server-derived outcome of re-checking a recipient's connection access. */
    public enum AccessOutcome {
        /** The recipient has an active care relationship and may open the target. */
        ALLOWED,
        /** The recipient has no active care relationship with the target. */
        NO_RELATIONSHIP,
        /** The connection was revoked or the target is stale. */
        REVOKED_OR_STALE,
        /** The target does not exist. */
        UNKNOWN_TARGET,
        /** The target belongs to a different tenant and must be concealed. */
        WRONG_TENANT
    }

    /** Append-only facts recorded for reference actions; never carries a target identifier. */
    public enum AuditType {
        /** A reference was attached to a message. */
        ATTACHED("attached"),
        /** An authorized recipient opened a reference. */
        OPENED("opened"),
        /** An open was denied, blocked or concealed. */
        ACCESS_DENIED("access_denied");

        private final String wire;

        AuditType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable stored key.
         *
         * @return lowercase wire value
     */
        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /**
     * A freshly derived staff actor.
     *
     * @param organizationId active organization from the tenant context
     * @param subject        authenticated staff subject
     * @param roles          currently active roles in this organization
     * @return this record
     */
    public record ReferenceActor(String organizationId, String subject, Set<String> roles) {
        /**
         * Freeze the role set so it cannot be widened after the access decision.
         */
        public ReferenceActor {
            roles = Set.copyOf(roles);
        }

        /**
         * Build an actor from fresh active membership roles.
         *
         * @param organizationId active organization
         * @param subject        authenticated staff subject
         * @param roles          active role keys
         * @return the immutable actor
         */
        public static ReferenceActor staff(String organizationId, String subject, String... roles) {
            return new ReferenceActor(organizationId, subject, Set.of(roles));
        }
    }

    /**
     * The reference a sender attaches to a message.
     *
     * <p>Carries opaque handles and a non-identifying label only. No subject
     * line, patient demographics or clinical text is copied into this value.</p>
     *
     * @return the immutable reference
     */
    public record MessageReference(String id, String organizationId, ReferenceKind kind,
                                   String targetId, String connectionId, String label,
                                   Instant createdAt) {
    }

    /**
     * What a recipient sees before opening: the label and kind, never a target id.
     *
     * @return the immutable label-only view
     * @param id identifier of the record in the active organization
     * @param kind value supplied by the caller
     * @param label value supplied by the caller
     */
    public record ReferenceView(String id, String kind, String label) {
    }

    /**
     * A sender warning about a possible identifier in a subject line.
     *
     * <p>The reason is a fixed code; the subject text is never echoed into the
     * warning, an audit fact or a notification.</p>
     *
     * @return the immutable warning
     * @param suspicious value supplied by the caller
     * @param reasonCode value supplied by the caller
     */
    public record SubjectWarning(boolean suspicious, String reasonCode) {
    }

    /**
     * An identifier-free notification to dispatch after an authorized open.
     *
     * <p>The text is a fixed approved phrase and the payload has no clinical
     * fields, so a patient-facing push can never carry a chart identifier.</p>
     *
     * @return the immutable notification draft
     * @param eventType value supplied by the caller
     * @param text value supplied by the caller
     * @param referenceId value supplied by the caller
     */
    public record NotificationDraft(String eventType, String text, String referenceId) {
    }

    /**
     * An append-only audit fact keyed to an opaque reference id.
     *
     * @return the immutable audit fact
     */
    public record ReferenceAudit(String id, String organizationId, String referenceId, AuditType type,
                                 String actorSubject, String outcome, Instant occurredAt) {
    }

    /**
     * The target locator returned only after the access check passes.
     *
     * @return the immutable open result
     * @param id identifier of the record in the active organization
     * @param kind value supplied by the caller
     * @param targetId value supplied by the caller
     */
    public record OpenResult(String id, String kind, String targetId) {
    }

    /**
     * Attach command. The subject is screened and discarded, never stored.
     *
     * @return the immutable command
     */
    public record AttachRequest(String kind, String targetId, String connectionId,
                                String label, String subject) {
    }

    /**
     * Client-held descriptor sent back for a view or open operation.
     *
     * <p>The organization is never accepted from the client: the server derives
     * it from the authenticated tenant context.</p>
     *
     * @return the immutable descriptor
     */
    public record ReferenceHandle(String id, String connectionId, String kind,
                                  String targetId, String label) {
    }

    /**
     * Result of an attach: the reference plus an identifier-safe warning.
     *
     * @return the immutable result
     * @param reference value supplied by the caller
     * @param warning value supplied by the caller
     */
    public record AttachResult(MessageReference reference, SubjectWarning warning) {
    }

    /**
     * Subject-screening command carried in a POST body, never in a URL.
     *
     * <p>A subject may contain a name or identifier, so it must not enter a
     * query string where access logs, proxies, history or Referer headers could
     * retain it.</p>
     *
     * @return the immutable command
     * @param subject authenticated subject derived from the tenant context
     */
    public record ScreenSubjectRequest(String subject) {
    }
}
