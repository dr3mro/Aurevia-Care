package com.aurevia.smartclinic.it.exchangetask;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.exchange.response.ExchangeState;
import com.aurevia.smartclinic.exchange.response.Xch03ActionPolicy;
import com.aurevia.smartclinic.exchange.response.Xch03Commands.Notification;
import com.aurevia.smartclinic.exchange.response.Xch03Commands.RetainedCopy;
import com.aurevia.smartclinic.exchange.response.Xch03Inputs;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trust-boundary validation branches for XCH-03 inputs. */
class Xch03InputsTest {

    @Test
    void validatesIdentifiers() {
        String valid = UUID.randomUUID().toString();
        assertEquals(valid, Xch03Inputs.uuid(valid, "id"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.uuid(null, "id")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.uuid("not-a-uuid", "id")).status());
    }

    @Test
    void validatesBoundedTextAndOptionalBlanks() {
        assertEquals("trimmed", Xch03Inputs.text("  trimmed  ", "field", 16, true));
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.text("   ", "reason", 16, true)).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.text("x".repeat(17), "field", 16, true)).status());
        assertNull(Xch03Inputs.text("   ", "assignee", 16, false));
        assertNull(Xch03Inputs.text(null, "assignee", 16, false));
    }

    @Test
    void validatesCodesAndChecksums() {
        assertEquals("RESULT_REVIEW", Xch03Inputs.code("RESULT_REVIEW", "purpose"));
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.code("RESULT REVIEW!", "purpose")).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.code("  ", "purpose")).status());
        assertEquals("a".repeat(64), Xch03Inputs.checksum("a".repeat(64)));
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.checksum("a".repeat(63))).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.checksum("z".repeat(64))).status());
    }

    @Test
    void validatesTheApprovedRetentionPolicies() {
        for (String policy : Xch03Inputs.RETENTION_POLICIES) {
            assertEquals(policy, Xch03Inputs.retentionPolicy(policy));
        }
        assertEquals(422, assertThrows(ApiException.class,
                () -> Xch03Inputs.retentionPolicy("FOREVER")).status());
    }

    @Test
    void generatesFreshIdentifiers() {
        String id = Xch03Inputs.newId();
        assertEquals(id, Xch03Inputs.uuid(id, "id"));
    }
}

/** Role-to-action matrix for the XCH-03 slice; every branch fails closed. */
class Xch03ActionPolicyTest {

    private final Xch03ActionPolicy policy = new Xch03ActionPolicy();

    private static final List<String> RECIPIENT_ACTIONS = List.of(
            Xch03ActionPolicy.EXCHANGE_ACCEPT, Xch03ActionPolicy.EXCHANGE_REJECT,
            Xch03ActionPolicy.EXCHANGE_CLARIFY, Xch03ActionPolicy.EXCHANGE_RESPOND);
    private static final List<String> SENDER_ACTIONS = List.of(
            Xch03ActionPolicy.EXCHANGE_PROPOSE_CHANGE, Xch03ActionPolicy.EXCHANGE_REQUEST_CANCEL);
    private static final List<String> READ_ACTIONS = List.of(
            Xch03ActionPolicy.EXCHANGE_READ, Xch03ActionPolicy.EXCHANGE_RETRIEVE);

    @Test
    void recipientRolesMayRespondButCannotRevokeOrProposeChanges() {
        for (String role : List.of("lab_collector", "lab_verifier", "radiology_staff", "pt_clinician",
                "pharmacy_staff")) {
            for (String action : RECIPIENT_ACTIONS) {
                assertTrue(policy.allows(action, role), role + " should allow " + action);
            }
            for (String action : SENDER_ACTIONS) {
                assertFalse(policy.allows(action, role), role + " should not allow " + action);
            }
            assertFalse(policy.allows(Xch03ActionPolicy.EXCHANGE_REVOKE, role));
        }
        for (String action : RECIPIENT_ACTIONS) {
            assertTrue(policy.allows(action, "clinician"));
        }
    }

