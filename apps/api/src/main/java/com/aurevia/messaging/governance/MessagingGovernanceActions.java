package com.aurevia.messaging.governance;

import org.springframework.stereotype.Component;

import com.aurevia.messaging.governance.MessagingAuditContract.AuditAction;

/**
 * Bounded action matrix for MSG-07 governance.
 *
 * <p>Audit review, retention, legal holds and exports belong to privacy and
 * administrative roles. Filing, correcting and linking clinical material to a
 * chart require a clinician. Patients may read their own conversation facts and
 * route a copy request, but never see another patient's material. The matrix is
 * paired with a fresh membership and role check on every protected request.</p>
 */
@Component
public class MessagingGovernanceActions {

    /**
     * Decide whether a role may perform an action in its active organization.
     *
     * @param action action key
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(AuditAction action, String role) {
        return switch (action) {
            case AUDIT_READ, RETENTION_APPLY, EXPORT, HOLD_PLACE, HOLD_RELEASE -> governanceRole(role);
            case CHART_LINK, FILE, CORRECT -> "clinician".equals(role);
            case ASSIGN, RESTRICT -> "tenant_admin".equals(role);
            case SEND, OPEN, VIEW, DOWNLOAD, ACKNOWLEDGE -> conversationRole(role);
            case COPY_REQUEST -> "patient".equals(role) || "tenant_admin".equals(role)
                    || "privacy_auditor".equals(role);
        };
    }

    private boolean governanceRole(String role) {
        return "tenant_admin".equals(role) || "privacy_auditor".equals(role);
    }

    private boolean conversationRole(String role) {
        return "patient".equals(role) || "clinician".equals(role) || "unit_staff".equals(role);
    }
}
