package com.aurevia.prescription.rx;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.prescription.rx.RxContract.Event;
import com.aurevia.prescription.rx.RxContract.EventType;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.State;

/**
 * The single SQL boundary for prescription transmission.
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced row
 * level security confines it to the active organization. The event ledger is
 * append-only for every role and the actor is always derived from the
 * transaction's {@code app.actor} binding, never from caller input. The single
 * logical submission is a row inserted once and reused by every retry, so a
 * reconciliation can never create a second downstream prescription.</p>
 */
@Repository
public class RxRepository {

    private static final String SELECT_PRESCRIPTION = """
            SELECT id::text AS id, version, patient_id::text AS patient_id, medication, strength,
                   dose, route, frequency, quantity, prescriber_subject, destination_pharmacy,
                   state, channel, submission_id::text AS submission_id, retry_allowed, created_at
              FROM public.prescription_transmission
            """;
    private static final String SELECT_EVENT = """
            SELECT id::text AS id, prescription_id::text AS prescription_id, prescription_version,
                   event_type, actor_id::text AS actor_id, channel, submission_id::text AS submission_id,
                   outcome, detail, occurred_at
              FROM public.prescription_event
            """;

    private static final RowMapper<Prescription> PRESCRIPTION_MAPPER = RxRepository::mapPrescription;
    private static final RowMapper<Event> EVENT_MAPPER = RxRepository::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public RxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership with the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List the roles currently in force for a subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Read one approved site configuration value through the version fold.
     *
     * @param key tenant-local policy key
     * @return the effective value, or empty when no approved policy exists
     */
    public Optional<String> approvedSetting(String key) {
        return jdbc.query("SELECT value FROM public.effective_config(:key)", Map.of("key", key),
                (resultSet, rowNumber) -> resultSet.getString("value")).stream().findFirst();
    }

