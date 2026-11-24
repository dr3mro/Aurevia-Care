package com.aurevia.staffmsg.governance;

import org.springframework.stereotype.Component;

import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.StaffAction;

/**
 * Bounded role matrix for staff-message governance (MSG-07, ARC-02).
 *
 * <p>Retention, legal holds, exports and audit review belong to the privacy and
 * administrative roles; content and folder actions belong to the staff roles
 * that participate in conversations. The matrix is paired with a fresh
 * membership and active-role check on every protected request, so a role key
 * alone never grants access.</p>
 */
@Component
public class StaffMessageGovernanceActions {

    /**
     * Decide whether a role may perform an action in its active organization.
     *
     * @param action action key
     * @param role   role key currently in force for the subject
     * @return {@code true} only when the role is permitted
     */
    public boolean allows(StaffAction action, String role) {
        return switch (action) {
            case HOLD_PLACE, HOLD_RELEASE, RETENTION_APPLY, EXPORT, AUDIT_READ -> governanceRole(role);
            case OPEN, SEND, FORWARD, ATTACH, DOWNLOAD, FOLDER_MOVE, DELETE -> staffRole(role);
        };
    }

    /**
     * Records-officer and administrative roles own governance commands.
     *
     * @param role role key
     * @return {@code true} for a records-officer role
     */
    private boolean governanceRole(String role) {
        return "privacy_auditor".equals(role) || "tenant_admin".equals(role);
    }

    /**
     * Clinical and front-office staff may act on conversations they participate in.
     *
     * @param role role key
     * @return {@code true} for a staff conversation role
     */
    private boolean staffRole(String role) {
        return "clinician".equals(role) || "unit_staff".equals(role) || "reception".equals(role);
    }
}
