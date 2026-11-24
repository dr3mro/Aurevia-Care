package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G0-03b: audit review is bounded to tenant admins and privacy auditors (IAM-01/OPS-02). */
class AuditActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    @Test
    void tenantAdminMayReadAudit() {
        assertTrue(policy.allows("AUDIT_READ", "tenant_admin"));
    }

    @Test
    void privacyAuditorMayOnlyReadAudit() {
        assertTrue(policy.allows("AUDIT_READ", "privacy_auditor"));
        assertFalse(policy.allows("CONFIG_READ", "privacy_auditor"));
        assertFalse(policy.allows("FACILITY_WRITE", "privacy_auditor"));
    }

    @Test
    void otherRolesDenyAudit() {
        assertFalse(policy.allows("AUDIT_READ", "reception"));
    }
}
