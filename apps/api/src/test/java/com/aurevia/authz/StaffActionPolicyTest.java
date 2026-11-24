package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G0-02: staff lifecycle actions are a bounded, tenant-admin-only subset (IAM-01/IAM-02). */
class StaffActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    @Test
    void tenantAdminAllowsEveryStaffAction() {
        assertTrue(policy.allows("STAFF_INVITE", "tenant_admin"));
        assertTrue(policy.allows("STAFF_DEACTIVATE", "tenant_admin"));
        assertTrue(policy.allows("STAFF_READ", "tenant_admin"));
        assertTrue(policy.allows("STAFF_HISTORY", "tenant_admin"));
    }

    @Test
    void otherRolesDenyEveryStaffAction() {
        assertFalse(policy.allows("STAFF_INVITE", "reception"));
        assertFalse(policy.allows("STAFF_DEACTIVATE", "reception"));
        assertFalse(policy.allows("STAFF_READ", "reception"));
        assertFalse(policy.allows("STAFF_HISTORY", "reception"));
    }

    @Test
    void unknownActionStaysDenied() {
        assertFalse(policy.allows("STAFF_DELETE", "tenant_admin"));
    }
}
