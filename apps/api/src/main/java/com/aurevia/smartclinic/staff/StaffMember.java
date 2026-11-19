package com.aurevia.smartclinic.staff;

import java.util.List;

/**
 * Current effective projection of one staff member in the active organization.
 *
 * @param subject     stable authenticated subject of the staff member
 * @param displayName human-readable display name
 * @param status      {@code active} or {@code deactivated}, from the latest membership event
 * @param roles       role keys attached to the current membership, ordered by key
 */
public record StaffMember(String subject, String displayName, String status, List<String> roles) {

    /**
     * Freeze the role list so the projection cannot be mutated through a caller.
     *
     * @throws NullPointerException when roles is null
     */
    public StaffMember {
        roles = List.copyOf(roles);
    }

    /**
     * Return the immutable role list.
     *
     * @return roles in stable key order
     */
    @Override
    public List<String> roles() {
        return List.copyOf(roles);
    }
}
