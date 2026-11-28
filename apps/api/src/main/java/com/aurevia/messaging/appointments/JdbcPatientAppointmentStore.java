package com.aurevia.messaging.appointments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant-owned JDBC store for the patient-facing appointment projection.
 *
 * <p>V103 owns the table and its forced row-level security. Every operation runs
 * inside {@code transactions}, whose tenant guard binds {@code app.current_org}
 * and {@code app.actor} after the transaction opens, so no manual organization
 * filter is needed for isolation and no process-local copy can survive a
 * restart. The patient identifier is still checked in every SQL predicate as
 * defence in depth: a caller cannot read another patient's row even if a link
 * check were misconfigured.</p>
 *
 * <p>Confirmation is a single compare-and-set on the version. The row count is
 * returned verbatim so the service can distinguish a committed confirmation
 * from a stale precondition; two concurrent confirmations cannot both win.</p>
 */
@Repository
public class JdbcPatientAppointmentStore implements PatientAppointmentStore {

    private static final RowMapper<PatientAppointment> MAPPER =
            JdbcPatientAppointmentStore::mapAppointment;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the store over the shared tenant-guarded JDBC boundaries.
     *
     * @param jdbc          named-parameter operations for the active tenant
     * @param transactions  template that binds tenant context before each statement
     */
    public JdbcPatientAppointmentStore(NamedParameterJdbcOperations jdbc,
                                       TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** {@inheritDoc} */
    @Override
    public List<PatientAppointment> forPatient(String patientId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, version, patient_id, facility_code, facility_time_zone,
                       starts_at, status, confirmation_enabled, confirmed_at
                  FROM public.patient_appointment_projection
                 WHERE patient_id = :patientId
                 ORDER BY starts_at, id
                """, new MapSqlParameterSource().addValue("patientId", patientId), MAPPER));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PatientAppointment> find(String appointmentId, String patientId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, version, patient_id, facility_code, facility_time_zone,
                       starts_at, status, confirmation_enabled, confirmed_at
                  FROM public.patient_appointment_projection
                 WHERE id = :appointmentId AND patient_id = :patientId
                """, new MapSqlParameterSource().addValue("appointmentId", appointmentId)
                        .addValue("patientId", patientId), MAPPER).stream().findFirst());
    }

    /** {@inheritDoc} */
    @Override
    public int confirm(String appointmentId, String patientId, int expectedVersion, Instant confirmedAt) {
        // The version predicate is the write precondition; a zero row count means
        // another confirmation or a status refresh won the race.
        return transactions.execute(status -> jdbc.update("""
                UPDATE public.patient_appointment_projection
                   SET version = version + 1, confirmed_at = CAST(:confirmedAt AS timestamptz)
                 WHERE id = :appointmentId AND patient_id = :patientId
                   AND version = :expectedVersion
                """, new MapSqlParameterSource()
                        .addValue("appointmentId", appointmentId)
                        .addValue("patientId", patientId)
                        .addValue("expectedVersion", expectedVersion)
                        .addValue("confirmedAt", OffsetDateTime.ofInstant(confirmedAt,
                                java.time.ZoneOffset.UTC))));
    }

    /**
     * Read one durable projection row. A null confirmation timestamp is a legitimate
     * unconfirmed appointment, not a corrupt record.
     *
     * @param resultSet result row
     * @param rowNumber zero-based row number
     * @return the immutable patient-facing projection
     * @throws SQLException when a column cannot be read
     */
    static PatientAppointment mapAppointment(ResultSet resultSet, int rowNumber) throws SQLException {
        OffsetDateTime confirmed = resultSet.getObject("confirmed_at", OffsetDateTime.class);
        return new PatientAppointment(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("facility_code"),
                resultSet.getString("facility_time_zone"),
                resultSet.getObject("starts_at", OffsetDateTime.class).toInstant(),
                AppointmentStatus.from(resultSet.getString("status")),
                resultSet.getBoolean("confirmation_enabled"),
                confirmed == null ? null : confirmed.toInstant());
    }
}
