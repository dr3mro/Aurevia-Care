package com.aurevia.scheduling.followup;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aurevia.scheduling.SchedulingRepository;

/**
 * Membership source for follow-up authorization.
 *
 * <p>Delegates to the authoritative append-only membership fold so a deactivated
 * member is denied on the next follow-up command without waiting for token expiry.</p>
 */
@Component
public class FollowUpAuthorizationGuard implements FollowUpAuth {

    private final SchedulingRepository scheduling;

    /**
     * Create the adapter.
     *
     * @param scheduling tenant scheduling repository holding the membership fold
     */
    public FollowUpAuthorizationGuard(SchedulingRepository scheduling) {
        this.scheduling = scheduling;
    }
    /**
     * Check active membership using the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest effective membership is active
     */
    @Override
    public boolean hasActiveMembership(String subject) {
        return scheduling.hasActiveMembership(subject);
    }
    /**
     * List role keys currently in force for the subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    @Override
    public List<String> activeRoles(String subject) {
        return scheduling.activeRoles(subject);
    }
}
