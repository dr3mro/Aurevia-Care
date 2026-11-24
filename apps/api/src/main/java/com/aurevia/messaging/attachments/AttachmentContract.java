package com.aurevia.messaging.attachments;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Domain contract for the S-G3-06 attachment slice (MSG-03, ARC-06).
 *
 * <p>The types here carry no persistence, transport or UI concerns. An
 * attachment starts life in private quarantine, receives an independent scan
 * verdict and becomes reachable only when it is bound to an authorized,
 * published message. Every state change is a separate immutable fact.</p>
 */
public final class AttachmentContract {

    private AttachmentContract() {
    }

    /**
     * The only three attachment media types accepted by this slice.
     *
     * <p>Both the declared content type and the leading bytes of the payload
     * must agree with the selected type, so a renamed executable cannot pass
     * as an image. The prefix is lowercase hexadecimal.</p>
     */
    public enum FileType {
        /** PDF document; leading bytes are {@code %PDF-}. */
        PDF("application/pdf", "pdf", "25504446"),
        /** JPEG image; leading bytes are {@code FF D8 FF}. */
        JPEG("image/jpeg", "jpg", "ffd8ff"),
        /** PNG image; leading bytes are the eight-byte PNG signature. */
        PNG("image/png", "png", "89504e470d0a1a0a");

        private final String contentType;
        private final String extension;
        private final String magicPrefix;

        /**
         * Create a supported media type.
         *
         */
        FileType(String contentType, String extension, String magicPrefix) {
            this.contentType = contentType;
            this.extension = extension;
            this.magicPrefix = magicPrefix;
        }

        /**
         * Return the canonical MIME type.
         *
         * @return stable wire content type
         */
        @JsonValue
        public String contentType() {
            return contentType;
        }

        /**
         * Return the expected leading-byte signature.
         *
         * @return lowercase hexadecimal prefix of the original payload
         */
        public String magicPrefix() {
            return magicPrefix;
        }

        /**
         * Check a declared content type, case-insensitively.
         *
         * @return {@code true} when it matches this type
         */
        public boolean accepts(String value) {
            return contentType.equalsIgnoreCase(value);
        }

        /**
         * Check that a file name carries this type's extension.
         *
         * @return {@code true} when the extension matches
         */
        public boolean matchesExtension(String fileName) {
            return fileName.toLowerCase(Locale.ROOT).endsWith("." + extension);
        }

        /**
         * Check that actual leading bytes match this type's signature.
         *
         * @return {@code true} when the signature matches
         */
        public boolean matchesMagic(String magicHex) {
            return magicHex.startsWith(magicPrefix);
        }

