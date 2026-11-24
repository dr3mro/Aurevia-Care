package com.aurevia.authz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G0-05: patient actions are bounded to reception and clinical roles (IAM-01/IAM-03). */
class PatientActionPolicyTest {

    private final ActionPolicy policy = new ActionPolicy();

    @Test
    void receptionMayRegisterReadAndSearch() {
        assertTrue(policy.allows("PATIENT_REGISTER", "reception"));
        assertTrue(policy.allows("PATIENT_READ", "reception"));
        assertTrue(policy.allows("PATIENT_SEARCH", "reception"));
    }

    @Test
    void receptionCannotAdministerTenant() {
        assertFalse(policy.allows("FACILITY_WRITE", "reception"));
        assertFalse(policy.allows("CONFIG_READ", "reception"));
    }

    @Test
    void privacyAuditorCannotReadPatientsAndAdminCannotEither() {
        assertFalse(policy.allows("PATIENT_READ", "privacy_auditor"));
        assertFalse(policy.allows("PATIENT_READ", "tenant_admin"));
        assertFalse(policy.allows("PATIENT_SEARCH", "tenant_admin"));
    }

    @Test
    void unknownRoleAndActionDenied() {
        assertFalse(policy.allows("PATIENT_READ", "unknown_role"));
        assertFalse(policy.allows("PATIENT_DELETE", "reception"));
    }
}
