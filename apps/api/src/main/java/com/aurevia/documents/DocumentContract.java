package com.aurevia.documents;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Domain contract for the X-03 document artifact slice (ARC-06).
 *
 * <p>The types here carry no persistence, transport or UI concerns. A payload
 * starts in private quarantine, receives an independent scan verdict and a
 * verified object version, and is downloaded only through a mediated handle.
 * Rendered PDFs are bound to an exact immutable revision before a release that
 * requires one can be recorded. Every fact is append-only and free of payload
 * bytes, original file names and URLs.</p>
 */
public final class DocumentContract {

    private DocumentContract() {
    }

    /**
     * The only three payload media types accepted by this slice.
     *
     * <p>Both the declared content type and the leading bytes of the payload
     * must agree with the selected type, and the file name must carry its
     * extension. The magic prefix is lowercase hexadecimal.</p>
     */
    public enum MediaType {
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
         * @param contentType canonical MIME type
         * @param extension   required file extension
         * @param magicPrefix lowercase hexadecimal leading-byte signature
         */
        MediaType(String contentType, String extension, String magicPrefix) {
            this.contentType = contentType;
            this.extension = extension;
            this.magicPrefix = magicPrefix;
        }

        /**
         * Return the canonical MIME type.
         *
         * @return stable wire content type
         */
        public String contentType() {
            return contentType;
        }

        /**
         * Return the expected leading-byte signature.
         *
         * @return lowercase hexadecimal payload prefix
         */
        public String magicPrefix() {
            return magicPrefix;
        }

