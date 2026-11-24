package com.aurevia.catalog.dictionary;

/**
 * Explicit requiredness of a field in a clinical dictionary (CLN-03, CLN-04).
 *
 * <p>Requiredness is a coded value rather than a boolean so a field can be
 * explicitly unknown while governance decides its final state.</p>
 */
public enum Requiredness {
    /** The field must be captured. */
    REQUIRED,
    /** The field may be omitted. */
    OPTIONAL,
    /** Governance has not decided, or the state is unknown. */
    UNKNOWN
}
