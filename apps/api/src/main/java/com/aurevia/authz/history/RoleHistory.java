package com.aurevia.authz.history;

import java.util.List;

/**
 * Immutable IAM-02 role-change history for one staff subject in the active organization.
 *
 * <p>The ledger is append-only in storage: every grant, replacement and revocation
 * is a separate immutable row carrying the acting user (who), the database time
 * (when) and the effective-date window supplied by the authoritative membership
 * event. This projection freezes its lists so callers cannot rewrite history in
 * memory either.</p>
 *
 * @return this record
 */
public record RoleHistory(String subject, List<RoleChange> changes) {

    /**
     * Freeze the change list so an append-only history cannot be altered by a caller.
     *
     * @throws java.lang.NullPointerException when changes is null
     */
    public RoleHistory {
        changes = List.copyOf(changes);
    }

    /**
     * Return the immutable change list in effective-time order.
     *
     * @return changes oldest first
     */
    @Override
    public List<RoleChange> changes() {
        return List.copyOf(changes);
    }

    /**
     * One immutable role-change ledger row.
     *
     * @param id            event identifier as text
     * @param subject       staff subject the change belongs to
     * @param roleKey       bounded role key affected by the change
     * @param changeKind    {@code GRANT}, {@code CHANGE} or {@code REVOKE}
     * @param actor         subject of the user who performed the change, or {@code null}
     * @param recordedAt    database time the row was appended
     * @param effectiveFrom instant from which the change is effective
     * @param effectiveTo   instant at which it stops being effective, or {@code null}
     * @return this record
     */
    public record RoleChange(String id, String subject, String roleKey, String changeKind, String actor,
                             String recordedAt, String effectiveFrom, String effectiveTo) {
    }
}
