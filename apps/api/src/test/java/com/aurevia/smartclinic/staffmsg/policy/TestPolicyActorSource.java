package com.aurevia.smartclinic.staffmsg.policy;

import java.util.List;
import java.util.Set;

/**
 * Synthetic, test-only fake for the authoritative actor fold.
 *
 * <p>The real adapter will read active roles and facility scope from the
 * tenant-guarded membership fold; this fake lets a test supply those derived
 * facts and count facility checks so guard ordering is observable.</p>
 */
final class TestPolicyActorSource implements MessagingPolicyActorSource {

    private final List<String> roles;
    private final Set<String> facilities;
    private int facilityChecks;
    private String lastFacilityChecked;

    TestPolicyActorSource(List<String> roles, Set<String> facilities) {
        this.roles = roles;
        this.facilities = facilities;
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        return roles;
    }

    @Override
    public boolean facilityWithinScope(String organizationId, String subject, String facilityCode) {
        facilityChecks++;
        lastFacilityChecked = facilityCode;
        return facilityCode != null && facilities.contains(facilityCode);
    }

    int facilityChecks() {
        return facilityChecks;
    }

    String lastFacilityChecked() {
        return lastFacilityChecked;
    }
}
