package com.aurevia.scheduling.followup;

import java.util.List;

import org.springframework.stereotype.Component;

import com.aurevia.scheduling.SchedulingRepository;

/**
 * Membership source for follow-up authorization (CLN-06, IAM-02).
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

    @Override
    public boolean hasActiveMembership(String subject) {
        return scheduling.hasActiveMembership(subject);
    }

    @Override
    public List<String> activeRoles(String subject) {
        return scheduling.activeRoles(subject);
    }
}
