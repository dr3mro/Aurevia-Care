package com.aurevia.pharmacyflow.inbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.pharmacyflow.inbox.PharmacyInboxContract.InboxItem;
import com.aurevia.prescription.rx.RxContract.State;

/**
 * The single SQL boundary for the pharmacy inbox read model (RX-02, XCH-01).
 *
 * <p>Every statement runs inside the tenant-guarded transaction, so forced row
 * level security confines it to the active organization. Routing is enforced by
 * an equality predicate on the pharmacy the authenticated subject is assigned to,
 * so an unrouted prescription (no destination pharmacy) and another pharmacy's
 * item are never visible even inside the same tenant. This repository reads the
 * existing prescription snapshot only; it never joins or reads a chart table.</p>
 */
@Repository
public class PharmacyInboxRepository {

    /** Approved policy key prefix binding a pharmacy staff subject to one pharmacy. */
    public static final String ASSIGNMENT_KEY_PREFIX = "pharmacy.inbox.assignment.";

    private static final String SELECT_ITEM = """
            SELECT id::text AS id, version, patient_id::text AS patient_id, medication, strength,
                   dose, route, frequency, quantity, state, submission_id::text AS submission_id,
                   prescriber_subject, destination_pharmacy, created_at
              FROM public.rx02_prescription
             WHERE destination_pharmacy = :pharmacy
            """;

    private static final RowMapper<InboxRow> ROW_MAPPER = PharmacyInboxRepository::mapRow;

    /**
     * One routed prescription together with its stable descending sort key.
     *
     * @param item    routed-prescription projection
     * @param sortKey canonical creation instant completing the ordering key
     */
    record InboxRow(InboxItem item, String sortKey) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public PharmacyInboxRepository(NamedParameterJdbcTemplate jdbc) {
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
     * List the role keys currently in force for a subject.
     *
     * @param subject authenticated subject
     * @return distinct active role keys
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Read the approved pharmacy assignment of a subject through the version fold.
     *
     * @param subject authenticated pharmacy staff subject
     * @return the assigned pharmacy key, or empty when no approved assignment exists
     */
    public Optional<String> assignedPharmacy(String subject) {
        return jdbc.query("SELECT value FROM public.effective_config(:key)",
                Map.of("key", ASSIGNMENT_KEY_PREFIX + subject),
                (resultSet, rowNumber) -> resultSet.getString("value")).stream().findFirst();
    }

    /**
     * Read one prescription only when it is explicitly routed to this pharmacy.
     *
     * @param id       prescription identifier
     * @param pharmacy assigned pharmacy key
     * @return the routed projection, or empty when the item is absent or not routed here
     */
    public Optional<InboxItem> findRouted(String id, String pharmacy) {
        return jdbc.query(SELECT_ITEM + " AND id = CAST(:id AS uuid)",
                new MapSqlParameterSource().addValue("pharmacy", pharmacy).addValue("id", id),
                ROW_MAPPER).stream().findFirst().map(InboxRow::item);
    }

    /**
     * Read one cursor-paged slice of prescriptions routed to this pharmacy.
     *
     * <p>Forced RLS confines the query to the active organization. The immutable
     * id breaks a creation-time tie, making descending paging stable. The caller
     * fetches one extra row to decide whether a next page exists.</p>
     *
     * @param pharmacy        assigned pharmacy key
     * @param status          optional exact prescription state, or {@code null}
     * @param cursorCreatedAt exclusive upper creation-time bound, or {@code null}
     * @param cursorId        immutable id that breaks a tie at the cursor instant
     * @param limit           maximum rows to fetch (one more than the page size)
     * @return routed rows in creation-time descending order with their sort key
     */
    public List<InboxRow> query(String pharmacy, String status, String cursorCreatedAt, String cursorId,
                                int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("pharmacy", pharmacy).addValue("limit", limit);
        StringBuilder sql = new StringBuilder(SELECT_ITEM);
        if (cursorCreatedAt != null) {
            sql.append("""
                        AND (created_at < CAST(:cursorCreatedAt AS timestamptz)
                             OR (created_at = CAST(:cursorCreatedAt AS timestamptz)
                                 AND id < CAST(:cursorId AS uuid)))
                       """);
            params.addValue("cursorCreatedAt", cursorCreatedAt).addValue("cursorId", cursorId);
        }
        if (status != null) {
            sql.append(" AND state = :state");
            params.addValue("state", status);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, ROW_MAPPER);
    }

    private static InboxRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        Instant createdAt = instant(resultSet, "created_at");
        InboxItem item = new InboxItem(resultSet.getString("id"), resultSet.getInt("version"),
                resultSet.getString("patient_id"), resultSet.getString("medication"),
                resultSet.getString("strength"), resultSet.getString("dose"), resultSet.getString("route"),
                resultSet.getString("frequency"), resultSet.getInt("quantity"),
                State.from(resultSet.getString("state")), resultSet.getString("submission_id"),
                resultSet.getString("prescriber_subject"), resultSet.getString("destination_pharmacy"),
                createdAt);
        return new InboxRow(item, createdAt.toString());
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
