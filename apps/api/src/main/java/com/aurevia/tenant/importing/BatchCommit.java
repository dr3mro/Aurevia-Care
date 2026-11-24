package com.aurevia.tenant.importing;

/**
 * The result of reconciling one controlled batch during an OPS-08 import.
 *
 * @param sequence batch sequence number this result refers to
 * @param outcome  whether the batch was applied, skipped or rejected
 * @param reason   short machine-readable reason; empty when the batch was applied
 */
public record BatchCommit(int sequence, BatchOutcome outcome, String reason) {
}
