package com.aurevia.chart.record;

/**
 * Verification of a chart entry's assertion (CLN-03 "track verification").
 *
 * <p>Verification is orthogonal to {@link com.aurevia.chart.record.PresenceState}: a clinician may
 * verify a present finding, an explicitly absent one or an unknown one. A
 * verified entry is immutable for its version; changing content creates a new
 * version that starts unverified.</p>
 */
public enum VerificationState {
    /** Not yet verified by an authorized clinician. */
    UNVERIFIED,
    /** Verified; {@code verifiedBy} and {@code verifiedAt} are populated. */
    VERIFIED
}
