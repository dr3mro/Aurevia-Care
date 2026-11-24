package com.aurevia.events;

import org.springframework.stereotype.Component;

import com.aurevia.events.DurableEventContract.AuditAction;

/**
 * Bounded action matrix for ARC-05 delivery.
 *
 * <p>Service principals publish and consume events; operators drive transmission
 * state and retries; replay and dead-letter review require an administrative
 * role; linking external data to a chart requires a clinician or integration
 * administrator. The matrix is paired with a fresh active-membership and role
 * check on every protected request, so a revoked credential loses access on the
 * next request without a token or session expiry.</p>
 */
@Component
public class DeliveryActions {

    /**
     * Decide whether a role may perform an action in its active organization.
     *
     * @param action action key
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(AuditAction action, String role) {
        return switch (action) {
            case PUBLISH, CONSUME -> serviceRole(role);
            case MARK_SENT, MARK_DELIVERED, MARK_FAILED, RETRY_SCHEDULED, DEAD_LETTER -> deliveryRole(role);
            case REVIEW, REPLAY -> adminRole(role);
            case LINK_EXTERNAL -> "clinician".equals(role) || "integration_admin".equals(role);
            case AUDIT_READ -> "tenant_admin".equals(role) || "privacy_auditor".equals(role);
        };
    }

    private boolean serviceRole(String role) {
        return "worker".equals(role) || "integration_admin".equals(role);
    }

    private boolean deliveryRole(String role) {
        return serviceRole(role) || "operator".equals(role);
    }

    private boolean adminRole(String role) {
        return "integration_admin".equals(role) || "tenant_admin".equals(role);
    }
}
