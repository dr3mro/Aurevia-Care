package com.aurevia.tenant.importing;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Named authorization to finalize a controlled onboarding import.
 *
 * <p>The sign-off names the approver, an authorized role and an affirmative
 * decision, and binds itself to this manifest version, every batch sequence and
 * the content digest the importer recomputes. Changing content, version or scope
 * invalidates a previously valid sign-off.</p>
 *
 * @param approver        non-blank name of the approving person or role holder
 * @param role            an authorized approver role
 * @param decision        must be {@code approved}
 * @param contentSha256   digest of the manifest version and ordered batch digests
 * @param manifestVersion manifest version this sign-off covers
 * @param batches         batch sequences this sign-off covers
 * @param approvedAt      non-blank approval timestamp
 */
public record ImportSignOff(
        String approver,
        String role,
        String decision,
        @JsonProperty("content_sha256") String contentSha256,
        @JsonProperty("manifest_version") Integer manifestVersion,
        List<Integer> batches,
        @JsonProperty("approved_at") String approvedAt) {

    /**
     * Freeze the batch list.
     *
     * @throws NullPointerException when batches is null
     */
    public ImportSignOff {
        batches = List.copyOf(batches);
    }

    /**
     * Return the immutable set of covered batch sequences.
     *
     * @return batch sequences covered by this sign-off
     */
    @Override
    public List<Integer> batches() {
        return List.copyOf(batches);
    }
}
