package com.aurevia.staff;

import java.util.List;

/**
 * Read projection of one staff invitation in the active organization (IAM-02).
 *
 * <p>The raw token is never part of this projection: only the database stores its
 * hash, and the one-time raw value is returned solely by {@code StaffInvitationIssue}
 * at creation time. Status is folded from durable columns rather than stored as a
 * mutable command result.</p>
 *
 * @param id          opaque invitation identifier
 * @param subject     subject the invitation binds to
 * @param displayName human-readable label carried into the new user identity
 * @param facilityId  facility the membership is scoped to
 * @param roles       bounded role keys granted on acceptance
 * @param expiresAt   ISO instant after which the token is unusable
 * @param status      {@code pending}, {@code revoked} or {@code consumed}
 * @param invitedBy   subject of the tenant administrator who issued the invitation
 * @return this record
 */
public record StaffInvitation(String id, String subject, String displayName, String facilityId,
                              List<String> roles, String expiresAt, String status, String invitedBy) {

    /**
     * Freeze the role keys so the projection cannot be mutated through a caller.
     *
     * @throws NullPointerException when roles is null
     */
    public StaffInvitation {
        roles = List.copyOf(roles);
    }

    /**
     * Return the immutable role keys in stable order.
     *
     * @return role keys
     */
    @Override
    public List<String> roles() {
        return List.copyOf(roles);
    }
}
