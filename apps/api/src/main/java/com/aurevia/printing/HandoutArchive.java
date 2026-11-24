package com.aurevia.printing;

import java.util.Optional;

import com.aurevia.printing.HandoutContract.RenderedHandout;

/**
 * Tenant-scoped registry of immutable handouts produced by the printing service (ARC-06).
 *
 * <p>The archive gives the rendering service a stable version within one
 * process: an unchanged source revision returns the original rendered bytes,
 * while a changed revision advances the version. Durable, audited persistence
 * of these handouts belongs to the print endpoint story; this boundary keeps
 * that decision out of the renderer.</p>
 */
public interface HandoutArchive {

    /**
     * Find a handout already rendered for an exact source revision and language.
     *
     * @param organizationId  owning tenant
     * @param kind             handout kind
     * @param sourceId         bound source identifier
     * @param revisionVersion  bound source revision
     * @param contentChecksum  bound source checksum
     * @param language         rendered language
     * @return the original handout, or empty when none was rendered
     */
    Optional<RenderedHandout> find(String organizationId, HandoutKind kind, String sourceId,
                                   int revisionVersion, String contentChecksum, String language);

    /**
     * Resolve the immutable version for a source revision, creating a new
     * version only when the bound revision or its checksum changed.
     *
     * @param organizationId  owning tenant
     * @param kind             handout kind
     * @param sourceId         bound source identifier
     * @param revisionVersion  bound source revision
     * @param contentChecksum  bound source checksum
     * @return the version bound to this source revision
     */
    int versionFor(String organizationId, HandoutKind kind, String sourceId,
                   int revisionVersion, String contentChecksum);

    /**
     * Store one immutable rendered handout.
     *
     * @param handout rendered bytes and metadata
     */
    void save(RenderedHandout handout);
}
