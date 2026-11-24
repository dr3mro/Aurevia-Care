package com.aurevia.exchange.result;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Authorization mapping of the XCH-04 reconciliation-resolution action.
 */
class ResultActionPolicyReconciliationTest {

    private final ResultActionPolicy policy = new ResultActionPolicy();

    @Test
    void allowsOperatorsToResolveAndDeniesClinicalReadersAndAuditors() {
        String action = ResultActionPolicy.RECONCILIATION_RESOLVE;
        assertTrue(policy.allows(action, "lab_verifier"));
        assertTrue(policy.allows(action, "tenant_admin"));
        assertFalse(policy.allows(action, "clinician"));
        assertFalse(policy.allows(action, "privacy_auditor"));
        assertFalse(policy.allows(action, "reception"));
    }

    @Test
    void deniesNullActionOrRole() {
        assertFalse(policy.allows(null, "tenant_admin"));
        assertFalse(policy.allows(ResultActionPolicy.RECONCILIATION_RESOLVE, null));
        assertFalse(policy.allows(null, null));
    }
}
