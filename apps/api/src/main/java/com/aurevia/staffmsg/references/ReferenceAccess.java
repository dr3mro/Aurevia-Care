package com.aurevia.staffmsg.references;

import com.aurevia.staffmsg.references.ReferenceContract.AccessOutcome;
import com.aurevia.staffmsg.references.ReferenceContract.ReferenceKind;

/**
 * Port that re-runs the recipient's normal access check when a reference is opened.
 *
 * <p>The check is keyed to the authenticated tenant and subject, never to a
 * value supplied by the caller. Implementations conceal cross-tenant targets and
 * report revoked or stale connections as denied so the domain service can keep a
 * single blocked-state rule.</p>
 */
public interface ReferenceAccess {

    /**
     * Decide whether a recipient may open a reference target.
     *
     * @param organizationId active organization from the tenant context
     * @param subject        authenticated recipient subject
     * @param kind           business meaning of the target
     * @param targetId       opaque target handle
     * @param connectionId   opaque patient connection handle
     * @return the server-derived access outcome
     */
    AccessOutcome check(String organizationId, String subject, ReferenceKind kind,
                        String targetId, String connectionId);
}
