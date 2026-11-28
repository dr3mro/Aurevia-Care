package com.aurevia.staffmsg.policy;

import java.util.List;

/**
 * Contract for authoritative actor facts that an authorization gate may consume.
 *
 * <p>Both reads must come from the tenant-guarded membership and role fold of
 * the active organization, never from a client-supplied role or facility
 * field. An implementation must fail closed when the tenant context is unset
 * or the subject is not an active member. This type declares the boundary;
 * callers must supply a fold-backed implementation, and no such implementation
 * is wired here.</p>
 */
public interface MessagingPolicyActorSource {

    /**
     * Read the role keys currently effective for a subject.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated subject from the tenant context
     * @return the effective role keys, empty when the subject has none
     */
    List<String> activeRoles(String organizationId, String subject);

    /**
     * Decide whether a facility is inside the subject's authorized scope.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated subject from the tenant context
     * @param facilityCode   tenant-local facility code being edited
     * @return {@code true} only when the facility is within the subject's scope
     */
    boolean facilityWithinScope(String organizationId, String subject, String facilityCode);
}
