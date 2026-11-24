package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G0-03: configuration actions are a bounded, tenant-admin-only subset (ADM-01/IAM-01). */
class ConfigurationActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    @Test
    void tenantAdminAllowsEveryConfigurationAction() {
        assertTrue(policy.allows("CONFIG_CREATE", "tenant_admin"));
        assertTrue(policy.allows("CONFIG_APPROVE", "tenant_admin"));
        assertTrue(policy.allows("CONFIG_READ", "tenant_admin"));
        assertTrue(policy.allows("CONFIG_HISTORY", "tenant_admin"));
    }

    @Test
    void otherRolesDenyEveryConfigurationAction() {
        assertFalse(policy.allows("CONFIG_CREATE", "reception"));
        assertFalse(policy.allows("CONFIG_APPROVE", "reception"));
        assertFalse(policy.allows("CONFIG_READ", "reception"));
        assertFalse(policy.allows("CONFIG_HISTORY", "reception"));
    }

    @Test
    void unknownActionStaysDenied() {
        assertFalse(policy.allows("CONFIG_DELETE", "tenant_admin"));
    }
}
