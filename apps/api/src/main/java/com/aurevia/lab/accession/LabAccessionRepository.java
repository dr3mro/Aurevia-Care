package com.aurevia.lab.accession;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The single SQL boundary for laboratory accession (LAB-01, ARC-01, ARC-02).
 *
 * <p>V26 applies forced RLS to both tables, derives the author and history actor
 * from the transaction-local subject, and pins ownership columns on update. This
 * class only issues parameterized statements; it never decides authorization.</p>
 */
@Repository
public class LabAccessionRepository {

    private static final RowMapper<LabAccession> ACCESSION_MAPPER = LabAccessionRepository::mapAccession;
    private static final RowMapper<AccessionEvent> EVENT_MAPPER = LabAccessionRepository::mapEvent;
    private static final RowMapper<AccessionRow> LIST_MAPPER = LabAccessionRepository::mapListRow;

    /**
     * One page row: the accession projection plus its canonical creation instant.
     *
     * <p>The instant is read from the row rather than reconstructed by the service
     * so the opaque cursor always names the exact value the database sorted on.</p>
     *
     * @param accession the current accession projection
     * @param createdAt canonical UTC creation instant used as the cursor sort value
     */
    record AccessionRow(LabAccession accession, String createdAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public LabAccessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership using the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest effective membership is active
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List role keys currently in force for the subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Confirm a patient is visible in the active tenant.
     *
     * @param patientId opaque patient identifier
     * @return {@code true} when the patient exists under forced RLS
     */
    public boolean patientExists(String patientId) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:id AS uuid))
                """, Map.of("id", patientId), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Check whether an issuer-scoped accession number is already used in the tenant.
     *
     * @param issuer      accession issuer
     * @param accessionNo accession number
     * @return {@code true} when the pair already exists
     */
    public boolean accessionExists(String issuer, String accessionNo) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.lab_accession
                                WHERE accession_issuer = :issuer AND accession_no = :accessionNo)
                """, Map.of("issuer", issuer, "accessionNo", accessionNo), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Insert a new accession; the version and author are database-owned.
     *
     * @param organizationId active organization identifier
     * @param id             opaque specimen identifier
     * @param accessionNo    issuer-scoped accession number
     * @param accessionIssuer issuer of the accession number
     * @param patientId      verified patient record
     * @param sourceOrderRef bounded source order reference
     * @param state          initial lifecycle state
     * @param replacesId     prior specimen for a recollection, otherwise {@code null}
     * @return number of inserted rows
     */
    public int insert(String organizationId, String id, String accessionNo, String accessionIssuer,
                      String patientId, String sourceOrderRef, AccessionState state, String replacesId) {
        return jdbc.update("""
                INSERT INTO public.lab_accession
                    (organization_id, id, accession_no, accession_issuer, patient_id,
                     source_order_ref, state, replaces_accession_id)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :accessionNo, :issuer,
                        CAST(:patientId AS uuid), :sourceOrderRef, :state, CAST(:replacesId AS uuid))
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId).addValue("id", id)
                .addValue("accessionNo", accessionNo).addValue("issuer", accessionIssuer)
                .addValue("patientId", patientId).addValue("sourceOrderRef", sourceOrderRef)
                .addValue("state", state.wire()).addValue("replacesId", replacesId));
    }

    /**
     * Apply an optimistic-concurrency state and field update.
     *
     * @param organizationId       active organization identifier
     * @param id                   specimen identifier
     * @param expectedVersion      version read by the caller
     * @param state                next state
     * @param specimenType         specimen type code
     * @param siteCode             collection site code
     * @param collector            recorded collector
     * @param collectedAt          ISO-8601 collection instant
     * @param receivedAt           ISO-8601 receipt instant
     * @param quality              receipt quality code
     * @param rejectionReason      retained rejection reason
     * @param rejectionNotification retained rejection notification
     * @return number of updated rows; zero means the version is stale
     */
    public int update(String organizationId, String id, int expectedVersion, AccessionState state,
                      String specimenType, String siteCode, String collector, String collectedAt,
                      String receivedAt, String quality, String rejectionReason,
                      String rejectionNotification) {
        return jdbc.update("""
                UPDATE public.lab_accession
                   SET version = :nextVersion, state = :state, specimen_type = :specimenType,
                       site_code = :siteCode, collector = :collector,
                       collected_at = CAST(:collectedAt AS timestamptz),
                       received_at = CAST(:receivedAt AS timestamptz),
                       quality = :quality, rejection_reason = :rejectionReason,
                       rejection_notification = :rejectionNotification
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                   AND version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId).addValue("id", id)
                .addValue("expectedVersion", expectedVersion).addValue("nextVersion", expectedVersion + 1)
                .addValue("state", state.wire()).addValue("specimenType", specimenType)
                .addValue("siteCode", siteCode).addValue("collector", collector)
                .addValue("collectedAt", collectedAt).addValue("receivedAt", receivedAt)
                .addValue("quality", quality).addValue("rejectionReason", rejectionReason)
                .addValue("rejectionNotification", rejectionNotification));
    }

    /**
     * Find one accession visible in the active tenant.
     *
     * @param id opaque specimen identifier
     * @return the current projection, or empty when concealed
     */
    public Optional<LabAccession> findById(String id) {
        return jdbc.query("""
                SELECT id::text, version, accession_no, accession_issuer, patient_id::text,
                       source_order_ref, state, specimen_type, site_code, collector,
                       collected_at::text, received_at::text, quality, rejection_reason,
                       rejection_notification, replaces_accession_id::text
                  FROM public.lab_accession WHERE id = CAST(:id AS uuid)
                """, Map.of("id", id), ACCESSION_MAPPER).stream().findFirst();
    }

    /**
     * Read one cursor-paged slice of the active tenant's accessions.
     *
     * <p>Forced RLS confines the query to the active organization, so no manual
     * organization predicate is added. The immutable id breaks a creation-time
     * tie, making descending paging stable. The caller fetches one extra row to
     * decide whether a next page exists.</p>
     *
     * @param cursorCreatedAt exclusive upper creation-time bound, or {@code null}
     * @param cursorId        immutable id that breaks a tie at the cursor instant
     * @param state           optional exact lifecycle state filter
     * @param limit           maximum rows to fetch (one more than the page size)
     * @return accessions in creation-time descending order with their sort instant
     */
    public List<AccessionRow> query(String cursorCreatedAt, String cursorId, String state, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                SELECT id::text, version, accession_no, accession_issuer, patient_id::text,
                       source_order_ref, state, specimen_type, site_code, collector,
                       collected_at::text, received_at::text, quality, rejection_reason,
                       rejection_notification, replaces_accession_id::text, created_at
                  FROM public.lab_accession
                 WHERE 1=1
                """);
        if (cursorCreatedAt != null) {
            sql.append("""
                        AND (created_at < CAST(:cursorCreatedAt AS timestamptz)
                             OR (created_at = CAST(:cursorCreatedAt AS timestamptz)
                                 AND id < CAST(:cursorId AS uuid)))
                       """);
            params.addValue("cursorCreatedAt", cursorCreatedAt).addValue("cursorId", cursorId);
        }
        if (state != null) {
            sql.append(" AND state = :state");
            params.addValue("state", state);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, LIST_MAPPER);
    }

    /**
     * Append one immutable history event inside the caller's transaction.
     *
     * @param accessionId      specimen identifier
     * @param version          revision the event commits with
     * @param action           stable action key
     * @param fromState        prior state, or {@code null}
     * @param toState          resulting state
     * @param reason           rejection reason, or {@code null}
     * @param matchedPatientId patient identity verified at this step, or {@code null}
     */
    public void appendEvent(String accessionId, int version, String action, AccessionState fromState,
                            AccessionState toState, String reason, String matchedPatientId) {
        jdbc.update("""
                INSERT INTO public.lab_accession_event
                    (organization_id, accession_id, accession_version, action, from_state,
                     to_state, reason, matched_patient_id)
                VALUES (public.app_org(), CAST(:accessionId AS uuid), :version, :action,
                        :fromState, :toState, :reason, CAST(:matchedPatientId AS uuid))
                """, new MapSqlParameterSource()
                .addValue("accessionId", accessionId).addValue("version", version)
                .addValue("action", action)
                .addValue("fromState", fromState == null ? null : fromState.wire())
                .addValue("toState", toState.wire()).addValue("reason", reason)
                .addValue("matchedPatientId", matchedPatientId));
    }

    /**
     * Read the append-only history of one accession.
     *
     * @param accessionId specimen identifier
     * @return events in occurrence order
     */
    public List<AccessionEvent> history(String accessionId) {
        return jdbc.query("""
                SELECT id::text, accession_id::text, accession_version, action, from_state,
                       to_state, reason, matched_patient_id::text, occurred_at::text
                  FROM public.lab_accession_event WHERE accession_id = CAST(:id AS uuid)
                 ORDER BY occurred_at, id
                """, Map.of("id", accessionId), EVENT_MAPPER);
    }

    private static AccessionRow mapListRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String createdAt = resultSet.getObject("created_at", OffsetDateTime.class).toInstant().toString();
        return new AccessionRow(mapAccession(resultSet, rowNumber), createdAt);
    }

    private static LabAccession mapAccession(ResultSet resultSet, int rowNumber) throws SQLException {
        String state = resultSet.getString("state");
        return new LabAccession(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("accession_no"), resultSet.getString("accession_issuer"),
                resultSet.getString("patient_id"), resultSet.getString("source_order_ref"),
                AccessionState.valueOf(state.toUpperCase(Locale.ROOT)),
                resultSet.getString("specimen_type"), resultSet.getString("site_code"),
                resultSet.getString("collector"), resultSet.getString("collected_at"),
                resultSet.getString("received_at"), resultSet.getString("quality"),
                resultSet.getString("rejection_reason"), resultSet.getString("rejection_notification"),
                resultSet.getString("replaces_accession_id"));
    }

    private static AccessionEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AccessionEvent(resultSet.getString("id"), resultSet.getString("accession_id"),
                resultSet.getInt("accession_version"), resultSet.getString("action"),
                resultSet.getString("from_state"), resultSet.getString("to_state"),
                resultSet.getString("reason"), resultSet.getString("matched_patient_id"),
                resultSet.getString("occurred_at"));
    }
}
