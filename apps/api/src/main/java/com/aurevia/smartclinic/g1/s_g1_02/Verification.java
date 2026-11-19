package com.aurevia.smartclinic.g1.s_g1_02;

import java.time.Instant;
import java.util.List;

/**
 * Immutable proof that a staff member confirmed a patient's identity before a
 * check-in (CLN-02, IAM-03).
 *
 * <p>Only the identifier categories used are retained, never their values, so
 * the record carries no more patient data than the audit purpose requires. The
 * alternate path names the site-approved workflow and records why the standard
 * two-identifier check was unavailable.</p>
 *
 * @param id              opaque verification identifier
 * @param appointmentId   appointment this evidence belongs to
 * @param patientId       patient whose identity was confirmed
 * @param facilityCode    facility where the check-in occurred
 * @param method          {@code two_identifiers} or {@code documented_alternate}
 * @param identifiers     distinct identifier categories used
 * @param alternateCode   named workflow code for the alternate path, or {@code null}
 * @param alternateReason recorded reason for the alternate path, or {@code null}
 * @param verifier        authenticated subject that verified, from the transaction actor
 * @param verifiedAt      instant the evidence was committed
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
