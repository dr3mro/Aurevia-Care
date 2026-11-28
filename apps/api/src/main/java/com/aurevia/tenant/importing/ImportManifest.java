package com.aurevia.tenant.importing;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Versioned onboarding import manifest.
 *
 * <p>The envelope fixes the contract version and version number before any
 * content is interpreted. The payload carries the source records, the
 * deterministic field mapping, provenance and the controlled batch plan. All
 * collections are frozen so a parsed manifest cannot change during evaluation.</p>
 *
 * @param schemaVersion   must be {@code 1.0}
 * @param requirement must be the onboarding requirement identifier
 * @param manifestVersion positive version number named by the sign-off
 * @param source          named source system or dataset
 * @param syntheticOnly   must be {@code true} for this repository
 * @param records         source records
 * @param mapping         deterministic field mapping
 * @param provenance      import provenance declaration
 * @param batches         controlled batch declarations
 */
public record ImportManifest(
        @JsonProperty("schema_version") String schemaVersion,
        String requirement,
        @JsonProperty("manifest_version") Integer manifestVersion,
        String source,
        @JsonProperty("synthetic_only") Boolean syntheticOnly,
        List<ImportRecord> records,
        FieldMapping mapping,
        ImportProvenance provenance,
        List<ImportBatch> batches) {

    /**
     * Freeze the record and batch lists.
     *
     * @throws NullPointerException when records or batches is null
     */
    public ImportManifest {
        records = List.copyOf(records);
        batches = List.copyOf(batches);
    }

    /**
     * Return the immutable source-record list.
     *
     * @return source records
     */
    @Override
    public List<ImportRecord> records() {
        return List.copyOf(records);
    }

    /**
     * Return the immutable batch list.
     *
     * @return controlled batch declarations
     */
    @Override
    public List<ImportBatch> batches() {
        return List.copyOf(batches);
    }
}
