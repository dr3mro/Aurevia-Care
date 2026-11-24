package com.aurevia.staff;

import java.util.List;

/**
 * Current effective projection of one staff member in the active organization.
 *
 * @return this record
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
