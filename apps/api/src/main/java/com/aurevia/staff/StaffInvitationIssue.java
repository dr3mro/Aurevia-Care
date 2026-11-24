package com.aurevia.staff;

import java.util.List;

/**
 * The one-time response to issuing a staff invitation (IAM-02).
 *
 * <p>Carries the raw token, which is never stored and cannot be recovered later.
 * Only the database holds its hash. The caller must hand the token to the invitee
 * over an approved channel; it is not present in any list or history projection.</p>
 *
 * @param id        opaque invitation identifier used by revoke
 * @param subject   subject the invitation binds to
 * @param token     one-time raw token, returned only here
 * @param expiresAt ISO instant when the invitation stops being usable
 * @param roles     bounded role keys that acceptance will grant
 * @return this record
 */
public record StaffInvitationIssue(String id, String subject, String token, String expiresAt,
                                   List<String> roles) {

    /**
     * Freeze the role keys so the issue response cannot be mutated through a caller.
     *
     * @throws NullPointerException when roles is null
     */
    public StaffInvitationIssue {
        roles = List.copyOf(roles);
    }

    /**
     * Return the immutable role keys.
     *
     * @return role keys
     */
    @Override
    public List<String> roles() {
        return List.copyOf(roles);
    }
}
