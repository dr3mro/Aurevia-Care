package com.aurevia.staffmsg.references;

import com.aurevia.staffmsg.references.ReferenceContract.ReferenceAudit;

/**
 * Port that appends an immutable, identifier-free fact about a reference action.
 *
 * <p>Facts carry only an opaque reference id, the actor subject, the server-
 * derived action and outcome. Target handles and subject lines are never passed
 * to an audit sink.</p>
 */
public interface ReferenceAuditor {

    /**
     * Append one audit fact.
     *
     * @param audit immutable fact to record
     */
    void append(ReferenceAudit audit);
}
