package com.aurevia.messaging.appointments;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * Durable audit adapter for patient self-access decisions.
 *
 * <p>Each allow or deny fact is appended through the shared {@link com.aurevia.audit.AuditAppender}
 * inside the tenant-guarded transaction, so the V3 trigger derives the actor
 * from the transaction-local binding instead of trusting the caller-supplied
 * subject. The provider token is never passed to this sink. A denial is written
 * before the failure is returned, so it survives a restart as evidence.</p>
 */
@Component
public class PatientAppointmentAuditAppender implements AuditSink {

    private final AuditAppender audit;
    private final TransactionOperations transactions;

    /**
     * Create the audit adapter.
     *
     * @param audit         shared append-only audit event writer
     * @param transactions  template that binds tenant context before the append
     */
    public PatientAppointmentAuditAppender(AuditAppender audit, TransactionOperations transactions) {
        this.audit = audit;
        this.transactions = transactions;
    }

    /** {@inheritDoc} */
    @Override
    public void append(String subject, String patientId, String action, String outcome, Instant at) {
        TenantContext context = Objects.requireNonNull(TenantContext.current(),
                "tenant context required for a patient audit fact");
        transactions.executeWithoutResult(status -> audit.append(context,
                "PATIENT_APPOINTMENT", action, "PATIENT_APPOINTMENT", "PATIENT", patientId, outcome));
    }
}
