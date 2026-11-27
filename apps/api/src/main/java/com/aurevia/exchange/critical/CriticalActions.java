package com.aurevia.exchange.critical;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for critical-result commands.
 *
 * <p>Lab verification, clinical acknowledgment and administrative release are
 * deliberately separated: a verifier cannot acknowledge, an administrator
 * cannot acknowledge clinical receipt, and reception has no critical-result
 * action. The matrix is paired with a fresh membership and role check on every
 * protected request in the domain service.</p>
 */
@Component
public class CriticalActions {

    /** Register a critical finding from the source system. */
    public static final String REGISTER = "CRITICAL_REGISTER";
    /** Verify the finding before any alert is routed. */
    public static final String VERIFY = "CRITICAL_VERIFY";
    /** Assign the responsible owner, deadline and escalation policy. */
    public static final String ASSIGN = "CRITICAL_ASSIGN";
    /** Record an escalation notification attempt. */
    public static final String ESCALATE = "CRITICAL_ESCALATE";
    /** Record a verbatim read-back. */
    public static final String READ_BACK = "CRITICAL_READ_BACK";
    /** Acknowledge receipt as an authorized clinician. */
    public static final String ACKNOWLEDGE = "CRITICAL_ACKNOWLEDGE";
    /** Record clinical follow-up. */
    public static final String FOLLOW_UP = "CRITICAL_FOLLOW_UP";
    /** Record a patient release, which is not a clinical acknowledgment. */
    public static final String RELEASE = "CRITICAL_RELEASE";
    /** Read the alert, its trail or the operational report. */
    public static final String READ = "CRITICAL_READ";

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case REGISTER, VERIFY -> "lab_verifier".equals(role);
            case ASSIGN, ESCALATE -> "clinician".equals(role) || "tenant_admin".equals(role);
            case READ_BACK, ACKNOWLEDGE, FOLLOW_UP -> "clinician".equals(role);
            case RELEASE -> "tenant_admin".equals(role);
            case READ -> "clinician".equals(role) || "tenant_admin".equals(role)
                    || "privacy_auditor".equals(role);
            default -> false;
        };
    }
}
