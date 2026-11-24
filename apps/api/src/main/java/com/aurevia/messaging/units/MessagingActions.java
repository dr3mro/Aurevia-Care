package com.aurevia.messaging.units;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for the MSG-01 / ARC-07 messaging foundation.
 *
 * <p>Patient self-service actions are deliberately separated from staff
 * administration: a tenant administrator may review the directory and revoke a
 * connection for safety, but cannot connect a patient or act as that patient.
 * The matrix is paired with a fresh membership and verified-chart check on every
 * protected request in the domain service.</p>
 */
@Component
public class MessagingActions {

    /** List the care units a verified patient is eligible to contact. */
    public static final String UNIT_LIST = "UNIT_LIST";
    /** Create a verified, connection-scoped patient-unit relationship. */
    public static final String CONNECTION_CREATE = "CONNECTION_CREATE";
    /** List the connections visible to the caller. */
    public static final String CONNECTION_LIST = "CONNECTION_LIST";
    /** Revoke a connection so it grants no future access. */
    public static final String CONNECTION_REVOKE = "CONNECTION_REVOKE";
    /** Use an existing connection for a protected messaging action. */
    public static final String CONNECTION_USE = "CONNECTION_USE";

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case UNIT_LIST, CONNECTION_LIST -> "patient".equals(role)
                    || "tenant_admin".equals(role) || "privacy_auditor".equals(role);
            case CONNECTION_CREATE, CONNECTION_USE -> "patient".equals(role);
            case CONNECTION_REVOKE -> "patient".equals(role) || "tenant_admin".equals(role);
            default -> false;
        };
    }
}
