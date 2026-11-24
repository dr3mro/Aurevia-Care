package com.aurevia.scheduling.followup;

import java.util.List;

/**
 * Re-derived membership source for follow-up commands (CLN-06, IAM-02).
 *
 * <p>An adapter reads the append-only membership fold on every protected
 * request, so deactivation denies the next command without token expiry.</p>
 */
public interface FollowUpAuth {

    /**
     * Check active membership for the authenticated subject.
     *
     * @param subject authenticated subject
     * @return {@code true} when the subject has active membership
     */
    boolean hasActiveMembership(String subject);

    /**
     * Read the currently assigned roles.
     *
     * @param subject authenticated subject
     * @return active role keys
     */
    List<String> activeRoles(String subject);
}
