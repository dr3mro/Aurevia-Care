package com.aurevia.web;

import com.aurevia.tenant.TenantContext;

/**
 * Decides whether a cached response may be disclosed to the current caller (ARC-03).
 *
 * <p>The contract requires a retry of the same key and payload to return the
 * original outcome only while the caller is still authorized. This supplier is
 * evaluated on every replay, from live identity state, never from the cached
 * row: a caller whose membership was revoked during the retention window must
 * not receive a stored clinical response. A {@code false} decision is concealed
 * as a generic 404 so the cached outcome is not disclosed.</p>
 *
 * @param context tenant and subject bound to the current request
 * @param command matched write command that owns the key
 * @return {@code true} only while the caller still authorizes the replay
 */
@FunctionalInterface
public interface ReplayAuthorization {

    /** Re-read current authorization for a replay of one keyed command. */
    boolean stillAuthorized(TenantContext context, String command);
}
