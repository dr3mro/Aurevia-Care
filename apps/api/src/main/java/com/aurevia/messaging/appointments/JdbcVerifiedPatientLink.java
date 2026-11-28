package com.aurevia.messaging.appointments;

import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Durable verified-patient-link adapter backed by the identity-proofing fold.
 *
 * <p>The authoritative link is the database's {@code public.patient_link_active}
 * function over V33's append-only link events, read inside the tenant-guarded
 * transaction so forced RLS confines it to the active organization. No client
 * assertion or demographics can create a link, and a revocation takes effect on
 * the next protected request. A malformed patient identifier or a failed lookup
 * denies access instead of surfacing a server error.</p>
 */
@Repository
public class JdbcVerifiedPatientLink implements VerifiedPatientLink {

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transactions;

    /**
     * Create the verified-link adapter.
     *
     * @param jdbc          named-parameter operations for the active tenant
     * @param transactions  template that binds tenant context before each statement
     */
    public JdbcVerifiedPatientLink(NamedParameterJdbcOperations jdbc,
                                   TransactionOperations transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** {@inheritDoc} */
    @Override
    public boolean allows(String subject, String patientId) {
        // The identifier is client-supplied; an invalid UUID must not become a
        // 500, and a failed lookup must not be mistaken for a granted link.
        try {
            UUID patient = UUID.fromString(patientId);
            return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT public.patient_link_active(:subject, CAST(:patientId AS uuid))
                    """, new MapSqlParameterSource()
                            .addValue("subject", subject)
                            .addValue("patientId", patient.toString()), Boolean.class)));
        } catch (IllegalArgumentException | DataAccessException exception) {
            return false;
        }
    }
}
