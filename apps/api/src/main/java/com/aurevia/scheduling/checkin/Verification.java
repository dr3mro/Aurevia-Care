package com.aurevia.scheduling.checkin;

import java.time.Instant;
import java.util.List;

/**
 * Immutable proof that a staff member confirmed a patient's identity before a
 * check-in.
 *
 * <p>Only the identifier categories used are retained, never their values, so
 * the record carries no more patient data than the audit purpose requires. The
 * alternate path names the site-approved workflow and records why the standard
 * two-identifier check was unavailable.</p>
 *
 * @return this record
 */
public record Verification(String id, String appointmentId, String patientId, String facilityCode,
                           String method, List<String> identifiers, String alternateCode,
                           String alternateReason, String verifier, Instant verifiedAt) {

    /**
     * Copy the caller's identifier list so the evidence cannot be changed through it.
     *
     * @throws NullPointerException when identifiers is null
     */
    public Verification {
        identifiers = identifiers == null ? List.of() : List.copyOf(
                identifiers.stream().filter(java.util.Objects::nonNull).toList());
    }

    /**
     * Return the immutable identifier categories used.
     *
     * @return the identifier categories
     */
    @Override
    public List<String> identifiers() {
        return List.copyOf(identifiers);
    }
}
