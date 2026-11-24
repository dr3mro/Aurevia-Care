package com.aurevia.printing;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import com.aurevia.documents.DocumentContract.Actor;

/**
 * Value types shared by the handout policy, renderer, archive and service (CLN-07, ARC-06).
 *
 * <p>A source is an exact immutable revision of a clinical record selected by
 * the caller. The service never accepts rendered bytes or a version from the
 * caller: both are derived from the validated source and the tenant clock. The
 * value types are grouped here to keep the printing slice's change set small.</p>
 */
public final class HandoutContract {

    private HandoutContract() {
    }

    /**
     * One immutable source revision selected for a patient handout.
     *
     * <p>{@code state} and {@code attested} come from the authoritative record;
     * {@code printChannel} is the approved BUILD-10 channel on a prescription.
     * The checksum binds the rendered PDF to this exact source content. The
     * clinical lines are copied so a caller cannot edit content after the
     * render decision.</p>
     *
     * @param organizationId owning tenant
     * @param kind           handout kind
     * @param sourceId       tenant-local immutable source identifier
     * @param revisionVersion monotonic source revision
     * @param contentChecksum checksum of the exact source content
     * @param state          authoritative lifecycle state wire key
     * @param attested       whether the content carries a signature or attestation
     * @param printChannel   approved prescription print channel, otherwise {@code null}
     * @param patientId      handout patient identifier
     * @param patientName    patient name in the handout language
     * @param clinician      responsible clinician
     * @param facility       issuing facility
     * @param issueTime      clinical issue instant
     * @param sourceSystem   originating system for the provenance footer
     * @param contentLines   clinical content lines in display order
     * @return this record
     */
    public record HandoutSource(String organizationId, HandoutKind kind, String sourceId,
                                int revisionVersion, String contentChecksum, String state,
                                boolean attested, String printChannel, String patientId,
                                String patientName, String clinician, String facility,
                                Instant issueTime, String sourceSystem, List<String> contentLines) {

        /**
         * Freeze the supplied clinical lines on construction.
         */
        public HandoutSource {
            contentLines = List.copyOf(contentLines);
        }

        /**
         * Return an independent copy of the clinical lines.
         *
         * @return immutable content lines
         */
        @Override
        public List<String> contentLines() {
            return List.copyOf(contentLines);
        }
    }

    /**
     * Command to render one handout for a freshly derived caller.
     *
     * @param actor    caller identity, never inferred from the request body
     * @param language {@code en} or {@code ar}
     * @param source   exact source revision to render
     * @return this record
     */
    public record HandoutRequest(Actor actor, String language, HandoutSource source) {
    }

    /**
     * Immutable metadata of one rendered handout.
     *
     * <p>The handout version advances only when the bound source revision
     * changes. {@code handoutId} and the content checksum are deterministic, so
     * a reprint of an unchanged source returns the same identity and bytes.</p>
     *
     * @param organizationId owning tenant
     * @param handoutId      deterministic tenant-local handout key
     * @param kind           handout kind
     * @param sourceId       bound source identifier
     * @param sourceRevision bound source revision
     * @param sourceChecksum bound source checksum
     * @param version        immutable handout version
     * @param language       rendered language
     * @param direction      LTR or RTL layout direction
     * @param pageCount      number of A4 pages
     * @param byteSize       rendered byte count
     * @param sha256         checksum of the rendered bytes
     * @param provenance     source system, identifier and revision
     * @param producedAt     server render instant
     * @param producedBy     authenticated subject that requested the handout
     * @return this record
     */
    public record Handout(String organizationId, String handoutId, HandoutKind kind, String sourceId,
                          int sourceRevision, String sourceChecksum, int version, String language,
                          String direction, int pageCount, long byteSize, String sha256,
                          String provenance, Instant producedAt, String producedBy) {
    }

    /**
     * Rendered handout bytes together with their immutable metadata.
     *
     * <p>The byte array is copied on store and on read so neither the archive
     * nor a caller can alter a version after it has been produced.</p>
     *
     * @param metadata immutable handout metadata
     * @param content  rendered PDF bytes
     * @return this record
     */
    public record RenderedHandout(Handout metadata, byte[] content) {

        /**
         * Freeze the rendered bytes on construction.
         */
        public RenderedHandout {
            content = Arrays.copyOf(content, content.length);
        }

        /**
         * Return an independent copy of the rendered bytes.
         *
         * @return PDF bytes
         */
        @Override
        public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }
}
