package com.aurevia.api.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.api.contract.IdempotencyLedger.Decision;
import com.aurevia.api.contract.IdempotencyLedger.InMemoryIdempotencyLedger;
import com.aurevia.api.contract.IdempotencyLedger.Resolution;
import com.aurevia.api.contract.IdempotencyLedger.Scope;

/** ARC-03/ARC-04 idempotency scope, claim, replay, conflict and failure semantics. */
class IdempotencyLedgerTest {

    private static final String ORG = "11111111-2222-3333-4444-555555555555";
    private static final String OUTCOME = "order-1:v2:ACCEPTED";

    private final IdempotencyLedger ledger = new InMemoryIdempotencyLedger();
    private final Scope scope = new Scope(ORG, "actor-1", "orders.accept");
    private final IdempotencyKey key = new IdempotencyKey("key-1");

    private RequestDigest digest(String payload) {
        return RequestDigest.of(ORG, "actor-1", "orders.accept", payload);
    }

    @Test
    void unseenClaimProceedsAndCompletedClaimReplaysAuthorizedOutcome() {
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        assertEquals(Decision.PROCEED, claim.decision());
        assertNull(claim.outcome());
        assertNotNull(claim.claim());

        ledger.complete(scope, key, claim.claim(), OUTCOME);
        Resolution replay = ledger.claim(scope, key, digest("a"), () -> true);
        assertEquals(Decision.REPLAY, replay.decision());
        assertEquals(OUTCOME, replay.outcome());
        assertNull(replay.claim());
    }

    @Test
    void reusedKeyWithDifferentPayloadConflicts() {
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        ledger.complete(scope, key, claim.claim(), OUTCOME);
        Resolution conflict = ledger.claim(scope, key, digest("b"), () -> true);
        assertEquals(Decision.CONFLICT, conflict.decision());
        assertNull(conflict.outcome());
    }

    @Test
    void concurrentInProgressClaimConflicts() {
        ledger.claim(scope, key, digest("a"), () -> true);
        assertEquals(Decision.CONFLICT, ledger.claim(scope, key, digest("a"), () -> true).decision());
    }

    @Test
    void replayRechecksCurrentAuthorizationAndConcealsOutcome() {
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        ledger.complete(scope, key, claim.claim(), OUTCOME);
        ApiException denial = assertThrows(ApiException.class,
                () -> ledger.claim(scope, key, digest("a"), () -> false));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
    }

    @Test
    void failedCommandReleasesItsClaimForARetryWithAnotherPayload() {
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        ledger.fail(scope, key, claim.claim());
        assertEquals(Decision.PROCEED, ledger.claim(scope, key, digest("b"), () -> true).decision());
    }

    @Test
    void expiringARetryKeyStartsANewClaimWithoutReplaying() {
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        ledger.complete(scope, key, claim.claim(), OUTCOME);
        ledger.expire(scope, key);
        Resolution next = ledger.claim(scope, key, digest("b"), () -> true);
        assertEquals(Decision.PROCEED, next.decision());
        assertNull(next.outcome());
    }

    @Test
    void completeRejectsUnknownClaimAndMismatchedOrCompletedClaim() {
        assertEquals(409, assertThrows(ApiException.class,
                () -> ledger.complete(scope, key, "unknown", OUTCOME)).status());
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        assertEquals(409, assertThrows(ApiException.class,
                () -> ledger.complete(scope, key, "unknown", OUTCOME)).status());
        ledger.complete(scope, key, claim.claim(), OUTCOME);
        assertEquals(409, assertThrows(ApiException.class,
                () -> ledger.complete(scope, key, claim.claim(), OUTCOME)).status());
        assertEquals(OUTCOME, ledger.claim(scope, key, digest("a"), () -> true).outcome());
    }

    @Test
    void failIgnoresUnknownMismatchedAndCompletedClaims() {
        ledger.fail(scope, key, "unknown");
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        ledger.fail(scope, key, "unknown");
        assertEquals(Decision.CONFLICT, ledger.claim(scope, key, digest("a"), () -> true).decision());
        ledger.complete(scope, key, claim.claim(), OUTCOME);
        ledger.fail(scope, key, claim.claim());
        assertEquals(Decision.REPLAY, ledger.claim(scope, key, digest("a"), () -> true).decision());
    }

    @Test
    void expireIsIdempotentAndClearsTheEntry() {
        ledger.expire(scope, key);
        ledger.expire(scope, key);
        assertEquals(Decision.PROCEED, ledger.claim(scope, key, digest("a"), () -> true).decision());
        ledger.expire(scope, key);
        assertEquals(Decision.PROCEED, ledger.claim(scope, key, digest("a"), () -> true).decision());
    }

    @Test
    void scopeIsTenantCallerAndCommandBound() {
        assertEquals(422, assertThrows(ApiException.class, () -> new Scope(null, "a", "cmd")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Scope(" ", "a", "cmd")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Scope(ORG, null, "cmd")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Scope(ORG, " ", "cmd")).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Scope(ORG, "a", null)).status());
        assertEquals(422, assertThrows(ApiException.class, () -> new Scope(ORG, "a", " ")).status());
        assertEquals("INVALID_SCOPE",
                assertThrows(ApiException.class, () -> new Scope(ORG, " ", "cmd")).code());

        Scope peerTenant = new Scope("99999999-8888-7777-6666-555555555555", "a", "cmd");
        Scope peerCaller = new Scope(ORG, "b", "cmd");
        Scope peerCommand = new Scope(ORG, "a", "other.cmd");
        Resolution claim = ledger.claim(scope, key, digest("a"), () -> true);
        ledger.complete(scope, key, claim.claim(), OUTCOME);
        assertEquals(Decision.PROCEED, ledger.claim(peerTenant, key, digest("a"), () -> true).decision());
        assertEquals(Decision.PROCEED, ledger.claim(peerCaller, key, digest("a"), () -> true).decision());
        assertEquals(Decision.PROCEED, ledger.claim(peerCommand, key, digest("a"), () -> true).decision());
        assertEquals(scope, new Scope(ORG, "actor-1", "orders.accept"));
        assertEquals(scope.hashCode(), new Scope(ORG, "actor-1", "orders.accept").hashCode());
        assertNotNull(scope.toString());
    }

    @Test
    void decisionsAndResolutionExposeTheirComponents() {
        assertEquals(List.of(Decision.PROCEED, Decision.REPLAY, Decision.CONFLICT),
                Arrays.asList(Decision.values()));
        Resolution proceeded = new Resolution(Decision.PROCEED, null, "claim-1");
        assertEquals(Decision.PROCEED, proceeded.decision());
        assertNull(proceeded.outcome());
        assertEquals("claim-1", proceeded.claim());
    }
}