    @Test
    void senderRolesMayChangeAndCancelButNotRespond() {
        for (String role : List.of("clinician", "reception")) {
            for (String action : SENDER_ACTIONS) {
                assertTrue(policy.allows(action, role));
            }
        }
        // Reception is clerical: it may propose a change but never make the
        // recipient decision itself. A clinician may do both in this slice.
        for (String action : RECIPIENT_ACTIONS) {
            assertFalse(policy.allows(action, "reception"));
        }
        assertTrue(policy.allows(Xch03ActionPolicy.EXCHANGE_REVOKE, "clinician"));
        assertTrue(policy.allows(Xch03ActionPolicy.EXCHANGE_REVOKE, "tenant_admin"));
        assertFalse(policy.allows(Xch03ActionPolicy.EXCHANGE_REVOKE, "reception"));
    }

    @Test
    void readsAreAvailableToClinicalAndAuditRolesOnly() {
        for (String role : List.of("lab_collector", "lab_verifier", "radiology_staff", "pt_clinician",
                "pharmacy_staff", "clinician", "reception", "tenant_admin", "privacy_auditor")) {
            for (String action : READ_ACTIONS) {
                assertTrue(policy.allows(action, role));
            }
        }
        for (String action : READ_ACTIONS) {
            assertFalse(policy.allows(action, "nurse"));
        }
    }

    @Test
    void unknownRolesAndUnlistedActionsFailClosed() {
        for (String action : List.of(Xch03ActionPolicy.EXCHANGE_ACCEPT,
                Xch03ActionPolicy.EXCHANGE_REVOKE, "EXCHANGE_PURGE")) {
            assertFalse(policy.allows(action, "unknown-role"));
        }
        assertFalse(policy.allows("EXCHANGE_PURGE", "clinician"));
    }
}

/** Wire contract and terminality of the XCH-03 lifecycle enum. */
class ExchangeStateTest {

    @Test
    void roundTripsEveryWireValue() {
        for (ExchangeState state : ExchangeState.values()) {
            assertEquals(state, ExchangeState.from(state.wire()));
        }
        assertEquals("change_pending", ExchangeState.CHANGE_PENDING.wire());
        assertEquals("clarification_requested", ExchangeState.CLARIFICATION_REQUESTED.wire());
    }

    @Test
    void marksOnlyTheFinalStatesTerminal() {
        for (ExchangeState state : ExchangeState.values()) {
            boolean terminal = state == ExchangeState.REJECTED
                    || state == ExchangeState.CANCELLED
                    || state == ExchangeState.REVOKED;
            assertEquals(terminal, state.isTerminal(), state.wire());
        }
        assertTrue(ExchangeState.REVOKED.isTerminal());
        assertFalse(ExchangeState.REQUESTED.isTerminal());
    }
}

/** Immutable ledger records expose their values without exposing mutable state. */
class Xch03RecordsTest {

    private static final Instant TIME = Instant.parse("2026-11-19T12:00:00Z");

    @Test
    void retainedCopyCarriesPurposePolicyAndHold() {
        RetainedCopy copy = new RetainedCopy("copy-1", 2, "a".repeat(64), "RESULT_REVIEW",
                "LEGAL_HOLD", true, "verifier", TIME);
        assertEquals(2, copy.revision());
        assertEquals("RESULT_REVIEW", copy.permittedPurpose());
        assertEquals("LEGAL_HOLD", copy.retentionPolicy());
        assertTrue(copy.legalHold());
        assertEquals("verifier", copy.actorId());
        assertEquals(TIME, copy.occurredAt());
    }

    @Test
    void notificationCarriesTheDurableEventId() {
        Notification notification = new Notification("notice-1", "evt-1", "partner-lab",
                "secure-email", "NOTIFIED", "clinician", TIME);
        assertEquals("evt-1", notification.eventId());
        assertEquals("partner-lab", notification.target());
        assertEquals("NOTIFIED", notification.outcome());
        assertEquals(TIME, notification.occurredAt());
    }
}
