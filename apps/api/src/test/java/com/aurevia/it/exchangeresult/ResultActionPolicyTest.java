package com.aurevia.it.exchangeresult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.aurevia.exchange.result.ResultActionPolicy;

/** S-G2-04 (XCH-04) role-to-action permissions, covering every action and the deny path. */
class ResultActionPolicyTest {

    private final ResultActionPolicy policy = new ResultActionPolicy();

    @Test
    void onlyTenantAdministratorsRegisterExpectedOrders() {
        assertTrue(policy.allows(ResultActionPolicy.ORDER_REGISTER, "tenant_admin"));
        assertFalse(policy.allows(ResultActionPolicy.ORDER_REGISTER, "lab_verifier"));
        assertFalse(policy.allows(ResultActionPolicy.ORDER_REGISTER, "clinician"));
        assertFalse(policy.allows(ResultActionPolicy.ORDER_REGISTER, "privacy_auditor"));
    }

    @Test
    void onlyLaboratoryVerifiersImportClinicalResultContent() {
        assertTrue(policy.allows(ResultActionPolicy.RESULT_IMPORT, "lab_verifier"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_IMPORT, "tenant_admin"),
                "administrators configure and audit but hold no clinical import permission");
        assertFalse(policy.allows(ResultActionPolicy.RESULT_IMPORT, "clinician"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_IMPORT, "privacy_auditor"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_IMPORT, "reception"));
    }

    @Test
    void onlyClinicalRolesReadResultContent() {
        assertTrue(policy.allows(ResultActionPolicy.RESULT_READ, "clinician"));
        assertTrue(policy.allows(ResultActionPolicy.RESULT_READ, "lab_verifier"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_READ, "tenant_admin"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_READ, "privacy_auditor"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_READ, "reception"));
    }

    @Test
    void onlyQualifiedClinicalRolesAcknowledgeReviewedResults() {
        assertTrue(policy.allows(ResultActionPolicy.REVIEW_ACK, "clinician"));
        assertTrue(policy.allows(ResultActionPolicy.REVIEW_ACK, "lab_verifier"));
        assertFalse(policy.allows(ResultActionPolicy.REVIEW_ACK, "tenant_admin"));
        assertFalse(policy.allows(ResultActionPolicy.REVIEW_ACK, "privacy_auditor"));
    }

    @Test
    void reconciliationQueueIsReadOnlyInThisSlice() {
        assertTrue(policy.allows(ResultActionPolicy.RECONCILIATION_READ, "tenant_admin"));
        assertTrue(policy.allows(ResultActionPolicy.RECONCILIATION_READ, "privacy_auditor"));
        assertTrue(policy.allows(ResultActionPolicy.RECONCILIATION_READ, "lab_verifier"));
        assertFalse(policy.allows(ResultActionPolicy.RECONCILIATION_READ, "reception"));
        assertFalse(policy.allows(ResultActionPolicy.RECONCILIATION_READ, "clinician"));
    }

    @Test
    void unknownActionsAndRolesFailClosed() {
        assertFalse(policy.allows("XCH04_UNKNOWN", "tenant_admin"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_IMPORT, "unknown_role"));
        assertFalse(policy.allows(null, "tenant_admin"));
        assertFalse(policy.allows(ResultActionPolicy.RESULT_IMPORT, null));
        assertFalse(policy.allows(null, null));
    }
}
