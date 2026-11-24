package com.aurevia.printing;

import java.time.Clock;
import java.util.Optional;

import com.aurevia.api.ApiException;
import com.aurevia.documents.ArtifactInputs;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.printing.HandoutContract.HandoutRequest;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.printing.HandoutContract.RenderedHandout;

/**
 * Render a signed or placed clinical source as an immutable patient handout PDF (CLN-07, RX-02).
 *
 * <p>The service is the single printing entry point for the five supported
 * kinds. It validates the tenant, language and revision selectors, applies the
 * print policy, reuses an existing rendering of an unchanged source and only
 * then renders and stores a new version. Permissions and audit for the HTTP
 * surface are a separate delivery step, so no caller-supplied role widens the
 * decision here.</p>
 */
public final class HandoutService {

    private final HandoutRenderer renderer;
    private final HandoutArchive archive;
    private final Clock clock;

    /**
     * Create the service with injected collaborators.
     *
     * @param renderer deterministic handout renderer
     * @param archive  tenant-scoped handout registry
     * @param clock    server clock for the render instant
     */
    public HandoutService(HandoutRenderer renderer, HandoutArchive archive, Clock clock) {
        this.renderer = renderer;
        this.archive = archive;
        this.clock = clock;
    }

    /**
     * Build a patient handout for one exact source revision.
     *
     * @param request freshly derived caller, language and source revision
     * @return the original or newly rendered immutable handout
     * @throws ApiException on a missing actor, a foreign tenant, an invalid
     *                      selector or a source that is not printable
     */
    public RenderedHandout handout(HandoutRequest request) {
        Actor actor = request.actor();
        if (actor == null) {
            throw new ApiException(403, "FORBIDDEN", "missing print permission");
        }
        String language = ArtifactInputs.language(request.language()).wire();
        HandoutSource source = request.source();
        if (!source.organizationId().equals(actor.organizationId())) {
            throw new ApiException(403, "WRONG_TENANT", "the source belongs to another organization");
        }
        String sourceId = ArtifactInputs.key(source.sourceId(), "sourceId");
        int revision = ArtifactInputs.positiveVersion(source.revisionVersion(), "revisionVersion");
        String checksum = ArtifactInputs.checksum(source.contentChecksum());
        HandoutPolicy.requirePrintable(source);
        HandoutSource validated = new HandoutSource(source.organizationId(), source.kind(), sourceId,
                revision, checksum, source.state(), source.attested(), source.printChannel(),
                source.patientId(), source.patientName(), source.clinician(), source.facility(),
                source.issueTime(), source.sourceSystem(), source.contentLines());

        Optional<RenderedHandout> existing = archive.find(validated.organizationId(), validated.kind(),
                sourceId, revision, checksum, language);
        if (existing.isPresent()) {
            return existing.get();
        }
        int version = archive.versionFor(validated.organizationId(), validated.kind(), sourceId,
                revision, checksum);
        RenderedHandout rendered = renderer.render(validated, language, version, actor.subject(),
                clock.instant());
        archive.save(rendered);
        return rendered;
    }
}