        /**
         * Check a declared content type case-insensitively.
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
         * @param value declared MIME type, possibly {@code null}
         * @return the matching type, or {@code null} when unsupported
         */
        public static MediaType fromContentType(String value) {
            if (value == null) {
                return null;
            }
            for (MediaType candidate : values()) {
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
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored verdict key.
         *
         * @param value stored key, possibly {@code null}
         * @return the matching verdict, or {@code null} when unknown
         */
        public static ScanVerdict from(String value) {
            if (value == null) {
                return null;
            }
            for (ScanVerdict candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Lifecycle of one quarantined artifact. Only a completed artifact with a
     * verified object version may be downloaded.
     */
    public enum ArtifactState {
        /** Uploaded and held in private quarantine. */
        QUARANTINED("quarantined"),
        /** Clean verdict recorded but not yet completed. */
        CLEAN("clean"),
        /** Rejected by validation or the scanner; never reachable. */
        FAILED("failed"),
        /** Authoritatively completed and downloadable through a mediated handle. */
        COMPLETED("completed"),
        /** Removed by its uploader; terminal and not reusable. */
        REMOVED("removed");

        private final String wire;

        /**
         * Create a state with its stable wire key.
         *
         * @param wire lowercase key
         */
        ArtifactState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        public String wire() {
            return wire;
        }

        /**
         * Report whether this state permits a mediated download.
         *
         * @return {@code true} only for a completed artifact
         */
        public boolean downloadable() {
            return this == COMPLETED;
        }

        /**
         * Parse a stored lifecycle key.
         *
         * @param value stored key, possibly {@code null}
         * @return the matching state, or {@code null} when unknown
         */
        public static ArtifactState from(String value) {
            if (value == null) {
                return null;
            }
            for (ArtifactState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Lifecycle of one asynchronous PDF rendering job.
     */
    public enum PdfJobState {
        /** Requested and waiting for a renderer to pick it up. */
        REQUESTED("requested"),
        /** Picked up by an authorized renderer. */
        RENDERING("rendering"),
        /** Rendered object verified and ready to bind. */
        COMPLETE("complete"),
        /** Render failed; no artifact may be bound from this job. */
        FAILED("failed");

        private final String wire;

        /**
         * Create a job state with its stable wire key.
         *
         * @param wire lowercase key
         */
        PdfJobState(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored render-job state key.
         *
         * @param value stored key, possibly {@code null}
         * @return the matching state, or {@code null} when unknown
         */
        public static PdfJobState from(String value) {
            if (value == null) {
                return null;
            }
            for (PdfJobState candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Language of a rendered PDF. Direction and font policy belong to the
     * rendering boundary; this slice records only the stable language tag.
     */
    public enum Language {
        /** English. */
        EN("en"),
        /** Arabic, rendered right-to-left by the rendering boundary. */
        AR("ar");

        private final String wire;

        /**
         * Create a language with its stable wire key.
         *
         * @param wire lowercase key
         */
        Language(String wire) {
            this.wire = wire;
        }

        /**
         * Return the wire key.
         *
         * @return stable lowercase key
         */
        public String wire() {
            return wire;
        }

        /**
         * Parse a language key.
         *
         * @param value stored key, possibly {@code null}
         * @return the matching language, or {@code null} when unknown
         */
        public static Language from(String value) {
            if (value == null) {
                return null;
            }
            for (Language candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Append-only fact vocabulary. Each fact is recorded separately and never
     * merged with another fact. No fact carries a payload or an object URL.
     */
    public enum EventType {
        /** A validated payload was accepted into quarantine. */
        UPLOAD_ACCEPTED("uploaded"),
        /** The scanner recorded a clean verdict. */
        SCAN_CLEAN("scan_clean"),
        /** The scanner recorded a failed verdict. */
        SCAN_FAILED("scan_failed"),
        /** An authorized reviewer completed a verified artifact. */
        UPLOAD_COMPLETED("upload_completed"),
        /** The uploader removed a draft or failed artifact. */
        ARTIFACT_REMOVED("artifact_removed"),
        /** An authorized participant obtained a mediated handle. */
        DOWNLOADED("downloaded"),
        /** A rendering job was requested. */
        RENDER_REQUESTED("render_requested"),
        /** A renderer picked up a requested job. */
        RENDER_PICKED_UP("render_picked_up"),
        /** A renderer verified and completed the rendered object. */
        RENDER_COMPLETE("render_complete"),
        /** A renderer reported a failure. */
        RENDER_FAILED("render_failed"),
        /** A completed PDF was bound to an immutable revision. */
        PDF_BOUND("pdf_bound"),
        /** A release was refused because a required PDF was incomplete. */
        RELEASE_BLOCKED("release_blocked"),
        /** A release decision was recorded. */
        RELEASED("released"),
        /** A protected action was denied or conflicted. */
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
        public String wire() {
            return wire;
        }

        /**
         * Parse a stored fact-type key.
         *
         * @param value stored key, possibly {@code null}
         * @return the matching type, or {@code null} when unknown
         */
        public static EventType from(String value) {
            if (value == null) {
                return null;
            }
            for (EventType candidate : values()) {
                if (candidate.wire.equals(value)) {
                    return candidate;
                }
            }
            return null;
        }
    }

    /**
     * Freshly derived caller identity for one protected request (IAM-01).
     *
     * <p>The integrating caller rebuilds this snapshot from active membership
     * on every command; it is never inferred from a request body. A patient
     * actor carries a verified linked chart identifier.</p>
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated synthetic subject
     * @param roles          currently active role keys
     * @param patientId      linked patient identifier for a patient actor, otherwise {@code null}
     * @return the immutable identity snapshot
     */
    public record Actor(String organizationId, String subject, Set<String> roles, String patientId) {
        /**
         * Defensively copy the role set so a caller cannot widen it after the
         * authorization decision. Components are documented on the record.
         */
        public Actor {
            roles = Set.copyOf(roles);
        }

        /**
         * Build a staff actor. Parameter semantics are documented on the record.
         *
         * @return the actor snapshot
         */
        public static Actor staff(String organizationId, String subject, String... roles) {
            return new Actor(organizationId, subject, Set.of(roles), null);
        }

        /**
         * Build a patient actor. Parameter semantics are documented on the record.
         *
         * @return the actor snapshot
         */
        public static Actor patient(String organizationId, String subject, String patientId) {
            return new Actor(organizationId, subject, Set.of("patient"), patientId);
        }
    }

    /**
     * Server-seeded authorization that binds an upload context to one verified
     * owner (MSG-01, ARC-06).
     *
     * @param organizationId owning organization
     * @param contextId      tenant-local context identifier
     * @param patientId      verified patient owner
     * @param active         whether the context still permits new uploads
     * @return the immutable grant
     */
    public record DocumentAuthorization(String organizationId, String contextId,
                                        String patientId, boolean active) {
    }

    /**
     * An immutable content revision that a rendered PDF may bind to.
     *
     * @param organizationId  owning organization
     * @param revisionId      tenant-local signed revision identifier
     * @param revisionVersion monotonic revision version
     * @param contentChecksum checksum of the exact signed content
     * @return the immutable revision selector
     */
    public record SourceRevision(String organizationId, String revisionId,
                                 int revisionVersion, String contentChecksum) {
    }

    /**
     * Upload command carrying only content metadata; all binding fields come
     * from the server-side authorization.
     *
     * @param contextId   verified upload context
     * @param fileName    original file name
     * @param contentType declared MIME type
     * @param magicHex    leading bytes as lowercase hexadecimal
     * @param sizeBytes   payload size
     * @param checksum    SHA-256 checksum as lowercase hexadecimal
     * @return the upload command
     */
    public record UploadRequest(String contextId, String fileName, String contentType,
                                String magicHex, long sizeBytes, String checksum) {
    }

    /**
     * Scanner verdict command.
     *
     * @param verdict {@code clean} or {@code failed}
     * @param detail  bounded non-sensitive scanner detail
     * @return the scan command
     */
    public record ScanRequest(String verdict, String detail) {
    }

    /**
     * Authoritative completion command.
     *
     * @param note bounded non-sensitive completion note
     * @return the completion command
     */
    public record CompleteRequest(String note) {
    }

    /**
     * Removal command for a draft or failed artifact.
     *
     * @param reason bounded non-sensitive reason
     * @return the removal command
     */
    public record RemoveRequest(String reason) {
    }

    /**
     * Asynchronous render request for one exact immutable revision.
     *
     * @param revision  revision to render
     * @param templateId approved template key
     * @param language  language key, {@code en} or {@code ar}
     * @return the render request
     */
    public record RenderRequest(SourceRevision revision, String templateId, String language) {
    }

    /**
     * Verified rendered object metadata produced by an authorized renderer.
     *
     * @param objectKey     private object key
     * @param objectVersion verified immutable object version
     * @param checksum      checksum of the rendered object
     * @param sizeBytes     rendered object size
     * @return the render result command
     */
    public record RenderFinishRequest(String objectKey, int objectVersion,
                                      String checksum, long sizeBytes) {
    }

    /**
     * Command binding a completed PDF job to an exact revision.
     *
     * @param revision revision selector
     * @param jobId    completed render job identifier
     * @return the binding command
     */
    public record BindRequest(SourceRevision revision, String jobId) {
    }

    /**
     * Release command. A required PDF must be bound to this exact revision
     * before a decision can be recorded.
     *
     * @param revision     revision selector
     * @param policyVersion approved release policy version
     * @param requirePdf   whether a completed PDF binding is mandatory
     * @return the release command
     */
    public record ReleaseRequest(SourceRevision revision, String policyVersion, boolean requirePdf) {
    }

    /**
     * Current projection of one quarantined artifact.
     *
     * @param organizationId owning organization
     * @param id             tenant-local artifact identifier
     * @param version        optimistic version advanced by each state change
     * @param uploaderSubject authenticated subject that bound the upload
     * @param contextId      server-side upload context
     * @param patientId      verified patient owner
     * @param fileName       validated original file name
     * @param fileType       validated media type
     * @param sizeBytes      validated payload size
     * @param checksum       original payload checksum
     * @param objectKey      unique private object key
     * @param objectVersion  immutable verified object version
     * @param verdict        independent scanner verdict
     * @param state          current lifecycle state
     * @param createdAt      server upload instant
     * @return the immutable projection
     */
    public record Artifact(String organizationId, String id, int version, String uploaderSubject,
                           String contextId, String patientId, String fileName, MediaType fileType,
                           long sizeBytes, String checksum, String objectKey, int objectVersion,
                           ScanVerdict verdict, ArtifactState state, Instant createdAt) {
    }

    /**
     * One asynchronous PDF rendering job.
     *
     * @param organizationId  owning organization
     * @param jobId           tenant-local job identifier
     * @param revisionId      immutable revision identifier
     * @param revisionVersion immutable revision version
     * @param contentChecksum checksum of the exact revision
     * @param templateId      approved template key
     * @param language        rendered language
     * @param state           current job state
     * @param version         optimistic version advanced by each transition
     * @param objectKey       verified rendered object key, once complete
     * @param objectVersion   verified rendered object version, once complete
     * @param checksum        rendered object checksum, once complete
     * @param sizeBytes       rendered object size, once complete
     * @param createdAt       server request instant
     * @return the immutable job projection
     */
    public record PdfJob(String organizationId, String jobId, String revisionId, int revisionVersion,
                         String contentChecksum, String templateId, Language language, PdfJobState state,
                         int version, String objectKey, int objectVersion, String checksum,
                         long sizeBytes, Instant createdAt) {
    }

    /**
     * Immutable binding between a completed PDF and one exact revision.
     *
     * @param organizationId  owning organization
     * @param revisionId      bound revision identifier
     * @param revisionVersion bound revision version
     * @param contentChecksum bound revision checksum
     * @param jobId           completed render job
     * @param objectKey       verified rendered object key
     * @param objectVersion   verified rendered object version
     * @param checksum        rendered object checksum
     * @param language        rendered language
     * @param boundAt         server binding instant
     * @return the immutable binding
     */
    public record PdfBinding(String organizationId, String revisionId, int revisionVersion,
                             String contentChecksum, String jobId, String objectKey,
                             int objectVersion, String checksum, Language language, Instant boundAt) {
    }

    /**
     * Immutable release decision for one exact revision and policy version.
     *
     * @param organizationId  owning organization
     * @param revisionId      released revision identifier
     * @param revisionVersion released revision version
     * @param contentChecksum released revision checksum
     * @param policyVersion   applied release policy version
     * @param releasedBy      authorized actor subject
     * @param releasedAt      server decision instant
     * @return the immutable decision
     */
    public record ReleaseDecision(String organizationId, String revisionId, int revisionVersion,
                                  String contentChecksum, String policyVersion, String releasedBy,
                                  Instant releasedAt) {
    }

    /**
     * Mediated download handle. It carries metadata only: no payload bytes,
     * original file name or public URL (ARC-06).
     *
     * @param objectKey     unique private object key
     * @param objectVersion verified immutable object version
     * @param checksum      expected payload checksum
     * @param contentType   validated media type
     * @param sizeBytes     validated payload size
     * @return the immutable handle
     */
    public record MediatedDownload(String objectKey, int objectVersion, String checksum,
                                   String contentType, long sizeBytes) {
    }

    /**
     * One append-only fact in the tenant's document ledger.
     *
     * @param id            fact identifier
     * @param organizationId owning organization
     * @param resourceId    owning artifact, job or revision identifier
     * @param eventType     fact type
     * @param actorSubject  authenticated actor, {@code null} for a system fact
     * @param outcome       {@code success}, {@code denied}, {@code conflict} or {@code invalid}
     * @param detail        bounded non-sensitive detail; never payload, name or URL
     * @param occurredAt    server instant
     * @return the immutable fact
     */
    public record DocumentEvent(String id, String organizationId, String resourceId,
                                EventType eventType, String actorSubject, String outcome,
                                String detail, Instant occurredAt) {
    }

    /**
     * One artifact together with its complete fact trail.
     *
     * @param artifact current projection
     * @param events   immutable facts in recorded order
     * @return the immutable view
     */
    public record View(Artifact artifact, List<DocumentEvent> events) {
        /**
         * Defensively copy the fact trail so later caller mutation cannot
         * change a returned view. Components are documented on the record.
         */
        public View {
            events = List.copyOf(events);
        }

        /**
         * Return the immutable fact trail. The second copy keeps the accessor
         * safe even if a caller retained a mutable reference before construction.
         *
         * @return immutable facts in recorded order
         */
        @Override
        public List<DocumentEvent> events() {
            return List.copyOf(events);
        }
    }
}
