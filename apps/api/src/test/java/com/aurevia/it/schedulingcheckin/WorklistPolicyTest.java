package com.aurevia.it.schedulingcheckin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.scheduling.checkin.WorklistPolicy;

/** S-G1-02 role matrix: reception is clerical, clinical roles own vitals and encounter opening. */
class WorklistPolicyTest {

    private final WorklistPolicy policy = new WorklistPolicy();

    private static final List<String> ACTIONS = List.of(
            WorklistPolicy.IDENTITY_VERIFY, WorklistPolicy.CHECK_IN, WorklistPolicy.WORKLIST_READ,
            WorklistPolicy.WORKLIST_ASSIGN, WorklistPolicy.HANDOFF, WorklistPolicy.VITALS_RECORD,
            WorklistPolicy.ENCOUNTER_OPEN, WorklistPolicy.INTAKE_LINK);

    @Test
    void receptionMayRunTheClericalFlowButNotClinicalActions() {
        for (String action : List.of(WorklistPolicy.IDENTITY_VERIFY, WorklistPolicy.CHECK_IN,
                WorklistPolicy.WORKLIST_READ, WorklistPolicy.WORKLIST_ASSIGN,
                WorklistPolicy.HANDOFF, WorklistPolicy.INTAKE_LINK)) {
            assertTrue(policy.allows(action, "reception"), "reception should allow " + action);
        }
        assertFalse(policy.allows(WorklistPolicy.VITALS_RECORD, "reception"));
        assertFalse(policy.allows(WorklistPolicy.ENCOUNTER_OPEN, "reception"));
    }

    @Test
    void clinicalAssistantMayRecordVitalsAndHandOffButNotOpenOrVerify() {
        assertTrue(policy.allows(WorklistPolicy.VITALS_RECORD, "clinical_assistant"));
        assertTrue(policy.allows(WorklistPolicy.HANDOFF, "clinical_assistant"));
        assertTrue(policy.allows(WorklistPolicy.WORKLIST_READ, "clinical_assistant"));
        assertTrue(policy.allows(WorklistPolicy.INTAKE_LINK, "clinical_assistant"));
        assertFalse(policy.allows(WorklistPolicy.ENCOUNTER_OPEN, "clinical_assistant"));
        assertFalse(policy.allows(WorklistPolicy.IDENTITY_VERIFY, "clinical_assistant"));
        assertFalse(policy.allows(WorklistPolicy.CHECK_IN, "clinical_assistant"));
        assertFalse(policy.allows(WorklistPolicy.WORKLIST_ASSIGN, "clinical_assistant"));
    }

    @Test
    void clinicianMayOpenAndRecordVitalsButNotRunClericalCommands() {
        assertTrue(policy.allows(WorklistPolicy.ENCOUNTER_OPEN, "clinician"));
        assertTrue(policy.allows(WorklistPolicy.VITALS_RECORD, "clinician"));
        assertTrue(policy.allows(WorklistPolicy.WORKLIST_READ, "clinician"));
        for (String action : List.of(WorklistPolicy.IDENTITY_VERIFY, WorklistPolicy.CHECK_IN,
                WorklistPolicy.WORKLIST_ASSIGN, WorklistPolicy.HANDOFF, WorklistPolicy.INTAKE_LINK)) {
            assertFalse(policy.allows(action, "clinician"), "clinician should not allow " + action);
        }
    }

    @Test
    void tenantAdminMayRunClericalWorklistButNotClinicalActions() {
        for (String action : List.of(WorklistPolicy.IDENTITY_VERIFY, WorklistPolicy.CHECK_IN,
                WorklistPolicy.WORKLIST_READ, WorklistPolicy.WORKLIST_ASSIGN,
                WorklistPolicy.HANDOFF, WorklistPolicy.INTAKE_LINK)) {
            assertTrue(policy.allows(action, "tenant_admin"), "admin should allow " + action);
        }
        assertFalse(policy.allows(WorklistPolicy.ENCOUNTER_OPEN, "tenant_admin"),
                "opening a clinical encounter requires clinician privileges, not tenant administration");
        assertFalse(policy.allows(WorklistPolicy.VITALS_RECORD, "tenant_admin"),
                "recording vitals requires a clinical role, not tenant administration");
        for (String action : ACTIONS) {
            assertFalse(policy.allows(action, "privacy_auditor"), "auditor must not allow " + action);
        }
    }

    @Test
    void unknownAndMissingRolesFailClosed() {
        for (String action : ACTIONS) {
            assertFalse(policy.allows(action, "unknown_role"));
            assertFalse(policy.allows(action, null));
        }
        assertFalse(policy.allows("UNKNOWN_ACTION", "tenant_admin"));
        assertFalse(policy.allows("UNKNOWN_ACTION", "reception"));
        assertFalse(policy.allows("UNKNOWN_ACTION", "clinical_assistant"));
        assertFalse(policy.allows("UNKNOWN_ACTION", "clinician"));
    }
}
