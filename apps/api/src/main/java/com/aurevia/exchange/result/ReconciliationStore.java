package com.aurevia.exchange.result;

import com.aurevia.exchange.result.ReconciliationModel.Resolution;

/**
 * Durable read and append port for reconciliation decisions.
 *
 * <p>Every operation is scoped to the active tenant. A resolution is appended at most once per queue
 * entry: a second append yields no row so the caller can report a conflict instead of overwriting the
 * first decision. An implementation must survive process restarts and be visible to every worker.</p>
 */
public interface ReconciliationStore {

    /**
     * Read the resolution recorded for one queue entry in a tenant.
     *
     * @param organizationId active tenant identifier as text
     * @param entryId        queue entry identifier
     * @return the recorded resolution, or {@code null} when this tenant has none
     */
    Resolution find(String organizationId, String entryId);

    /**
     * Append one immutable decision for a queue entry.
     *
     * @param organizationId active tenant identifier as text
     * @param resolution     decision to record
     * @return the persisted resolution, or {@code null} when this entry already has one in the tenant
     */
    Resolution insert(String organizationId, Resolution resolution);
}
