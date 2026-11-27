package com.aurevia.radflow.report;

import java.util.Set;

import com.aurevia.radiology.Interpretation;
import com.aurevia.radiology.RadRules;

/**
 * Pure guard rules for report delivery and addendum acknowledgment.
 *
 * <p>Roles and identity are supplied by the caller from the active tenant
 * membership and authenticated context, and are re-derived on every protected
 * request. The signing check is deliberately repeated at the delivery boundary:
 * an interpretation carries a named author, but an unsigned or unattested draft
 * must never be delivered to a referrer.</p>
 */
public interface ReportRules {

    /**
     * Whether the subject may sign and deliver an interpretation or addendum.
     *
     * <p>This delegates to the radiology signing rule so the delivery kernel cannot
     * grant signing rights to a role that the imaging workflow reserves for
     * credentialed interpreters.</p>
     *
     * @param roles roles active in the current organization
     * @return {@code true} only for an interpreter with imaging privileges
     */
    static boolean mayDeliver(Set<String> roles) {
        return RadRules.mayInterpret(roles);
    }

    /**
     * Whether an interpretation is complete enough to be delivered as a signed report.
     *
     * @param interpretation interpretation to check, possibly {@code null}
     * @return {@code true} only with a named signer, an attestation and a signing time
     */
    static boolean isSigned(Interpretation interpretation) {
        // A named signer is already enforced by Interpretation itself; only the
        // attestation and the signing time can be absent at the delivery boundary.
        return interpretation != null
                && interpretation.attestation() != null && !interpretation.attestation().isBlank()
                && interpretation.signedAt() != null;
    }

    /**
     * Whether an authenticated subject is the verified referring clinician.
     *
     * <p>Acknowledgment is bound to the receiving clinician's identity rather than
     * to a role: the report was addressed to that subject and only that subject may
     * close its addenda.</p>
     *
     * @param subject  authenticated subject from the tenant context
     * @param referrer verified referring clinician recorded on the report
     * @return {@code true} when the subject is the verified referrer
     */
    static boolean isReferrer(String subject, String referrer) {
        return referrer.equals(subject);
    }
}
