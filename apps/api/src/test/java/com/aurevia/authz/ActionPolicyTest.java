package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    @Test
    void tenantAdminMayReadAndWriteFacilities() {
        assertTrue(policy.allows("FACILITY_READ", "tenant_admin"));
        assertTrue(policy.allows("FACILITY_WRITE", "tenant_admin"));
    }

    @Test
    void otherRolesAreDenied() {
        assertFalse(policy.allows("FACILITY_READ", "reception"));
    }

    @Test
    void otherActionsAreDenied() {
        assertFalse(policy.allows("FACILITY_DELETE", "tenant_admin"));
        assertFalse(policy.allows("FACILITY_READ", "unknown"));
    }

    @Test
    void roleHistoryReadIsTenantAdminOnly() {
        assertTrue(policy.allows(ActionPolicy.ROLE_HISTORY_READ, "tenant_admin"));
        assertFalse(policy.allows(ActionPolicy.ROLE_HISTORY_READ, "privacy_auditor"));
        assertFalse(policy.allows(ActionPolicy.ROLE_HISTORY_READ, "reception"));
        assertFalse(policy.allows(ActionPolicy.ROLE_HISTORY_READ, "clinician"));
        assertTrue(policy.allows(ActionPolicy.APPOINTMENT_START, "clinician"));
    }
}
