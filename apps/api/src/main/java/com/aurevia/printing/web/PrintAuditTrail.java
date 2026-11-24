package com.aurevia.printing.web;

/**
 * Durable sink for sanitized print audit facts (OPS-02).
 *
 * <p>The narrow interface lets the HTTP layer record an outcome without knowing
 * how OPS-02 persistence works, and lets tests capture the exact facts that
 * would be written. Implementations must never persist handout content.</p>
 */
public interface PrintAuditTrail {

    /**
     * Append one immutable print fact inside the caller's transaction.
     *
     * @param entry sanitized audit facts, never handout content
     */
    void record(PrintAuditEntry entry);
}
