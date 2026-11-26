package com.aurevia.api.contract;

/**
 * Durable replay cache for protected write endpoints (ARC-03, ARC-04).
 *
 * <p>A key is owned by the active tenant, the authenticated caller and a stable
 * command name. The first successful response is frozen for a bounded window;
 * a retry with the same canonical request replays it without invoking the
 * handler again, while a different request body under the same key conflicts.
 * This is a deduplication cache, not durable clinical business uniqueness.</p>
 */
public interface IdempotencyResponseStore {

    /**
     * Evaluate a keyed request.
     *
     * @param scope tenant, authenticated caller and command that own the key
     * @param key the validated request key
     * @param digest server-computed digest of the canonical request
     * @return the decision and, for a replay, the frozen response
     */
    ResponseResolution evaluate(IdempotencyLedger.Scope scope, IdempotencyKey key, RequestDigest digest);

    /**
     * Freeze the first successful response under a key. A losing concurrent
     * insert is ignored so the earliest committed response always wins.
     *
     * @param scope tenant, authenticated caller and command that own the key
     * @param key the validated request key
     * @param digest digest of the request that produced the response
     * @param response the captured first response
     */
    void store(IdempotencyLedger.Scope scope, IdempotencyKey key, RequestDigest digest, StoredResponse response);
}
