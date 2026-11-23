package com.aurevia.smartclinic.staffmsg.policy;

import java.util.List;

import com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.PolicyRevision;
import com.aurevia.smartclinic.staffmsg.policy.MessagingPolicyContract.PolicyScope;

/**
 * Narrow persistence boundary for append-only messaging policy revisions.
 *
 * <p>An implementation must bind the active tenant and read inside the caller's
 * tenant-guarded transaction; an unset context must yield no rows rather than
 * a cross-tenant result. Revisions are immutable, so a change is an append and
 * no update or delete path is exposed. An implementation must enforce a unique
 * {@code (organizationId, scope, facilityCode, version)} constraint and bind the
 * duplicate check and the append into one tenant-bound transaction, so two
 * concurrent edits cannot both allocate the same version. No production
 * adapter is part of this kernel slice; the database adapter and its row-level
 * security migration are a follow-up task.</p>
 */
public interface MessagingPolicyHistory {

    /**
     * Read the revisions for one tenant, scope and facility.
     *
     * @param organizationId active organization identifier, never a request field
     * @param scope          tenant or facility scope
     * @param facilityCode   facility code for facility scope, otherwise {@code null}
     * @return revisions visible in the active tenant, in any order
     */
    List<PolicyRevision> revisions(String organizationId, PolicyScope scope, String facilityCode);

    /**
     * Append one immutable revision.
     *
     * @param revision revision to persist in the active tenant
     */
    void append(PolicyRevision revision);
}
