package com.aurevia.messaging.enrollment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aurevia.messaging.enrollment.EnrollmentContract.AccessToken;
import com.aurevia.messaging.enrollment.EnrollmentContract.Invitation;
import com.aurevia.messaging.enrollment.EnrollmentContract.InvitationState;

/**
 * Covers the new atomic single-use branches in the synthetic in-memory adapter,
 * which mirrors the JDBC compare-and-set for unit-level service tests.
 */
class InMemoryPatientAccessStoreBranchTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CHART = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CODE = "opaque-code";
    private static final String FACTS = "site-verified-facts";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    @Test
    void invitationIsConsumedOnceAndFailuresStopAfterRedemption() {
        InMemoryPatientAccessStore store = new InMemoryPatientAccessStore();
        Invitation pending = new Invitation(CODE, ORG, CHART, FACTS, NOW.plusSeconds(600),
                InvitationState.PENDING, 0, "reception", NOW);
        store.putInvitation(ORG, pending);

        store.recordInvitationFailure(ORG, CODE, 2);
        assertEquals(2, store.invitation(ORG, CODE).orElseThrow().failedAttempts());
        assertTrue(store.consumeInvitation(ORG, CODE));
        assertEquals(InvitationState.REDEEMED, store.invitation(ORG, CODE).orElseThrow().state());

        // A late failure or a second consume cannot change a redeemed invitation.
        store.recordInvitationFailure(ORG, CODE, 9);
        assertFalse(store.consumeInvitation(ORG, CODE));
        assertEquals(2, store.invitation(ORG, CODE).orElseThrow().failedAttempts());
        assertFalse(store.consumeInvitation(ORG, "unknown"));
    }

    @Test
    void tokenIsConsumedOnce() {
        InMemoryPatientAccessStore store = new InMemoryPatientAccessStore();
        AccessToken token = new AccessToken(CODE, ORG, "syn-patient", "recovery",
                NOW.plusSeconds(900), false);
        store.putToken(ORG, token);

        assertTrue(store.consumeToken(ORG, CODE));
        assertTrue(store.token(ORG, CODE).orElseThrow().used());
        assertFalse(store.consumeToken(ORG, CODE));
        assertFalse(store.consumeToken(ORG, "unknown"));
    }
}
