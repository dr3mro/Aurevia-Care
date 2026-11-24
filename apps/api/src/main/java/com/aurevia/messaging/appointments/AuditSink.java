package com.aurevia.messaging.appointments;

import java.time.Instant;

/**
 * Append-only audit boundary for patient self-access (PAT-01, OPS-02).
 * The adapter must derive the actor from the transaction-local actor binding;
 * neither the subject string passed here nor any token value is authoritative.
 */
public interface AuditSink {

    /**
     * Append one allow or deny event in the caller's transaction.
     *
     * @param subject   authenticated subject from the tenant context
     * @param patientId patient identifier in scope
     * @param action    patient-specific action key
     * @param outcome   {@code SUCCESS} or {@code DENIED}
     * @param at        server instant of the event
     */
    void append(String subject, String patientId, String action, String outcome, Instant at);
}
