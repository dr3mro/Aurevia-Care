package com.aurevia.prescription;

import com.aurevia.api.ApiException;

/**
 * The catalog version a prescription draft was built from (RX-01).
 *
 * <p>The version is stamped once when a medication is selected and never
 * rewritten by a later catalog publication, so an old draft keeps the version
 * that was effective when it was built. A draft that has not selected a
 * medication yet carries no version.</p>
 *
 * @return the validated draft-to-catalog binding
 */
public record DraftCatalogVersion(String draftId, Integer catalogVersion) {

    /**
     * Validate the draft identifier.
     *
     * @throws ApiException when the draft identifier is missing
     */
    public DraftCatalogVersion {
        if (draftId == null || draftId.isBlank()) {
            throw new ApiException(422, "INVALID_DRAFT_CATALOG_VERSION", "a draft identifier is required");
        }
    }
}
