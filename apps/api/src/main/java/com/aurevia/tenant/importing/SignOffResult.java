package com.aurevia.tenant.importing;

/**
 * Outcome of an OPS-08 sign-off attempt.
 *
 * @param accepted true when an authorized sign-off finalized the import
 * @param reason   short machine-readable reason; empty when accepted
 * @param progress the checkpoint after this attempt (finalized only when accepted)
 */
public record SignOffResult(boolean accepted, String reason, ImportProgress progress) {
}
