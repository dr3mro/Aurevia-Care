package com.aurevia.tenant.importing;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One controlled batch declared by an onboarding manifest.
 *
 * <p>The dry run uses only the declared batch count; sequence,
 * coverage and hash reconciliation are performed by the controlled-commit
 * service. The shape is declared here so manifests stay version-stable as
 * batches move through the pipeline.</p>
 *
 * @param sequence      contiguous batch sequence number
 * @param recordIds     sorted, unique source ids in this batch
 * @param expectedCount declared number of records in this batch
 * @param sha256        declared digest of the canonical batch bytes
 */
public record ImportBatch(
        Integer sequence,
        @JsonProperty("record_ids") List<String> recordIds,
        @JsonProperty("expected_count") Integer expectedCount,
        String sha256) {

    /**
     * Freeze the id list.
     *
     * @throws NullPointerException when recordIds is null
     */
    public ImportBatch {
        recordIds = List.copyOf(recordIds);
    }

    /**
     * Return the immutable source-id list.
     *
     * @return source ids in this batch
     */
    @Override
    public List<String> recordIds() {
        return List.copyOf(recordIds);
    }
}
