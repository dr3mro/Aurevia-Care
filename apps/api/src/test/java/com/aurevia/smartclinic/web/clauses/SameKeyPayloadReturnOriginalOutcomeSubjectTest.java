package com.aurevia.smartclinic.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.api.contract.IdempotencyKey;
import com.aurevia.smartclinic.api.contract.IdempotencyLedger;
import com.aurevia.smartclinic.api.contract.IdempotencyLedger.Decision;
import com.aurevia.smartclinic.api.contract.IdempotencyLedger.InMemoryIdempotencyLedger;
import com.aurevia.smartclinic.api.contract.IdempotencyLedger.Resolution;
import com.aurevia.smartclinic.api.contract.IdempotencyLedger.Scope;
import com.aurevia.smartclinic.api.contract.RequestDigest;

/**
 * ARC-03 clause 6 conformance at the contract boundary: a retry of a completed
 * keyed command returns its original outcome only while the caller still
 * authorizes the resource. The reference ledger is process-local; the missing
 * durable HTTP path is recorded as a gap in the clause status document.
 */
class SameKeyPayloadReturnOriginalOutcomeSubjectTest {

    private static final String ORG = "11111111-2222-3333-4444-555555555555";
    private static final String OUTCOME = "order-1:v2:ACCEPTED";

    private final IdempotencyLedger ledger = new InMemoryIdempotencyLedger();
    private final Scope scope = new Scope(ORG, "actor-1", "orders.accept");
    private final IdempotencyKey key = new IdempotencyKey("key-1");

    /**
     * Fingerprint the canonical command payload; a caller never supplies the digest,
     * so a reused key with a different payload is bound to a different fingerprint.
     */
    private RequestDigest digest(String payload) {
        return RequestDigest.of(scope.organizationId(), scope.subject(), scope.command(), payload);
    }

    @Test
    void sameKeyAndPayloadReplayOriginalOutcomeWhenStillAuthorized() {
        AtomicBoolean reauthorized = new AtomicBoolean();
        Resolution claim = ledger.claim(scope, key, digest("payload-a"),
                () -> {
                    reauthorized.set(true);
                    return true;
                });
        assertEquals(Decision.PROCEED, claim.decision());
        assertNull(claim.outcome());
        assertNotNull(claim.claim());

        ledger.complete(scope, key, claim.claim(), OUTCOME);

        // Reset the probe so the only supplier call observed is the replay's own.
        reauthorized.set(false);
        Resolution replay = ledger.claim(scope, key, digest("payload-a"),
                () -> {
                    reauthorized.set(true);
                    return true;
                });
        assertTrue(reauthorized.get(), "a replay must re-check current authorization");
        assertEquals(Decision.REPLAY, replay.decision());
        assertEquals(OUTCOME, replay.outcome(), "the original committed outcome is returned unchanged");
        assertNull(replay.claim(), "a replay must not mint a fresh claim token");
    }

    @Test
    void replayIsRefusedWhenAuthorizationNoLongerHolds() {
        Resolution claim = ledger.claim(scope, key, digest("payload-a"), () -> true);
        ledger.complete(scope, key, claim.claim(), OUTCOME);

        ApiException denial = assertThrows(ApiException.class,
                () -> ledger.claim(scope, key, digest("payload-a"), () -> false));
        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertFalse(denial.getMessage().contains(OUTCOME),
                "a lapsed authorization must not disclose the cached outcome");
    }
}
