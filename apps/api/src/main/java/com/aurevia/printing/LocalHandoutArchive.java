package com.aurevia.printing;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.aurevia.printing.HandoutContract.Handout;
import com.aurevia.printing.HandoutContract.RenderedHandout;

/**
 * In-process handout registry for the rendering service.
 *
 * <p>State is keyed by tenant, kind and source, so two tenants using the same
 * local identifier never share a version or a rendering. A rendered handout is
 * immutable and is never overwritten: a changed source revision receives the
 * next version. The durable, audited store is a separate delivery step; this
 * registry only makes a reprint stable within the running process.</p>
 */
public final class LocalHandoutArchive implements HandoutArchive {

    private final ConcurrentMap<String, Revision> lineages = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RenderedHandout> handouts = new ConcurrentHashMap<>();

    /**
     * Create an empty archive.
     */
    public LocalHandoutArchive() {
    }

    /**
     * Find an earlier rendering of this exact source revision and language.
     *
     * @param organizationId owning tenant
     * @param kind           handout kind
     * @param sourceId       bound source identifier
     * @param revisionVersion bound source revision
     * @param contentChecksum bound source checksum
     * @param language       rendered language
     * @return the immutable original, or empty when none exists
     */
    @Override
    public Optional<RenderedHandout> find(String organizationId, HandoutKind kind, String sourceId,
                                          int revisionVersion, String contentChecksum, String language) {
        return Optional.ofNullable(handouts.get(
                key(organizationId, kind, sourceId, revisionVersion, contentChecksum, language)));
    }

    /**
     * Resolve and record the version bound to one source revision.
     *
     * @param organizationId owning tenant
     * @param kind           handout kind
     * @param sourceId       bound source identifier
     * @param revisionVersion bound source revision
     * @param contentChecksum bound source checksum
     * @return the bound version
     */
    @Override
    public int versionFor(String organizationId, HandoutKind kind, String sourceId,
                          int revisionVersion, String contentChecksum) {
        return lineages.compute(lineageKey(organizationId, kind, sourceId), (ignored, current) -> {
            if (current == null) {
                return new Revision(1, revisionVersion, contentChecksum);
            }
            if (current.revisionVersion() == revisionVersion
                    && current.checksum().equals(contentChecksum)) {
                return current;
            }
            return new Revision(current.version() + 1, revisionVersion, contentChecksum);
        }).version();
    }

    /**
     * Store an immutable handout for later reprints.
     *
     * @param handout rendered bytes and metadata
     */
    @Override
    public void save(RenderedHandout handout) {
        Handout metadata = handout.metadata();
        handouts.put(key(metadata.organizationId(), metadata.kind(), metadata.sourceId(),
                metadata.sourceRevision(), metadata.sourceChecksum(), metadata.language()), handout);
    }

    private static String lineageKey(String organizationId, HandoutKind kind, String sourceId) {
        return organizationId + "|" + kind.wire() + "|" + sourceId;
    }

    private static String key(String organizationId, HandoutKind kind, String sourceId,
                              int revisionVersion, String checksum, String language) {
        return lineageKey(organizationId, kind, sourceId) + "|" + revisionVersion + "|"
                + checksum + "|" + language;
    }

    /**
     * The last source revision seen for one lineage and its current version.
     *
     * @param version         current handout version
     * @param revisionVersion bound source revision
     * @param checksum        bound source checksum
     * @return this record
     */
    private record Revision(int version, int revisionVersion, String checksum) {
    }
}
