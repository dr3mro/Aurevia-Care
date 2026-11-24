package com.aurevia.it.exchangerelease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.exchange.release.ReleaseActionPolicy;
import com.aurevia.exchange.release.ReleaseKind;
import com.aurevia.exchange.release.ReleaseState;

/** S-G2-06 pure policy branches: kinds, state machine and role actions (XCH-06, IAM-01). */
class ReleasePolicyTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    @Test
    void parseKindAcceptsAllPoliciesCaseInsensitively() {
        assertEquals(ReleaseKind.IMMEDIATE, ReleaseKind.parse("immediate"));
        assertEquals(ReleaseKind.TIMED, ReleaseKind.parse(" Timed "));
        assertEquals(ReleaseKind.HOLD, ReleaseKind.parse("HOLD"));
    }

    @Test
    void parseKindRejectsNullBlankAndUnknown() {
        assertEquals(422, assertThrows(ApiException.class, () -> ReleaseKind.parse(null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ReleaseKind.parse("  ")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> ReleaseKind.parse("bogus")).status());
    }

    @Test
    void heldCanGoToAuthorizedOrWithdrawnOnly() {
        assertTrue(ReleaseState.HELD.canTransitionTo(ReleaseState.AUTHORIZED));
        assertTrue(ReleaseState.HELD.canTransitionTo(ReleaseState.WITHDRAWN));
        // An unreviewed hold must be lifted before it can be corrected; the service, the enum
        // and the V24 database trigger all enforce this same invariant.
        assertFalse(ReleaseState.HELD.canTransitionTo(ReleaseState.CORRECTED));
        assertFalse(ReleaseState.HELD.canTransitionTo(ReleaseState.HELD));
    }

    @Test
    void authorizedCanOnlyBeWithdrawnOrCorrected() {
        assertTrue(ReleaseState.AUTHORIZED.canTransitionTo(ReleaseState.WITHDRAWN));
        assertTrue(ReleaseState.AUTHORIZED.canTransitionTo(ReleaseState.CORRECTED));
        assertFalse(ReleaseState.AUTHORIZED.canTransitionTo(ReleaseState.AUTHORIZED));
        assertFalse(ReleaseState.AUTHORIZED.canTransitionTo(ReleaseState.HELD));
    }

    @Test
    void terminalStatesCannotTransition() {
        for (ReleaseState target : ReleaseState.values()) {
            assertFalse(ReleaseState.WITHDRAWN.canTransitionTo(target));
            assertFalse(ReleaseState.CORRECTED.canTransitionTo(target));
        }
    }

    @Test
    void visibilityRequiresAuthorizedAndReachedEffectiveTime() {
        assertTrue(ReleaseState.AUTHORIZED.visibleAt(NOW, NOW));
        assertFalse(ReleaseState.AUTHORIZED.visibleAt(NOW.plusSeconds(1), NOW));
        assertFalse(ReleaseState.HELD.visibleAt(NOW, NOW));
        assertFalse(ReleaseState.WITHDRAWN.visibleAt(NOW, NOW));
        assertFalse(ReleaseState.CORRECTED.visibleAt(NOW, NOW));
    }

    @Test
    void deciderActionsRequireClinicianOrTenantAdmin() {
        ReleaseActionPolicy policy = new ReleaseActionPolicy();
        for (String action : new String[]{ReleaseActionPolicy.AUTHORIZE, ReleaseActionPolicy.LIFT_HOLD,
                ReleaseActionPolicy.WITHDRAW, ReleaseActionPolicy.CORRECT}) {
            assertTrue(policy.allows(action, "clinician"));
            assertTrue(policy.allows(action, "tenant_admin"));
            assertFalse(policy.allows(action, "reception"));
            assertFalse(policy.allows(action, "privacy_auditor"));
        }
    }

    @Test
    void readerActionsAllowCareTeamAndAuditorButNotDecisions() {
        ReleaseActionPolicy policy = new ReleaseActionPolicy();
        for (String action : new String[]{ReleaseActionPolicy.READ, ReleaseActionPolicy.HISTORY}) {
            assertTrue(policy.allows(action, "reception"));
            assertTrue(policy.allows(action, "privacy_auditor"));
            assertFalse(policy.allows(action, "unknown_role"));
        }
    }

    @Test
    void patientScopedReadPolicyExcludesReceptionAndAuditor() {
        // AUD-20261119-01: a patient-scoped revision query must not be usable as
        // a staff-wide enumeration surface. A verified patient is authorized by
        // the chart-link check, not by a staff role.
        ReleaseActionPolicy policy = new ReleaseActionPolicy();
        assertTrue(policy.allows(ReleaseActionPolicy.ACCESS, "clinician"));
        assertTrue(policy.allows(ReleaseActionPolicy.ACCESS, "tenant_admin"));
        assertFalse(policy.allows(ReleaseActionPolicy.ACCESS, "reception"));
        assertFalse(policy.allows(ReleaseActionPolicy.ACCESS, "privacy_auditor"));
        assertFalse(policy.allows(ReleaseActionPolicy.ACCESS, "patient"));
        assertFalse(policy.allows(ReleaseActionPolicy.ACCESS, "unknown_role"));
    }

    @Test
    void nullRoleAndUnknownActionAreDenied() {
        ReleaseActionPolicy policy = new ReleaseActionPolicy();
        assertFalse(policy.allows(ReleaseActionPolicy.AUTHORIZE, null));
        assertFalse(policy.allows("UNKNOWN_ACTION", "clinician"));
    }
}
