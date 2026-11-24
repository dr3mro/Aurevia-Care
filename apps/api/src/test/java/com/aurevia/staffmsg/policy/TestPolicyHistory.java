package com.aurevia.staffmsg.policy;

import static com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyRevision;
import static com.aurevia.staffmsg.policy.MessagingPolicyContract.PolicyScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Synthetic, test-only fake for the revision history port.
 *
 * <p>It reproduces the tenant/scope/facility filter a future database adapter
 * must apply and records each lookup so a test can prove which keys were used.
 * It lives only under {@code src/test}; production durability is a separate,
 * task-requested adapter.</p>
 */
final class TestPolicyHistory implements MessagingPolicyHistory {

    /** One recorded port lookup, used to assert tenant and facility filtering. */
    record Lookup(String organizationId, PolicyScope scope, String facilityCode) {
    }

    private final List<PolicyRevision> revisions = new ArrayList<>();
    private final List<Lookup> lookups = new ArrayList<>();

    @Override
    public List<PolicyRevision> revisions(String organizationId, PolicyScope scope, String facilityCode) {
        lookups.add(new Lookup(organizationId, scope, facilityCode));
        return revisions.stream()
                .filter(revision -> revision.organizationId().equals(organizationId))
                .filter(revision -> revision.scope() == scope)
                .filter(revision -> Objects.equals(revision.facilityCode(), facilityCode))
                .toList();
    }

    @Override
    public void append(PolicyRevision revision) {
        revisions.add(revision);
    }

    /**
     * Seed a revision directly, bypassing service validation.
     *
     * @param revision synthetic revision to add
     */
    void seed(PolicyRevision revision) {
        revisions.add(revision);
    }

    List<Lookup> lookups() {
        return List.copyOf(lookups);
    }

    int size() {
        return revisions.size();
    }
}
