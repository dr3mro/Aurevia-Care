package com.aurevia.catalog;

import java.time.Instant;

import com.aurevia.api.ApiException;

/**
 * One published version of the market-approved medication catalog for the active
 * tenant (RX-01, ADM-01).
 *
 * <p>A version is immutable once published. A prescription draft binds the
 * version it was built from, so a later publication never rewrites the catalog
 * identity of an existing draft. Approval provenance names the governance
 * decision that made this version effective in the market.</p>
 *
 * @return the validated published catalog version
 */
public record MedicationCatalogVersion(int version, String market, Instant publishedAt, String provenance) {

    /**
     * Baseline for tenants whose catalog predates version publication (RX-01).
     *
     * <p>The original synthetic catalog was market-approved as version one; this
     * constant keeps those tenants readable until an explicit version is
     * published. It is a value, not mutable state.</p>
     */
    public static final MedicationCatalogVersion BASELINE =
            new MedicationCatalogVersion(1, "synthetic-market", Instant.EPOCH, "synthetic-baseline");

    /**
     * Validate the published version, its market and its approval provenance.
     *
     * @throws ApiException when the version is not positive or a required field is missing
     */
    public MedicationCatalogVersion {
        if (version < 1) {
            throw new ApiException(422, "INVALID_CATALOG_VERSION", "catalog version must be positive");
        }
        if (market == null || market.isBlank()) {
            throw new ApiException(422, "INVALID_CATALOG_VERSION", "catalog market is required");
        }
        if (publishedAt == null) {
            throw new ApiException(422, "INVALID_CATALOG_VERSION", "catalog publication instant is required");
        }
        if (provenance == null || provenance.isBlank()) {
            throw new ApiException(422, "INVALID_CATALOG_VERSION", "catalog approval provenance is required");
        }
    }
}
