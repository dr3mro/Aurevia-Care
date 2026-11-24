package com.aurevia.clinical.templates;

import java.util.Set;

/**
 * Caller identity for a template command: active organization, subject and roles.
 *
 * @return this record
 */
public record TemplateActor(String organizationId, String subject, Set<String> roles) {

    /**
     * Freeze the role set so authorization cannot change through the caller.
     *
     * @throws NullPointerException when roles is null
     */
    public TemplateActor {
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
    public static TemplateActor of(String organizationId, String subject, String... roles) {
        return new TemplateActor(organizationId, subject, Set.of(roles));
    }
}
