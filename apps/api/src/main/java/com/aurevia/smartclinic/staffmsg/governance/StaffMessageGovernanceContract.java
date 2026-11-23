package com.aurevia.smartclinic.staffmsg.governance;

import java.time.Instant;
import java.util.List;

import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.ExportProvenance;
import com.aurevia.smartclinic.messaging.governance.MessagingAuditContract.Outcome;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Value contract for staff-to-staff message governance (MSG-07, OPS-02, OPS-07).
 *
 * <p>Every type here is a non-sensitive governance fact. An audit fact records
 * who did what to which resource and with what outcome, but never a message
 * body, an attachment checksum or any clinical payload; those stay in the
 * authorized conversation store. Attachment checksums are assembled on demand
 * from {@code staff_attachment_link} when a records officer exports a
 * conversation, and only the non-sensitive export metadata is persisted.</p>
 *
 * <p>The retention, legal-hold and provenance value types are owned by
 * {@link com.aurevia.smartclinic.messaging.governance.MessagingAuditContract}
 * and imported rather than duplicated.</p>
 */
public final class StaffMessageGovernanceContract {

    private StaffMessageGovernanceContract() {
    }

    /**
     * The complete staff-message action vocabulary: content actions, file and
     * folder actions, and the governance commands that own retention, holds,
     * exports and audit review.
     */
    public enum StaffAction {
        /** A conversation or message was opened. */
        OPEN("open"),
        /** A message was sent. */
        SEND("send"),
        /** A conversation or file was forwarded. */
        FORWARD("forward"),
        /** A file was attached to a message. */
        ATTACH("attach"),
        /** A file was downloaded through a mediated link. */
        DOWNLOAD("download"),
        /** A message was moved between a participant's folders. */
        FOLDER_MOVE("folder_move"),
        /** A message was hidden by a user; the durable row is retained. */
        DELETE("delete"),
        /** A legal hold was placed. */
        HOLD_PLACE("hold_place"),
        /** A legal hold was released. */
        HOLD_RELEASE("hold_release"),
        /** Retention was applied to eligible messages. */
        RETENTION_APPLY("retention_apply"),
        /** A conversation export was produced by a records officer. */
        EXPORT("export"),
        /** The audit trail was read; reading audit is itself audited. */
        AUDIT_READ("audit_read");

        private final String wire;

        /**
         * Create an action with its stable wire key.
         *
         * @param wire lowercase action key
         */
        StaffAction(String wire) {
            this.wire = wire;
        }

        /**
         * Return the stable wire representation.
         *
         * @return lowercase action key
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
        public static StaffAction from(String value) {
            for (StaffAction candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown staff action: " + value);
        }
    }

    /**
     * One append-only, non-sensitive staff-message audit fact (OPS-02).
     *
     * @param id            opaque fact identifier
     * @param organizationId owning organization
     * @param subject       authenticated actor
     * @param purpose       recorded business purpose
     * @param action        action key
     * @param resourceId    affected conversation or message identifier
     * @param unitId        facility or system scope
     * @param correlationId request correlation identifier
     * @param outcome       recorded outcome, derived by the service
     * @param occurredAt    server timestamp
     * @return the immutable fact
     */
    public record AuditEvent(String id, String organizationId, String subject, String purpose,
                             StaffAction action, String resourceId, String unitId, String correlationId,
                             Outcome outcome, Instant occurredAt) {
    }

    /**
     * A durable message projection used by tenant-only search.
     *
     * @param messageId      message identifier
     * @param conversationId owning conversation
     * @param senderSubject  author subject
     * @param subject        mail subject, or {@code null} for an instant message
     * @param sentAt         server send instant
     * @param body           immutable message content
     * @return the immutable projection
     */
    public record MessageView(String messageId, String conversationId, String senderSubject,
                              String subject, Instant sentAt, String body) {
    }

    /**
     * Validated search criteria. Every field is optional except the page bounds;
     * an absent filter means "do not restrict on this field".
     *
     * @param senderSubject exact sender subject filter, or {@code null}
     * @param subject       subject text fragment, or {@code null}
     * @param from          inclusive lower sent-at bound, or {@code null}
     * @param to            inclusive upper sent-at bound, or {@code null}
     * @param text          body text fragment, or {@code null}
     * @param cursor        opaque message identifier after which to continue, or {@code null}
     * @param limit         requested page size, validated by the service
     * @return the immutable query
     */
    public record SearchQuery(String senderSubject, String subject, Instant from, Instant to,
                              String text, String cursor, Integer limit) {
    }

    /**
     * One search result. The body belongs to the requesting participant's own
     * conversation; it is returned only after the participant and folder filters
     * have passed.
     *
     * @param messageId      message identifier
     * @param conversationId owning conversation
     * @param senderSubject  message author
     * @param subject        mail subject, or {@code null}
     * @param sentAt         server send instant
     * @param body           immutable message content
     * @param folder         requesting participant's folder key
     * @return the immutable hit
     */
    public record SearchHit(String messageId, String conversationId, String senderSubject,
                            String subject, Instant sentAt, String body, String folder) {
    }

    /**
     * One cursor-paged search result.
     *
     * @param hits       messages in stable sent-at descending order
     * @param nextCursor opaque cursor for the next page, or {@code null} on the last page
     * @return the immutable page
     */
    public record SearchPage(List<SearchHit> hits, String nextCursor) {
        /**
         * Freeze the page so a caller cannot mutate the returned projection.
         */
        public SearchPage {
            hits = List.copyOf(hits);
        }

        /**
         * Return the immutable hit list.
         *
         * @return page hits
         */
        @Override
        public List<SearchHit> hits() {
            return List.copyOf(hits);
        }
    }

    /**
     * A records-officer conversation export whose checksums are assembled from
     * the conversation's attachment links at export time. Only metadata is
     * persisted; attachment bytes never leave the private object store.
     *
     * @param id             export identifier
     * @param organizationId owning organization
     * @param conversationId exported conversation
     * @param createdBy      authorized records officer
     * @param createdAt      server creation instant
     * @param expiresAt      server expiry instant of the scoped copy
     * @param checksums      checksums of every attachment in the conversation
     * @param provenance     non-sensitive source provenance for the copy
     * @return the immutable export
     */
    public record ConversationExport(String id, String organizationId, String conversationId,
                                     String createdBy, Instant createdAt, Instant expiresAt,
                                     List<String> checksums, ExportProvenance provenance) {
        /**
         * Freeze the checksum snapshot.
         */
        public ConversationExport {
            checksums = List.copyOf(checksums);
        }
    }

    /**
     * Record one audited staff action performed by an authorized workflow.
     *
     * @param action     action wire key
     * @param resourceId affected conversation or message
     * @param unitId     facility or system scope
     * @return the command
     */
    public record RecordActionRequest(String action, String resourceId, String unitId) {
    }

    /**
     * Hide a message in the actor's folder without purging the durable row.
     *
     * @param messageId      message to hide
     * @param conversationId owning conversation, checked for participation
     * @return the command
     */
    public record HideRequest(String messageId, String conversationId) {
    }

    /**
     * Produce an export of one conversation for a records officer.
     *
     * @param conversationId conversation to export
     * @param provenance     non-sensitive source provenance for the copy
     * @return the command
     */
    public record ExportRequest(String conversationId, ExportProvenance provenance) {
    }
}