    /**
     * Check that a patient is visible in the active organization.
     *
     * @param patientId patient identifier
     * @return {@code true} when the patient exists in this tenant
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                """, Map.of("patientId", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert a transmission record referencing a prescription draft id.
     *
     * @param id prescription draft identifier used as the local id
     * @param patientId          owning patient
     * @param medication         medication name
     * @param strength           medication strength
     * @param dose               dose per administration
     * @param route              administration route
     * @param frequency          administration frequency
     * @param quantity           prescribed quantity
     * @param prescriberSubject  prescribing clinician subject
     * @param destinationPharmacy destination pharmacy key, or {@code null}
     * @return number of inserted rows
     */
    public int insert(String id, String patientId, String medication, String strength, String dose,
                      String route, String frequency, int quantity, String prescriberSubject,
                      String destinationPharmacy) {
        return jdbc.update("""
                INSERT INTO public.prescription_transmission
                    (organization_id, id, version, patient_id, medication, strength, dose, route,
                     frequency, quantity, prescriber_subject, destination_pharmacy, state)
                VALUES (public.app_org(), CAST(:id AS uuid), 1, CAST(:patientId AS uuid),
                        :medication, :strength, :dose, :route, :frequency, :quantity,
                        :prescriberSubject, :destinationPharmacy, 'draft')
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("patientId", patientId).addValue("medication", medication)
                .addValue("strength", strength).addValue("dose", dose).addValue("route", route)
                .addValue("frequency", frequency).addValue("quantity", quantity)
                .addValue("prescriberSubject", prescriberSubject)
                .addValue("destinationPharmacy", destinationPharmacy));
    }

    /**
     * Insert the single logical submission for a stable submission id.
     *
     * <p>The conflict clause makes a retry a no-op on the send ledger: the
     * submission row is created once and never replaced.</p>
     *
     * @param submissionId stable submission id
     * @param prescriptionId owning prescription
     * @param channel actual routing channel
     * @return number of inserted rows; zero means the submission already exists
     */
    public int insertSubmission(String submissionId, String prescriptionId, String channel) {
        return jdbc.update("""
                INSERT INTO public.prescription_submission
                    (organization_id, submission_id, prescription_id, channel)
                VALUES (public.app_org(), CAST(:submissionId AS uuid), CAST(:prescriptionId AS uuid), :channel)
                ON CONFLICT (organization_id, submission_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("submissionId", submissionId)
                .addValue("prescriptionId", prescriptionId).addValue("channel", channel));
    }

    /**
     * Read one prescription.
     *
     * @param id prescription identifier
     * @return the prescription when visible
     */
    public Optional<Prescription> find(String id) {
        return jdbc.query(SELECT_PRESCRIPTION + " WHERE id = CAST(:id AS uuid)",
                Map.of("id", id), PRESCRIPTION_MAPPER).stream().findFirst();
    }

    /**
     * Read one prescription, locking its row for the current transaction.
     *
     * @param id prescription identifier
     * @return the locked prescription when visible
     */
    public Optional<Prescription> loadForUpdate(String id) {
        return jdbc.query(SELECT_PRESCRIPTION + " WHERE id = CAST(:id AS uuid) FOR UPDATE",
                Map.of("id", id), PRESCRIPTION_MAPPER).stream().findFirst();
    }

    /**
     * Record prescriber attestation.
     *
     * @param id             prescription identifier
     * @param expectedVersion version read by the caller
     * @return number of updated rows; zero means the version moved
     */
    public int attest(String id, int expectedVersion) {
        return setState(id, expectedVersion, State.ATTESTED);
    }

    /**
     * Record an electronic transmission and its stable submission id.
     *
     * @param id             prescription identifier
     * @param expectedVersion version read by the caller
     * @param channel        actual channel
     * @param submissionId   stable submission id
     * @param state          resulting state
     * @return number of updated rows; zero means the version moved
     */
    public int transmit(String id, int expectedVersion, String channel, String submissionId, State state) {
        return jdbc.update("""
                UPDATE public.prescription_transmission
                   SET state = :state, channel = :channel, submission_id = CAST(:submissionId AS uuid),
                       version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("state", state.wire())
                .addValue("channel", channel).addValue("submissionId", submissionId));
    }

    /**
     * Record a locally printable prescription. No submission id is created.
     *
     * @param id             prescription identifier
     * @param expectedVersion version read by the caller
     * @param channel        actual channel
     * @return number of updated rows; zero means the version moved
     */
    public int transmitPrinted(String id, int expectedVersion, String channel) {
        return jdbc.update("""
                UPDATE public.prescription_transmission
                   SET state = 'printed', channel = :channel, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("channel", channel));
    }

    /**
     * Record the outcome of partner-status reconciliation.
     *
     * @param id             prescription identifier
     * @param expectedVersion version read by the caller
     * @param retryAllowed   whether a verified-absent status authorizes a retry
     * @param state          resulting state
     * @return number of updated rows; zero means the version moved
     */
    public int reconcile(String id, int expectedVersion, boolean retryAllowed, State state) {
        return jdbc.update("""
                UPDATE public.prescription_transmission
                   SET state = :state, retry_allowed = :retryAllowed, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("state", state.wire())
                .addValue("retryAllowed", retryAllowed));
    }

    /**
     * Reuse the original submission id for a retry without creating a new send.
     *
     * @param id             prescription identifier
     * @param expectedVersion version read by the caller
     * @param state          resulting state, which may itself be ambiguous
     * @return number of updated rows; zero means the version moved
     */
    public int retrySubmission(String id, int expectedVersion, State state) {
        return jdbc.update("""
                UPDATE public.prescription_transmission
                   SET state = :state, retry_allowed = false, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("state", state.wire()));
    }

    /**
     * Advance the version for a fact-only transition such as renewal.
     *
     * @param id             prescription identifier
     * @param expectedVersion version read by the caller
     * @return number of updated rows; zero means the version moved
     */
    public int bumpVersion(String id, int expectedVersion) {
        return jdbc.update("""
                UPDATE public.prescription_transmission SET version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion));
    }

    /**
     * Move a prescription to a single target state.
     *
     * @param id             prescription identifier
     * @param expectedVersion version read by the caller
     * @param state          target state
     * @return number of updated rows; zero means the version moved
     */
    public int setState(String id, int expectedVersion, State state) {
        return jdbc.update("""
                UPDATE public.prescription_transmission SET state = :state, version = version + 1
                 WHERE id = CAST(:id AS uuid) AND version = :expectedVersion
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("state", state.wire()));
    }

    /**
     * Count submission facts recorded for a stable submission id.
     *
     * @param submissionId stable submission id
     * @return number of submitted or retry facts
     */
    public int submissionAttempts(String submissionId) {
        // COUNT(*) always yields one non-null row; fail closed if a driver ever
        // violates that contract instead of unboxing null.
        Integer attempts = jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.prescription_event
                 WHERE submission_id = CAST(:submissionId AS uuid) AND event_type = 'submitted'
                """, Map.of("submissionId", submissionId), Integer.class);
        return Objects.requireNonNull(attempts, "submissionAttempts aggregate returned no row");
    }

    /**
     * Append one immutable fact to the transmission ledger.
     *
     * @param prescriptionId owning prescription
     * @param version        revision the fact is bound to
     * @param eventType      fact type
     * @param channel        channel used, or {@code null}
     * @param submissionId   stable submission id, or {@code null}
     * @param outcome        recorded outcome, or {@code null}
     * @param detail         non-sensitive detail, or {@code null}
     */
    public void appendEvent(String prescriptionId, int version, EventType eventType, String channel,
                            String submissionId, String outcome, String detail) {
        jdbc.update("""
                INSERT INTO public.prescription_event
                    (organization_id, prescription_id, prescription_version, event_type,
                     channel, submission_id, outcome, detail)
                VALUES (public.app_org(), CAST(:prescriptionId AS uuid), :version, :eventType,
                        :channel, CAST(:submissionId AS uuid), :outcome, :detail)
                """, new MapSqlParameterSource().addValue("prescriptionId", prescriptionId)
                .addValue("version", version).addValue("eventType", eventType.wire())
                .addValue("channel", channel).addValue("submissionId", submissionId)
                .addValue("outcome", outcome).addValue("detail", detail));
    }

    /**
     * Read the full event trail of one prescription in chronological order.
     *
     * @param prescriptionId prescription identifier
     * @return immutable, ordered events
     */
    public List<Event> events(String prescriptionId) {
        return jdbc.query(SELECT_EVENT + " WHERE prescription_id = CAST(:prescriptionId AS uuid)"
                        + " ORDER BY occurred_at, id",
                Map.of("prescriptionId", prescriptionId), EVENT_MAPPER);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Prescription mapPrescription(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Prescription(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("medication"),
                resultSet.getString("strength"), resultSet.getString("dose"), resultSet.getString("route"),
                resultSet.getString("frequency"), resultSet.getInt("quantity"),
                resultSet.getString("prescriber_subject"), resultSet.getString("destination_pharmacy"),
                State.from(resultSet.getString("state")), resultSet.getString("channel"),
                resultSet.getString("submission_id"), resultSet.getBoolean("retry_allowed"),
                instant(resultSet, "created_at"));
    }

    private static Event mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Event(resultSet.getString("id"), resultSet.getString("prescription_id"),
                resultSet.getInt("prescription_version"), EventType.from(resultSet.getString("event_type")),
                resultSet.getString("actor_id"), resultSet.getString("channel"),
                resultSet.getString("submission_id"), resultSet.getString("outcome"),
                resultSet.getString("detail"), instant(resultSet, "occurred_at"));
    }
}
