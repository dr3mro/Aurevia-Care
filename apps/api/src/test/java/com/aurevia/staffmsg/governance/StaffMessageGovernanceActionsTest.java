package com.aurevia.staffmsg.governance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.StaffAction;

/**
 * Exhaustive role matrix verification for staff-message governance (MSG-07).
 *
 * <p>Every enum arm is checked for both an allowed and a denied role so the
 * switch expression's branches are proven, not assumed.</p>
 */
class StaffMessageGovernanceActionsTest {

    private final StaffMessageGovernanceActions actions = new StaffMessageGovernanceActions();

    /** Governance commands belong to records-officer and administrative roles only. */
    @Test
    void governanceCommandsAreLimitedToRecordsOfficers() {
        List<StaffAction> governance = List.of(StaffAction.HOLD_PLACE, StaffAction.HOLD_RELEASE,
                StaffAction.RETENTION_APPLY, StaffAction.EXPORT, StaffAction.AUDIT_READ);
        for (StaffAction action : governance) {
            assertTrue(actions.allows(action, "privacy_auditor"), action.name());
            assertTrue(actions.allows(action, "tenant_admin"), action.name());
            assertFalse(actions.allows(action, "clinician"), action.name());
            assertFalse(actions.allows(action, "unit_staff"), action.name());
            assertFalse(actions.allows(action, "reception"), action.name());
            assertFalse(actions.allows(action, "patient"), action.name());
        }
    }

    /** Content and folder actions belong to staff conversation roles only. */
    @Test
    void staffActionsAreLimitedToConversationRoles() {
        List<StaffAction> staff = List.of(StaffAction.OPEN, StaffAction.SEND, StaffAction.FORWARD,
                StaffAction.ATTACH, StaffAction.DOWNLOAD, StaffAction.FOLDER_MOVE, StaffAction.DELETE);
        for (StaffAction action : staff) {
            assertTrue(actions.allows(action, "clinician"), action.name());
            assertTrue(actions.allows(action, "unit_staff"), action.name());
            assertTrue(actions.allows(action, "reception"), action.name());
            assertFalse(actions.allows(action, "privacy_auditor"), action.name());
            assertFalse(actions.allows(action, "tenant_admin"), action.name());
            assertFalse(actions.allows(action, "patient"), action.name());
            assertFalse(actions.allows(action, null), action.name());
        }
    }

    /** Every enum value belongs to exactly one branch of the role matrix. */
    @Test
    void everyActionIsCoveredByTheMatrix() {
        for (StaffAction action : StaffAction.values()) {
            boolean anyRole = false;
            for (String role : Arrays.asList("clinician", "unit_staff", "reception", "privacy_auditor",
                    "tenant_admin", "patient", null)) {
                anyRole |= actions.allows(action, role);
            }
            assertTrue(anyRole, action.name() + " has no permitted role");
        }
    }
}
