package com.aurevia.smartclinic.staffmsg.instant;

import java.util.Optional;

import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.StaffIdentity;

/**
 * Authorization port for instant staff messaging (IAM-01, ARC-07).
 *
 * <p>Every conversation operation resolves the acting subject through this port
 * on each request. An empty result means the subject has no active membership
 * of the active organization and must be refused; the caller never trusts a
 * facility or role supplied in the request body.</p>
 */
public interface StaffGate {

    /**
     * Resolve the active staff identity of a subject in one organization.
     *
     * @param organizationId active organization identifier
     * @param subject        authenticated staff subject
     * @return the active identity, or empty when the subject may not take part
     */
    Optional<StaffIdentity> activeStaff(String organizationId, String subject);
}
