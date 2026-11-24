package com.aurevia.patient.identity;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for patient identity proofing (IAM-05).
 *
 * <p>Site-verified enrollment and invitation issuance are clerical actions;
 * re-verification and unlinking a verified relationship are privacy-sensitive
 * and reserved for administrative and privacy roles. Patient self-service is
 * authorised separately by an active verified account, not by this policy.</p>
 */
@Component
public class IdentityActionPolicy {

    /** Site-verified enrollment of a patient account. */
    public static final String ACCOUNT_ENROLL = "PATIENT_ACCOUNT_ENROLL";
    /** Issuance of a single-use invitation for a verified chart relationship. */
    public static final String INVITATION_ISSUE = "PATIENT_INVITATION_ISSUE";
    /** Re-verification after lost access or a disputed identity. */
    public static final String LINK_REVERIFY = "PATIENT_LINK_REVERIFY";
    /** Unlinking an account from a chart. */
    public static final String LINK_UNLINK = "PATIENT_LINK_UNLINK";
    /** Reading another account's access or links. */
    public static final String LINK_READ = "PATIENT_LINK_READ";

    private static final Set<String> CLERICAL = Set.of(ACCOUNT_ENROLL, INVITATION_ISSUE);
    private static final Set<String> PRIVILEGED = Set.of(LINK_REVERIFY, LINK_UNLINK, LINK_READ);

    /**
     * Decide whether a staff role may perform a protected identity action.
     *
     * @param action action key such as {@code PATIENT_ACCOUNT_ENROLL}
     * @param role   role key assigned in the active organization
     * @return {@code true} when the role is allowed the action
     */
    public boolean allows(String action, String role) {
        if ("tenant_admin".equals(role)) {
            return CLERICAL.contains(action) || PRIVILEGED.contains(action);
        }
        if ("privacy_auditor".equals(role)) {
            return PRIVILEGED.contains(action);
        }
        if ("reception".equals(role)) {
            return CLERICAL.contains(action);
        }
        return false;
    }
}
