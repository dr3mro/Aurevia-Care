package com.aurevia.authz.history;

import java.util.List;

/**
 * Read boundary for the append-only IAM-02 role-change ledger.
 *
 * <p>The interface has no write operation on purpose: history rows are recorded by
 * database triggers on the authoritative membership and role-grant tables, so no
 * application path can update or delete them. Forced row-level security confines
 * every read to the active organization.</p>
 */
public interface RoleHistoryStore {

    /**
     * Read every immutable change recorded for a subject in the active organization.
     *
     * @param subject staff subject whose ledger is requested
     * @return changes in effective-time order, empty when the subject is not visible
     */
    List<RoleHistory.RoleChange> findBySubject(String subject);
}
