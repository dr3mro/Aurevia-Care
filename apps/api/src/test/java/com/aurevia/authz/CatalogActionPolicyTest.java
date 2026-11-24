package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G0-03 catalog review is admin-only in G0 (ADM-01/IAM-01). */
class CatalogActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    @Test
    void tenantAdminMayReadCatalogs() {
        assertTrue(policy.allows("CATALOG_READ", "tenant_admin"));
    }

    @Test
    void otherRolesDenyCatalogs() {
        assertFalse(policy.allows("CATALOG_READ", "reception"));
        assertFalse(policy.allows("CATALOG_READ", "privacy_auditor"));
    }

    @Test
    void unknownActionDenied() {
        assertFalse(policy.allows("CATALOG_DELETE", "tenant_admin"));
    }
}
