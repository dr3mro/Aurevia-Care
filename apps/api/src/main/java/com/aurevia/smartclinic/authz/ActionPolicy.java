package com.aurevia.smartclinic.authz;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Bounded action policy for the G0 facility slice.
 *
 * <p>The policy is intentionally small for G0: only the tenant administrator
 * role may read or write facilities. Broader role matrices belong to later
 * identity stories.</p>
 */
@Component
public class ActionPolicy {

    private static final Set<String> ADMIN_ACTIONS = Set.of(
            "FACILITY_READ", "FACILITY_WRITE",
            "STAFF_INVITE", "STAFF_DEACTIVATE", "STAFF_READ", "STAFF_HISTORY",
            "CONFIG_CREATE", "CONFIG_APPROVE", "CONFIG_READ", "CONFIG_HISTORY", "AUDIT_READ",
            "CATALOG_READ");

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key such as {@code FACILITY_WRITE}
     * @param role   role key assigned in the active organization
     * @return {@code true} when the action is allowed
     */
    public boolean allows(String action, String role) {
        if ("tenant_admin".equals(role)) {
            return ADMIN_ACTIONS.contains(action);
        }
        // A privacy auditor may review the append-only audit trail, but cannot
        // administer configuration or staff. Their clinical access is separate.
        return "privacy_auditor".equals(role) && "AUDIT_READ".equals(action);
    }
}
