package com.aurevia.smartclinic.messaging.s_g3_06;

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
         * @param contentType  canonical MIME type
         * @param extension    required file extension
         * @param magicPrefix  lowercase hexadecimal leading-byte signature
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
         * @param value declared MIME type
         * @return {@code true} when it matches this type
         */
        public boolean accepts(String value) {
            return contentType.equalsIgnoreCase(value);
        }

        /**
         * Check that a file name carries this type's extension.
         *
         * @param fileName normalized lowercased file name
         * @return {@code true} when the extension matches
         */
        public boolean matchesExtension(String fileName) {
            return fileName.toLowerCase(Locale.ROOT).endsWith("." + extension);
        }

        /**
         * Check that actual leading bytes match this type's signature.
         *
         * @param magicHex lowercase hexadecimal leading bytes
         * @return {@code true} when the signature matches
         */
        public boolean matchesMagic(String magicHex) {
            return magicHex.startsWith(magicPrefix);
        }

        /**
         * Resolve a declared content type to a supported type.
         *
         * @param value declared MIME type
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
         * @param wire lowercase key
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
         * @param value stored key
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
         * @param wire lowercase key
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
         * Decide whether a transition is permitted.
         *
         * @param next requested target state
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
         * @param wire lowercase key
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
         * @param value stored key
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
     * @param organizationId active organization identifier
     * @param subject        authenticated synthetic subject
     * @param roles          currently active role keys
     * @param patientId      linked patient identifier for a patient actor, otherwise {@code null}
     * @param unitId         assigned care-unit identifier for a staff actor, otherwise {@code null}
     * @return the immutable identity snapshot
     */
    public record Actor(String organizationId, String subject, Set<String> roles,
                        String patientId, String unitId) {
        /**
         * Defensively copy the role set and enforce patient/staff exclusivity.
         *
         * @param organizationId active organization identifier
         * @param subject        authenticated synthetic subject
         * @param roles          currently active role keys
         * @param patientId      linked patient identifier, or {@code null}
         * @param unitId         assigned unit identifier, or {@code null}
         */
        public Actor {
            roles = Set.copyOf(roles);
        }

        /**
         * Build a staff actor with a unit assignment.
         *
         * @param organizationId active organization identifier
         * @param subject        authenticated subject
         * @param unitId         assigned care-unit identifier
         * @param roles          active role keys
         * @return the actor snapshot
         */
        public static Actor staff(String organizationId, String subject, String unitId, String... roles) {
            return new Actor(organizationId, subject, Set.of(roles), null, unitId);
        }

        /**
         * Build a patient actor linked to one verified chart.
         *
         * @param organizationId active organization identifier
         * @param subject        authenticated subject
         * @param patientId      verified linked patient identifier
         * @return the actor snapshot
         */
        public static Actor patient(String organizationId, String subject, String patientId) {
            return new Actor(organizationId, subject, Set.of("patient"), patientId, null);
        }
    }

    /**
     * Verified patient-unit connection that authorizes an upload (MSG-01, MSG-03).
     *
     * @param organizationId owning organization
     * @param connectionId   tenant-local connection identifier
     * @param unitId         care unit that owns the conversation
     * @param patientId      verified patient chart linked by the connection
     * @param active         whether new messaging is currently permitted
     * @return the immutable grant
     */
    public record ConnectionGrant(String organizationId, String connectionId, String unitId,
                                  String patientId, boolean active) {
    }

    /**
     * Current projection of one attachment.
     *
     * @param organizationId owning organization
     * @param id             tenant-local attachment identifier
     * @param version        optimistic version, advanced by every state change
     * @param uploaderSubject authenticated subject that bound the upload
     * @param connectionId   connection the upload is bound to
     * @param unitId         care unit the upload is bound to
     * @param patientId      patient chart the upload is bound to
     * @param fileName       validated original file name
     * @param fileType       validated media type
     * @param sizeBytes      validated payload size
     * @param checksum       SHA-256 checksum of the original payload
     * @param objectKey      unique private object key
     * @param objectVersion  immutable object-store version
     * @param verdict        independent scanner verdict
     * @param state          current lifecycle state
     * @param messageId      published message identifier, once published
     * @param recipientUnitId unit that received the published material
     * @param createdAt      server upload instant
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
     * @param id             fact identifier
     * @param organizationId owning organization
     * @param attachmentId   owning attachment
     * @param eventType      fact type
     * @param actorSubject   authenticated actor, {@code null} for system facts
     * @param outcome        {@code success}, {@code denied}, {@code conflict} or {@code not_found}
     * @param detail         non-sensitive detail; never payload bytes or a URL
     * @param occurredAt     server instant
     * @return the immutable fact
     */
    public record AttachmentEvent(String id, String organizationId, String attachmentId,
                                  EventType eventType, String actorSubject, String outcome,
                                  String detail, Instant occurredAt) {
    }

    /**
     * One attachment together with its complete fact trail.
     *
     * @param attachment current projection
     * @param events     immutable facts in recorded order
     * @return the immutable view
     */
    public record View(Attachment attachment, List<AttachmentEvent> events) {
        /**
         * Defensively copy the fact trail.
         *
         * @param attachment current projection
         * @param events     fact trail
         */
        public View {
            events = List.copyOf(events);
        }
    }

    /**
     * A mediated download handle. It never carries payload bytes or a public
     * URL; the caller must present it to the private object boundary (ARC-06).
     *
     * @param objectKey     unique private object key
     * @param objectVersion verified immutable object version
     * @param checksum      expected checksum of the original payload
     * @param contentType   validated media type
     * @param sizeBytes     validated payload size
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
     * @param connectionId verified connection identifier
     * @param fileName     original file name
     * @param contentType  declared MIME type
     * @param magicHex     leading bytes as hexadecimal
     * @param sizeBytes    payload size
     * @param checksum     SHA-256 checksum as lowercase hexadecimal
     * @return the upload command
     */
    public record UploadRequest(String connectionId, String fileName, String contentType,
                                String magicHex, long sizeBytes, String checksum) {
    }

    /**
     * Scanner verdict command from the authorized quarantine scanner.
     *
     * @param verdict {@code clean} or {@code failed}
     * @param detail  non-sensitive scanner detail
     * @return the scan command
     */
    public record ScanRequest(String verdict, String detail) {
    }

    /**
     * Explicit send command naming every pending file and its recipient unit.
     *
     * @param attachmentIds   pending attachment identifiers, non-empty and distinct
     * @param messageId       durable client message identifier
     * @param recipientUnitId unit that must match every attachment's bound unit
     * @return the publish command
     */
    public record PublishRequest(List<String> attachmentIds, String messageId, String recipientUnitId) {
        /**
         * Defensively copy the file identifier list so a caller cannot mutate a
         * publish command after validation (ARC-06).
         *
         * @param attachmentIds pending attachment identifiers
         * @param messageId     durable client message identifier
         * @param recipientUnitId receiving care unit identifier
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
