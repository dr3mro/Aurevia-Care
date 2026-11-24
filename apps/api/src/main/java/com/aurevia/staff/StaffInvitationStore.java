package com.aurevia.staff;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for single-use staff invitations (IAM-02, ARC-02).
 *
 * <p>Every method runs inside the tenant-guarded transaction. Forced row-level
 * security confines each statement to the active organization, so no raw token
 * or cross-tenant identifier is accepted from a caller. The raw token is hashed
 * inside the database and only the hash is persisted.</p>
 */
public interface StaffInvitationStore {

    /**
     * Append a pending invitation; the database stores only the token hash.
     *
     * @param organizationId active organization identifier
     * @param id             opaque invitation identifier
     * @param subject        invited subject
     * @param displayName    invited display name
     * @param facilityId     facility identifier
     * @param roles          bounded role keys
     * @param invitedBy      subject of the inviting administrator
     * @param token          one-time raw token, never stored
     * @param expiresAt      instant after which the token is unusable
     * @return number of inserted rows
     */
    int insert(String organizationId, String id, String subject, String displayName, String facilityId,
               List<String> roles, String invitedBy, String token, Instant expiresAt);

    /**
     * List invitations that are still pending in the active organization.
     *
     * @return pending invitation projections, oldest first
     */
    List<StaffInvitation> findPending();

    /**
     * Revoke an unused invitation if it is visible and still pending.
     *
     * @param id opaque invitation identifier
     * @return the revoked projection, or empty when the invitation is not pending
     */
    Optional<StaffInvitation> revoke(String id);

    /**
     * Atomically consume an invitation and create the membership and role grants.
     *
     * @param token one-time raw token
     * @return invited subject on success, or empty for any unusable token
     */
    Optional<String> accept(String token);
}
