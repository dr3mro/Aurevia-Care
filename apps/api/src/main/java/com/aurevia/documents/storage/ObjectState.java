package com.aurevia.documents.storage;

/**
 * Storage lifecycle of one object (ARC-06).
 *
 * <p>An object enters private quarantine on upload and is only promoted after
 * an independent clean verdict. Quarantined bytes are never returned by a
 * verified read.</p>
 */
public enum ObjectState {
    /** Uploaded and held privately, not yet cleared for reads. */
    QUARANTINED,
    /** Promoted after a clean scan and readable at its verified version. */
    PROMOTED
}
