package com.aurevia.tenant.importing;

import java.util.List;

/**
 * Result of one controlled-batch commit attempt.
 *
 * <p>The returned progress is the resumable checkpoint: a later call passes it
 * back so already-applied batches are skipped. Committing never changes the
 * source of truth on its own; only a subsequent authorized sign-off finalizes
 * the import.</p>
 *
 * @param progress the resumable checkpoint after this attempt
 * @param results  one result per attempted batch, in sequence order
 * @param stopped  true when a batch was rejected and later batches were skipped
 * @param reason   short machine-readable reason for a report-level refusal; empty on success
 */
public record CommitReport(ImportProgress progress, List<BatchCommit> results, boolean stopped,
        String reason) {

    /**
     * Freeze the result list.
     *
     * @throws NullPointerException when results is null
     */
    public CommitReport {
        results = List.copyOf(results);
    }

    /**
     * Return the immutable result list.
     *
     * @return one result per attempted batch
     */
    @Override
    public List<BatchCommit> results() {
        return List.copyOf(results);
    }
}
