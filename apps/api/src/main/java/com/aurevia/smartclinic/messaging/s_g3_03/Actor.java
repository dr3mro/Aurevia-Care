package com.aurevia.smartclinic.messaging.s_g3_03;

import java.util.Set;

/**
 * Caller identity for a patient-access command: active organization, subject and roles.
 *
 * <p>The integrating caller must derive this snapshot from active membership on
 * every protected request (IAM-01, IAM-02). A patient subject is the verified
 * account owner, never a demographic claim.</p>
 *
 * @param organizationId active organization identifier
 * @param subject        authenticated subject
 * @param roles          role assignments in the active organization
 */
public record Actor(String organizationId, String subject, Set<String> roles) {

    /**
     * Freeze the role set so authorization cannot change through the caller.
     *
     * @throws NullPointerException when roles is null
     */
    public Actor {
        roles = Set.copyOf(roles);
    }

    /**
     * Return the immutable active roles.
     *
     * @return active role assignments
     */
    @Override
    public Set<String> roles() {
        return Set.copyOf(roles);
    }

    /**
     * Build an actor from role names.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated subject
     * @param roles          role assignments in the active organization
     * @return the actor
     */
    public static Actor of(String organizationId, String subject, String... roles) {
        return new Actor(organizationId, subject, Set.of(roles));
    }
}
