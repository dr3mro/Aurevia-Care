package com.aurevia.api.contract;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.aurevia.api.ApiException;

/**
 * Idempotency contract for protected commands (ARC-03, ARC-04).
 *
 * <p>A key is scoped to tenant, authenticated caller and command. A first call
 * claims the key; a completed call with the same digest replays the original
 * outcome only after the caller re-authorizes through its normal protected
 * path. A different payload or an in-flight duplicate conflicts. Failed
 * commands release their claim so a corrected retry can proceed. Expiry is a
 * dedup-cache concern only: durable business uniqueness for external clinical
 * actions belongs to the owning module's transactional constraint, not to this
 * cache.</p>
 */
public interface IdempotencyLedger {

    /** Outcome of evaluating a keyed command. */
    enum Decision {
        /** No committed outcome exists; the command may proceed. */
        PROCEED,
        /** The original committed outcome may be returned after re-authorization. */
        REPLAY,
        /** The key is in flight or bound to a different payload. */
        CONFLICT
    }

    /**
     * Tenant, caller and command that own an idempotency key.
     *
     * @param organizationId active tenant, never inferred from the request body
     * @param subject authenticated caller
     * @param command stable command name
     */
    record Scope(String organizationId, String subject, String command) {

        /**
         * Reject an incomplete scope.
         *
         * @throws com.aurevia.api.ApiException {@code 422} with code
         *         {@code INVALID_SCOPE} when any part is null or blank
         */
        public Scope {
            require(organizationId);
            require(subject);
            require(command);
        }

        private static void require(String part) {
            if (part == null || part.isBlank()) {
                throw ApiErrorCode.INVALID_SCOPE.exception();
            }
        }
    }

    /**
     * The decision plus the opaque committed outcome reference and claim token.
     *
     * <p>The outcome is an opaque resource reference such as
     * {@code order-1:v2:ACCEPTED}; the caller re-loads and re-authorizes it and
     * mints a fresh correlation id for the current request. No original HTTP
     * envelope is replayed and no domain audit event is re-emitted.</p>
     *
     * @param decision the evaluated decision
     * @param outcome the original outcome reference, or {@code null}
     * @param claim the in-flight claim token, or {@code null}
     */
    record Resolution(Decision decision, String outcome, String claim) {
    }

    /**
     * Evaluate a keyed command.
     *
     * @param scope tenant, caller and command scope
     * @param key the validated idempotency key
     * @param digest server-computed digest of the canonical request
     * @param stillAuthorized caller-supplied re-authorization of the original
     *        resource through the normal protected path
     * @return the decision to proceed, replay or conflict
     */
    Resolution claim(Scope scope, IdempotencyKey key, RequestDigest digest, BooleanSupplier stillAuthorized);

    /**
     * Record a successfully committed command outcome.
     *
     * @param scope key scope
     * @param key the idempotency key
     * @param claim the claim token returned by {@code claim}
     * @param outcome opaque committed outcome reference
     */
    void complete(Scope scope, IdempotencyKey key, String claim, String outcome);

    /**
     * Release a failed command's claim without caching the failure.
     *
     * @param scope key scope
     * @param key the idempotency key
     * @param claim the claim token returned by {@code claim}
     */
    void fail(Scope scope, IdempotencyKey key, String claim);

    /**
     * Expire a retry key from this cache.
     *
     * @param scope key scope
     * @param key the idempotency key
     */
    void expire(Scope scope, IdempotencyKey key);

    /**
     * Synthetic, process-local reference implementation of the contract.
     *
     * <p>This is a single-node stand-in for wire-semantics and unit tests. It is
     * not durable, does not span process loss and cannot satisfy ARC-04's
     * atomic commit of state, event and audit or ARC-05's outbox. A production
     * implementation must persist the claim and outcome transactionally in the
     * owning module.</p>
     */
    final class InMemoryIdempotencyLedger implements IdempotencyLedger {

        private final Map<CacheKey, Entry> entries = new ConcurrentHashMap<>();

        @Override
        public Resolution claim(Scope scope, IdempotencyKey key, RequestDigest digest,
                                BooleanSupplier stillAuthorized) {
            CacheKey cacheKey = new CacheKey(scope, key.value());
            AtomicReference<Resolution> result = new AtomicReference<>();
            entries.compute(cacheKey, (ignored, existing) -> {
                if (existing == null) {
                    String claim = UUID.randomUUID().toString();
                    result.set(new Resolution(Decision.PROCEED, null, claim));
                    return new Entry(digest, claim, Status.IN_PROGRESS, null);
                }
                result.set(resolve(existing, digest, stillAuthorized));
                return existing;
            });
            return result.get();
        }

        private static Resolution resolve(Entry existing, RequestDigest digest,
                                          BooleanSupplier stillAuthorized) {
            if (!existing.digest().equals(digest) || existing.status() == Status.IN_PROGRESS) {
                return new Resolution(Decision.CONFLICT, null, null);
            }
            if (!stillAuthorized.getAsBoolean()) {
                throw ApiErrorCode.NOT_FOUND.exception();
            }
            return new Resolution(Decision.REPLAY, existing.outcome(), null);
        }

        @Override
        public void complete(Scope scope, IdempotencyKey key, String claim, String outcome) {
            CacheKey cacheKey = new CacheKey(scope, key.value());
            entries.compute(cacheKey, (ignored, existing) -> {
                if (existing == null || !existing.claim().equals(claim)
                        || existing.status() != Status.IN_PROGRESS) {
                    throw invalidClaim();
                }
                return new Entry(existing.digest(), claim, Status.COMPLETED, outcome);
            });
        }

        @Override
        public void fail(Scope scope, IdempotencyKey key, String claim) {
            CacheKey cacheKey = new CacheKey(scope, key.value());
            entries.computeIfPresent(cacheKey, (ignored, existing) -> {
                if (!existing.claim().equals(claim) || existing.status() != Status.IN_PROGRESS) {
                    return existing;
                }
                return null;
            });
        }

        @Override
        public void expire(Scope scope, IdempotencyKey key) {
            entries.remove(new CacheKey(scope, key.value()));
        }

        private static ApiException invalidClaim() {
            return new ApiException(409, "IDEMPOTENCY_CLAIM_INVALID", "claim is no longer active");
        }

        private record CacheKey(Scope scope, String key) {
        }

        private record Entry(RequestDigest digest, String claim, Status status, String outcome) {
        }

        private enum Status {
            IN_PROGRESS,
            COMPLETED
        }
    }
}