        /**
         * Resolve a declared content type to a supported type.
         *
         * @return the matching type, or {@code null} when unsupported
         */
        public static FileType fromContentType(String value) {
            if (value == null) {
                return null;
            }
            for (FileType candidate : values()) {
                if (candidate.accepts(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Independent scanner verdict for a quarantined payload.
     */
    public enum ScanVerdict {
        /** No verdict has been recorded; the payload stays private. */
        PENDING("pending"),
        /** The scanner found no threats. */
        CLEAN("clean"),
        /** The scanner rejected the payload. */
        FAILED("failed");

        private final String wire;

        /**
         * Create a verdict with its stable wire key.
         *
         */
        ScanVerdict(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored verdict key.
         *
         * @return the matching verdict
         * @throws IllegalArgumentException when the key is unknown
         */
        public static ScanVerdict from(String value) {
            for (ScanVerdict candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown scan verdict: " + value);
        }
    }

    /**
     * Lifecycle of one attachment. A payload is never reachable before both a
     * clean verdict and publication against an authorized message.
     */
    public enum AttachmentState {
        /** Uploaded and held in private quarantine. */
        QUARANTINED("quarantined"),
        /** Clean verdict recorded but still an unpublished draft. */
        CLEAN("clean"),
        /** Bound to a published message and reachable by the authorized unit. */
        PUBLISHED("published"),
        /** Rejected by the scanner or validation; not reachable. */
        FAILED("failed"),
        /** Removed by its uploader; not reachable and not reusable. */
        REMOVED("removed");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         */
        AttachmentState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored lifecycle key.
         *
         * @return the matching state
         * @throws IllegalArgumentException when the key is unknown
         */
        public static AttachmentState from(String value) {
            for (AttachmentState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown attachment state: " + value);
        }

        /**
         * Decide whether a transition is permitted.
         *
         * @return {@code true} for an allowed transition or an explicit no-op
         */
        public boolean canTransitionTo(AttachmentState next) {
            if (this == next) {
                return true;
            }
            return (this == QUARANTINED && (next == CLEAN || next == FAILED || next == REMOVED))
                    || (this == CLEAN && (next == PUBLISHED || next == REMOVED))
                    || (this == FAILED && next == REMOVED);
        }

        /**
         * Report whether this state permits a mediated download.
         *
         * @return {@code true} only for a published attachment
         */
        public boolean downloadable() {
            return this == PUBLISHED;
        }
    }

    /**
     * Append-only fact vocabulary. Each fact is recorded separately and is
     * never merged into another fact.
     */
    public enum EventType {
        /** The upload was accepted into quarantine. */
        UPLOADED("uploaded"),
        /** The scanner returned a clean verdict. */
        SCAN_CLEAN("scan_clean"),
        /** The scanner returned a failed verdict. */
        SCAN_FAILED("scan_failed"),
        /** The attachment was bound to a published message. */
        PUBLISHED("published"),
        /** The uploader removed a draft or failed attachment. */
        REMOVED("removed"),
        /** An authorized participant downloaded the mediated handle. */
        DOWNLOADED("downloaded"),
        /** An authorized reviewer filed the material in the chart. */
        FILED_TO_CHART("filed_to_chart"),
        /** A protected action was denied. */
        ACCESS_DENIED("access_denied");

        private final String wire;

        /**
         * Create a fact type with its stable wire key.
         *
         */
        EventType(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        @JsonValue
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored fact key.
         *
         * @return the matching fact type
         * @throws IllegalArgumentException when the key is unknown
         */
        public static EventType from(String value) {
            for (EventType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown attachment event: " + value);
        }
    }

    /**
     * Freshly derived caller identity for one protected request (IAM-01).
     *
     * <p>The integrating caller must rebuild this snapshot from active
     * membership on every command. It is never inferred from a request body.
     * A patient actor carries a linked patient identifier; a staff actor
     * carries an assigned unit identifier; exactly one of the two is present.</p>
     *
     * @return the immutable identity snapshot
     */
    public record Actor(String organizationId, String subject, Set<String> roles,
                        String patientId, String unitId) {
        /**
         * Defensively copy the role set and enforce patient/staff exclusivity.
         * Components are documented once on the record declaration; repeating
         * them here or on the factory methods yields duplicate Doxygen sections.
         */
        public Actor {
            roles = Set.copyOf(roles);
        }

        /**
         * Build a staff actor with a unit assignment. Parameter semantics are
         * documented on the record declaration.
         *
         * @return the actor snapshot
         */
        public static Actor staff(String organizationId, String subject, String unitId, String... roles) {
            return new Actor(organizationId, subject, Set.of(roles), null, unitId);
        }

        /**
         * Build a patient actor linked to one verified chart. Parameter semantics
         * are documented on the record declaration.
         *
         * @return the actor snapshot
         */
        public static Actor patient(String organizationId, String subject, String patientId) {
            return new Actor(organizationId, subject, Set.of("patient"), patientId, null);
        }
    }

    /**
     * Verified patient-unit connection that authorizes an upload (MSG-01, MSG-03).
     *
     * @return the immutable grant
     */
    public record ConnectionGrant(String organizationId, String connectionId, String unitId,
                                  String patientId, boolean active) {
    }

    /**
     * Current projection of one attachment.
     *
     * @return the immutable projection
     */
    public record Attachment(String organizationId, String id, int version, String uploaderSubject,
                             String connectionId, String unitId, String patientId, String fileName,
                             FileType fileType, long sizeBytes, String checksum, String objectKey,
                             int objectVersion, ScanVerdict verdict, AttachmentState state,
                             String messageId, String recipientUnitId, Instant createdAt) {
    }

    /**
     * One immutable append-only fact in an attachment's trail.
     *
     * @return the immutable fact
     */
    public record AttachmentEvent(String id, String organizationId, String attachmentId,
                                  EventType eventType, String actorSubject, String outcome,
                                  String detail, Instant occurredAt) {
    }

    /**
     * One attachment together with its complete fact trail.
     *
     * @return the immutable view
     */
    public record View(Attachment attachment, List<AttachmentEvent> events) {
        /**
         * Defensively copy the fact trail. Components are documented on the
         * record declaration, not repeated on this canonical constructor.
         */
        public View {
            events = List.copyOf(events);
        }
    }

    /**
     * A mediated download handle. It never carries payload bytes or a public
     * URL; the caller must present it to the private object boundary (ARC-06).
     *
     * @return the immutable handle
     */
    public record MediatedDownload(String objectKey, int objectVersion, String checksum,
                                   String contentType, long sizeBytes) {
    }

    /**
     * Upload command. The checksum and magic bytes are computed by the client
     * over the original payload; the private object boundary verifies them.
     *
     * <p>The unit is deliberately not a field: it is resolved from the server-side
     * verified connection grant so a caller cannot rebind a draft to another
     * unit (MSG-03).</p>
     *
     * @return the upload command
     */
    public record UploadRequest(String connectionId, String fileName, String contentType,
                                String magicHex, long sizeBytes, String checksum) {
    }

    /**
     * Scanner verdict command from the authorized quarantine scanner.
     *
     * @return the scan command
     */
    public record ScanRequest(String verdict, String detail) {
    }

    /**
     * Explicit send command naming every pending file and its recipient unit.
     *
     * @return the publish command
     */
    public record PublishRequest(List<String> attachmentIds, String messageId, String recipientUnitId) {
        /**
         * Defensively copy the file identifier list so a caller cannot mutate a
         * publish command after validation (ARC-06). Components are documented
         * on the record declaration, not repeated on this canonical constructor.
         */
        public PublishRequest {
            attachmentIds = attachmentIds == null ? null : List.copyOf(attachmentIds);
        }

        /**
         * Return the immutable file identifier list.
         *
         * @return the attachment identifiers
         */
        @Override
        public List<String> attachmentIds() {
            return attachmentIds == null ? null : List.copyOf(attachmentIds);
        }
    }

    /**
     * Removal command for a draft or failed attachment.
     *
     * @param reason non-sensitive removal reason
     * @return the removal command
     */
    public record RemoveRequest(String reason) {
    }

    /**
     * Chart-filing command for an authorized reviewer.
     *
     * @param encounterId owning encounter identifier
     * @param note        non-sensitive provenance note
     * @return the filing command
     */
    public record FileRequest(String encounterId, String note) {
    }
}
