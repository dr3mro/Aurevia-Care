package com.aurevia.tenant.importing;

/**
 * Outcome of reconciling and applying one controlled onboarding batch (OPS-08).
 *
 * <p>Only {@code COMMITTED} and {@code ALREADY_COMMITTED} let an import
 * continue; every other outcome stops the batch sequence and leaves the failing
 * batch unapplied so a corrected manifest can resume safely.</p>
 */
public enum BatchOutcome {
    /** The batch passed reconciliation and was applied for the first time. */
    COMMITTED,
    /** The batch had already been applied by an earlier run and was skipped. */
    ALREADY_COMMITTED,
    /** The declared batch plan is malformed or does not cover known records. */
    INVALID_BATCH,
    /** The declared record count does not match the batch contents. */
    COUNT_MISMATCH,
    /** The declared batch digest does not match the recomputed digest. */
    HASH_MISMATCH
}
