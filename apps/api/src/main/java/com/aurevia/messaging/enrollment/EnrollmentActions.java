package com.aurevia.messaging.enrollment;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for the PAT-03 slice.
 *
 * <p>Enrollment administration, privacy-officer dispute handling and patient
 * self-service are separated: a clinician has no account-administration action,
 * and a support agent may handle accessibility cases but may not resolve an
 * identity dispute or read the audit trail. The matrix is paired with a fresh
 * membership and role check on every protected request.</p>
 */
@Component
public class EnrollmentActions {

    /** Patient self-service role. */
    public static final String PATIENT = "patient";
    /** Registration staff role. */
    public static final String RECEPTION = "reception";
    /** Privacy officer role. */
    public static final String PRIVACY_AUDITOR = "privacy_auditor";
    /** Tenant administrator role. */
    public static final String TENANT_ADMIN = "tenant_admin";
    /** Support agent role. */
    public static final String SUPPORT_AGENT = "support_agent";

    /** Issue a single-use, expiring enrollment invitation. */
    public static final String INVITE_ISSUE = "PATIENT_INVITE_ISSUE";
    /** Redeem an enrollment invitation. */
    public static final String INVITE_REDEEM = "PATIENT_INVITE_REDEEM";
    /** Issue a single-use recovery code. */
    public static final String RECOVERY_ISSUE = "PATIENT_RECOVERY_ISSUE";
    /** Redeem a recovery code with re-verification. */
    public static final String RECOVERY_REDEEM = "PATIENT_RECOVERY_REDEEM";
    /** Clear a lockout after review. */
    public static final String ACCOUNT_UNLOCK = "PATIENT_ACCOUNT_UNLOCK";
    /** Apply a sensitive change with renewed authentication. */
    public static final String SENSITIVE_CHANGE = "PATIENT_SENSITIVE_CHANGE";
    /** Edit a patient's recovery contact as staff. */
    public static final String CONTACT_EDIT_STAFF = "PATIENT_CONTACT_EDIT_STAFF";
    /** Open a support case. */
    public static final String SUPPORT_OPEN = "PATIENT_SUPPORT_OPEN";
    /** Assign a support case to a named owner. */
    public static final String SUPPORT_ASSIGN = "PATIENT_SUPPORT_ASSIGN";
    /** Resolve a support case. */
    public static final String SUPPORT_RESOLVE = "PATIENT_SUPPORT_RESOLVE";
    /** Read a support case list or account view. */
    public static final String SUPPORT_READ = "PATIENT_SUPPORT_READ";
    /** Read the append-only access audit. */
    public static final String AUDIT_READ = "PATIENT_AUDIT_READ";
    /** Check whether a verified chart link currently permits access. */
    public static final String ACCESS_CHART = "PATIENT_ACCESS_CHART";

    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            Map.entry(INVITE_ISSUE, Set.of(RECEPTION, TENANT_ADMIN)),
            Map.entry(INVITE_REDEEM, Set.of(PATIENT)),
            Map.entry(RECOVERY_ISSUE, Set.of(PRIVACY_AUDITOR, TENANT_ADMIN)),
            Map.entry(RECOVERY_REDEEM, Set.of(PATIENT)),
            Map.entry(ACCOUNT_UNLOCK, Set.of(PRIVACY_AUDITOR, TENANT_ADMIN)),
            Map.entry(SENSITIVE_CHANGE, Set.of(PATIENT)),
            Map.entry(CONTACT_EDIT_STAFF, Set.of(RECEPTION, TENANT_ADMIN)),
            Map.entry(SUPPORT_OPEN, Set.of(PATIENT, RECEPTION, TENANT_ADMIN)),
            Map.entry(SUPPORT_ASSIGN, Set.of(SUPPORT_AGENT, PRIVACY_AUDITOR, TENANT_ADMIN)),
            Map.entry(SUPPORT_RESOLVE, Set.of(PRIVACY_AUDITOR, SUPPORT_AGENT)),
            Map.entry(SUPPORT_READ, Set.of(SUPPORT_AGENT, PRIVACY_AUDITOR, TENANT_ADMIN)),
            Map.entry(AUDIT_READ, Set.of(PRIVACY_AUDITOR, TENANT_ADMIN)),
            Map.entry(ACCESS_CHART, Set.of(PATIENT)));

    /**
     * Decide whether any of a caller's roles may perform an action.
     *
     * @param action action key from this class
     * @param roles  active role assignments in the current organization
     * @return {@code true} when at least one role is permitted
     */
    public boolean allows(String action, Set<String> roles) {
        Set<String> permitted = ALLOWED.get(action);
        if (permitted == null) {
            return false;
        }
        for (String role : roles) {
            if (permitted.contains(role)) {
                return true;
            }
        }
        return false;
    }
}
