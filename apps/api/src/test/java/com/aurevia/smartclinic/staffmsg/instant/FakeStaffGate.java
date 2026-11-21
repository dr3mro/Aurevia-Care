package com.aurevia.smartclinic.staffmsg.instant;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.StaffIdentity;

/**
 * Test-only authorization port populated with synthetic staff of a synthetic
 * organization and facility. No real identity or credential is involved.
 */
final class FakeStaffGate implements StaffGate {

    private final Map<String, StaffIdentity> identities = new HashMap<>();

    /**
     * Register a synthetic active staff member.
     *
     * @param organizationId synthetic organization identifier
     * @param subject        synthetic subject
     * @param facilityId     synthetic facility identifier
     * @param roleKey        synthetic active role key
     */
    void register(String organizationId, String subject, String facilityId, String roleKey) {
        identities.put(key(organizationId, subject), new StaffIdentity(subject, facilityId, roleKey));
    }

    @Override
    public Optional<StaffIdentity> activeStaff(String organizationId, String subject) {
        return Optional.ofNullable(identities.get(key(organizationId, subject)));
    }

    private static String key(String organizationId, String subject) {
        return organizationId + "\u0000" + subject;
    }
}
