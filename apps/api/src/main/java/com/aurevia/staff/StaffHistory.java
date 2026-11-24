package com.aurevia.staff;

import java.util.List;

/**
 * Append-only membership and role-grant history for one staff subject.
 *
 * <p>History rows are never mutated: deactivation appends a {@code revoked}
 * membership event, and role grants carry the grantor and effective-date window
 * they were created with.</p>
 *
 * @return this record
 */
public record StaffHistory(String subject, List<MembershipEvent> memberships, List<RoleGrant> roleGrants) {

    /**
     * Freeze both history lists so append-only history cannot be altered in memory.
     *
     * @throws NullPointerException when either list is null
     */
    public StaffHistory {
        memberships = List.copyOf(memberships);
        roleGrants = List.copyOf(roleGrants);
    }

    /**
     * Return the immutable membership events.
     *
     * @return membership events in effective-time order
     */
    @Override
    public List<MembershipEvent> memberships() {
        return List.copyOf(memberships);
    }

    /**
     * Return the immutable role grants.
     *
     * @return role grants in effective-time order
     */
    @Override
    public List<RoleGrant> roleGrants() {
        return List.copyOf(roleGrants);
    }

    /**
     * One immutable membership state event.
     *
     * @param id         event identifier
     * @param status     {@code active} or {@code revoked}
     * @param validFrom  instant from which the event is effective
     * @param validTo    instant at which the event stops being effective, or {@code null}
     * @param createdAt  instant the row was appended
     */
    public record MembershipEvent(String id, String status, String validFrom, String validTo, String createdAt) {
    }

    /**
     * One immutable role grant.
     *
     * @param id         grant identifier
     * @param roleKey    bounded role key
     * @param grantedBy  user identifier of the grantor in the active organization
     * @param validFrom  instant from which the grant is effective
     * @param validTo    instant at which the grant stops being effective, or {@code null}
 * @return this record
 */
public record RoleGrant(String id, String roleKey, String grantedBy, String validFrom, String validTo) {
    }
}
