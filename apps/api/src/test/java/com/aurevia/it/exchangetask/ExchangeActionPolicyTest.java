package com.aurevia.it.exchangetask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.task.ExchangeActionPolicy;
import com.aurevia.exchange.task.ExchangeDimension;

/** Scoped role matrix: only a clinician may change the clinical review dimension. */
class ExchangeActionPolicyTest {

    private static final String[] ROLES =
            {"tenant_admin", "clinician", "lab_collector", "reception", "privacy_auditor", "unknown"};

    private final ExchangeActionPolicy policy = new ExchangeActionPolicy();

    @Test
    void createIsLimitedToClinicalAndAdministrativeRoles() {
        for (String role : ROLES) {
            boolean expected = role.equals("tenant_admin") || role.equals("clinician");
            assertEquals(expected, policy.allowsCreate(role), role);
        }
    }

    @Test
    void readIsAvailableToEveryStaffRoleInScope() {
        for (String role : ROLES) {
            boolean expected = role.equals("tenant_admin") || role.equals("clinician")
                    || role.equals("lab_collector") || role.equals("reception");
            assertEquals(expected, policy.allowsRead(role), role);
        }
    }

    @Test
    void workflowAndTransmissionRolesAreBounded() {
        for (String role : ROLES) {
            boolean expected = role.equals("lab_collector") || role.equals("clinician");
            assertEquals(expected, policy.allows(ExchangeDimension.WORKFLOW.policyAction(), role), role);
            boolean transmission = expected || role.equals("tenant_admin");
            assertEquals(transmission,
                    policy.allows(ExchangeDimension.TRANSMISSION.policyAction(), role), role);
        }
    }

    @Test
    void clinicalReviewIsClinicianOnly() {
        for (String role : ROLES) {
            assertEquals(role.equals("clinician"),
                    policy.allows(ExchangeDimension.CLINICAL_REVIEW.policyAction(), role), role);
        }
        assertFalse(policy.allows("EXCHANGE_UNKNOWN", "clinician"));
        assertTrue(policy.allows(ExchangeDimension.CLINICAL_REVIEW.policyAction(), "clinician"));
    }
}
