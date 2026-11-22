package com.aurevia.smartclinic.staffmsg.attachments;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;
import com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ScanState;

/**
 * Value types for the staff-message attachment slice (MSG-03, CLN-07).
 *
 * <p>An upload is bound to its uploader and a chosen conversation or mail draft,
 * held in private quarantine until an independent scan verdict is recorded. A
 * quarantined file can never be attached to a sent message. A clean file becomes
 * downloadable only after an explicit send that names the pending files, and a
 * download is authorized per recipient and recorded as an append-only fact.
 * The three allowed media types and the scan-state vocabulary are reused from
 * the document and staff-messaging domains rather than duplicated here.</p>
 */
public final class StaffAttachmentContract {

    private StaffAttachmentContract() {
    }

    /**
     * Append-only audit vocabulary for one attachment's lifecycle.
     */
    public enum AuditType {
        /** A validated payload was accepted into quarantine. */
        UPLOADED("uploaded"),
        /** The scanner returned a clean verdict. */
        SCANNED_CLEAN("scanned_clean"),
        /** The scanner found a threat and the file was quarantined. */
        SCANNED_QUARANTINED("scanned_quarantined"),
        /** A clean file was bound to a published message. */
        SENT("sent"),
        /** An authorized recipient obtained the verified bytes. */
        DOWNLOADED("downloaded"),
        /** A protected action was denied or blocked. */
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
     * Freshly derived staff caller identity for one protected request (IAM-01).
     *
     * @return the immutable identity snapshot
     */
    public record StaffActor(String organizationId, String subject, Set<String> roles) {
        /**
         * Defensively copy the role set so a caller cannot widen it after the
         * authorization decision.
         */
        public StaffActor {
            roles = Set.copyOf(roles);
        }

        /**
         * Build a staff actor from active membership roles.
         *
         * @param organizationId active organization
         * @param subject        authenticated subject
         * @param roles          active role keys
         * @return the actor snapshot
         */
        public static StaffActor staff(String organizationId, String subject, String... roles) {
            return new StaffActor(organizationId, subject, Set.of(roles));
        }
    }

    /**
     * Current projection of one staff-message attachment.
     *
     * @return the immutable projection
     */
    public record StaffAttachment(String organizationId, String id, String uploaderSubject,
                                  String draftId, String documentId, String fileName, String contentType,
                                  long sizeBytes, String checksum, String objectKey, int objectVersion,
                                  ScanState scanState, String messageId, boolean sent, Instant createdAt) {

        /**
         * Report whether the file is clean and bound to a published message.
         *
         * @return {@code true} only for a clean, sent attachment
         */
        public boolean downloadable() {
            return sent && scanState == ScanState.CLEAN;
        }
    }

    /**
     * One immutable append-only fact in an attachment's audit trail.
     *
     * @return the immutable fact
     */
    public record AttachmentAudit(String id, String organizationId, String attachmentId, AuditType type,
                                  String actorSubject, String outcome, String detail, Instant occurredAt) {
    }

    /**
     * Upload command. The bytes are the original payload; the checksum is the
     * client's integrity claim, re-verified against the bytes before quarantine.
     *
     * @return the upload command
     */
    public record UploadRequest(String draftId, String contentType, String fileName,
                                byte[] content, String checksum) {
        /**
         * Defensively copy the payload so a caller cannot mutate it after validation.
         */
        public UploadRequest {
            content = content == null ? null : content.clone();
        }

        /**
         * Return a copy of the original payload bytes.
         *
         * @return payload bytes
         */
        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    /**
     * Scanner verdict command from the authorized quarantine scanner.
     *
     * @return the scan command
     */
    public record ScanRequest(String verdict, String detail) {
    }

    /**
     * Explicit send command naming the pending files, the recipients and the
     * confirmation flag. MSG-03 requires the sender to see the recipient and the
     * pending files and to confirm before publication.
     *
     * @return the send command
     */
    public record SendRequest(String draftId, String messageId, List<String> attachmentIds,
                              Set<String> recipients, boolean confirm) {
        /**
         * Defensively copy the collections so the command is immutable.
         */
        public SendRequest {
            attachmentIds = attachmentIds == null ? null : List.copyOf(attachmentIds);
            recipients = recipients == null ? null : Set.copyOf(recipients);
        }

        /**
         * Return the immutable file identifier list.
         *
         * @return attachment identifiers
         */
        @Override
        public List<String> attachmentIds() {
            return attachmentIds == null ? null : List.copyOf(attachmentIds);
        }

        /**
         * Return the immutable recipient set.
         *
         * @return recipient subjects
         */
        @Override
        public Set<String> recipients() {
            return recipients == null ? null : Set.copyOf(recipients);
        }
    }

    /**
     * Verified original bytes and their metadata returned by a download.
     *
     * @return the downloaded file
     */
    public record DownloadedFile(byte[] bytes, String checksum, String contentType, long sizeBytes) {
        /**
         * Defensively copy the payload so the caller cannot mutate the stored bytes.
         */
        public DownloadedFile {
            bytes = bytes.clone();
        }

        /**
         * Return a copy of the downloaded bytes.
         *
         * @return payload bytes
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * One attachment together with its audit trail and the scan failure reason
     * the sender needs to see (carried in the latest denial detail).
     *
     * @return the immutable view
     */
    public record View(StaffAttachment attachment, List<AttachmentAudit> audit) {
        /**
         * Defensively copy the audit trail.
         */
        public View {
            audit = List.copyOf(audit);
        }
    }
}
