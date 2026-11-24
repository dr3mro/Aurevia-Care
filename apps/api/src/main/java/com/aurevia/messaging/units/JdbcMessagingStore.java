package com.aurevia.messaging.units;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.messaging.units.MessagingContract.ConnectionContext;
import com.aurevia.messaging.units.MessagingContract.ConnectionState;
import com.aurevia.messaging.units.MessagingContract.EligibleRelationship;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;

/**
 * PostgreSQL-backed persistence boundary for the MSG-01 / ARC-07 care-unit
 * directory and verified connections.
 *
 * <p>V60 owns the four tenant tables and their forced row-level security. Every
 * operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org()} and {@code app.actor} after the transaction opens;
 * forced RLS therefore confines each statement to the active organization and
 * no manual organization predicate is needed on reads. The {@code organizationId}
 * argument on each method identifies that same active tenant and is written
 * explicitly on inserts so the policy's {@code WITH CHECK} rejects a mismatch.</p>
 *
 * <p>Membership, roles, verified chart links and patient existence are owned by
 * the tenancy, staff and patient modules. This adapter reads their authoritative
 * folds ({@code has_active_membership}, {@code active_roles},
 * {@code current_chart_links} and {@code patient_record}) rather than projecting
 * a second copy, so a deactivation or unlink takes effect on the next request.</p>
 */
@Repository
public class JdbcMessagingStore implements MessagingStore {

    private static final String CARE_UNIT_COLUMNS = """
            id::text, organization_id::text, facility_id::text, name, topics, active, unit_key
            """;
    private static final String RELATIONSHIP_COLUMNS = """
            r.id::text, r.organization_id::text, r.patient_id::text, r.unit_id::text,
            r.context, r.reference, r.active, r.consumed, r.expires_at
            """;
    private static final String CONNECTION_COLUMNS = """
            id::text, organization_id::text, patient_id::text, unit_id::text, context,
            context_reference, relationship_id::text, state, verified_by, verified_at,
            created_at, revoked_at
            """;
    private static final String AUDIT_COLUMNS = """
            organization_id::text, subject, action, resource_id, outcome, occurred_at
            """;

    private static final RowMapper<CareUnit> CARE_UNIT_MAPPER = JdbcMessagingStore::mapCareUnit;
    private static final RowMapper<EligibleRelationship> RELATIONSHIP_MAPPER =
            JdbcMessagingStore::mapRelationship;
    private static final RowMapper<PatientUnitConnection> CONNECTION_MAPPER =
            JdbcMessagingStore::mapConnection;
    private static final RowMapper<MessagingAudit> AUDIT_MAPPER = JdbcMessagingStore::mapAudit;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL messaging store.
     *
     * @param jdbc          shared named-parameter JDBC template
     * @param transactions  tenant-guarded transaction template
     */
    public JdbcMessagingStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return transactions.execute(status -> {
            Boolean active = jdbc.queryForObject(
                    "SELECT public.has_active_membership(:subject)",
                    new MapSqlParameterSource().addValue("subject", subject), Boolean.class);
            return Boolean.TRUE.equals(active);
        });
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)",
                new MapSqlParameterSource().addValue("subject", subject), String.class));
    }

    @Override
    public Set<String> verifiedPatientCharts(String organizationId, String subject) {
        return transactions.execute(status -> Set.copyOf(jdbc.queryForList("""
                SELECT chart_id::text FROM public.current_chart_links()
                 WHERE account_subject = :subject AND event = 'linked'
                """, new MapSqlParameterSource().addValue("subject", subject), String.class)));
    }

    @Override
    public boolean patientExists(String organizationId, String patientId) {
        return transactions.execute(status -> {
            Boolean exists = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM public.patient_record WHERE id = CAST(:patientId AS uuid))
                    """, new MapSqlParameterSource().addValue("patientId", patientId), Boolean.class);
            return Boolean.TRUE.equals(exists);
        });
    }

    @Override
    public Optional<CareUnit> findUnit(String organizationId, String unitId) {
        return transactions.execute(status -> jdbc.query("SELECT " + CARE_UNIT_COLUMNS
                + "FROM public.care_unit WHERE id = CAST(:unitId AS uuid)",
                new MapSqlParameterSource().addValue("unitId", unitId), CARE_UNIT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<EligibleRelationship> relationshipsFor(String organizationId, String patientId) {
        return transactions.execute(status -> jdbc.query("SELECT " + RELATIONSHIP_COLUMNS
                + "FROM public.eligible_relationship r "
                + "JOIN public.care_unit u ON u.organization_id = r.organization_id AND u.id = r.unit_id "
                + "WHERE r.patient_id = CAST(:patientId AS uuid) ORDER BY r.id",
                new MapSqlParameterSource().addValue("patientId", patientId), RELATIONSHIP_MAPPER));
    }

    @Override
    public Optional<EligibleRelationship> relationship(String organizationId, String relationshipId) {
        return transactions.execute(status -> jdbc.query("SELECT " + RELATIONSHIP_COLUMNS
                + "FROM public.eligible_relationship r "
                + "JOIN public.care_unit u ON u.organization_id = r.organization_id AND u.id = r.unit_id "
                + "WHERE r.id = CAST(:relationshipId AS uuid)",
                new MapSqlParameterSource().addValue("relationshipId", relationshipId),
                RELATIONSHIP_MAPPER).stream().findFirst());
    }

    @Override
    public void consumeRelationship(String organizationId, String relationshipId) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE public.eligible_relationship SET consumed = true
                 WHERE id = CAST(:relationshipId AS uuid)
                   AND NOT consumed
                   AND EXISTS (SELECT 1 FROM public.care_unit u
                                WHERE u.organization_id = eligible_relationship.organization_id
                                  AND u.id = eligible_relationship.unit_id)
                """, new MapSqlParameterSource().addValue("relationshipId", relationshipId)));
    }

    @Override
    public Optional<PatientUnitConnection> findConnectionByReference(
            String organizationId, String patientId, String unitId, String reference) {
        return transactions.execute(status -> jdbc.query("SELECT " + CONNECTION_COLUMNS
                + "FROM public.patient_unit_connection "
                + "WHERE patient_id = CAST(:patientId AS uuid) AND unit_id = CAST(:unitId AS uuid) "
                + "AND context_reference = :reference ORDER BY created_at, id",
                new MapSqlParameterSource().addValue("patientId", patientId)
                        .addValue("unitId", unitId).addValue("reference", reference),
                CONNECTION_MAPPER).stream().findFirst());
    }

    @Override
    public List<PatientUnitConnection> connectionsFor(String organizationId, String patientId) {
        return transactions.execute(status -> jdbc.query("SELECT " + CONNECTION_COLUMNS
                + "FROM public.patient_unit_connection "
                + "WHERE patient_id = CAST(:patientId AS uuid) ORDER BY created_at, id",
                new MapSqlParameterSource().addValue("patientId", patientId), CONNECTION_MAPPER));
    }

    @Override
    public Optional<PatientUnitConnection> findConnection(String organizationId, String connectionId) {
        return transactions.execute(status -> jdbc.query("SELECT " + CONNECTION_COLUMNS
                + "FROM public.patient_unit_connection WHERE id = CAST(:connectionId AS uuid)",
                new MapSqlParameterSource().addValue("connectionId", connectionId),
                CONNECTION_MAPPER).stream().findFirst());
    }

    @Override
    public PatientUnitConnection insertConnection(String organizationId, PatientUnitConnection connection) {
        return transactions.execute(status -> {
            List<PatientUnitConnection> inserted = jdbc.query("""
                    INSERT INTO public.patient_unit_connection
                        (organization_id, id, patient_id, unit_id, context, context_reference,
                         relationship_id, state, verified_by, verified_at, created_at, revoked_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid),
                            CAST(:patientId AS uuid), CAST(:unitId AS uuid), :context,
                            :contextReference, CAST(:relationshipId AS uuid), :state,
                            :verifiedBy, CAST(:verifiedAt AS timestamptz),
                            CAST(:createdAt AS timestamptz), CAST(:revokedAt AS timestamptz))
                    ON CONFLICT (organization_id, patient_id, unit_id, context_reference) DO NOTHING
                    """ + "RETURNING " + CONNECTION_COLUMNS, connectionParameters(organizationId, connection),
                    CONNECTION_MAPPER);
            if (inserted.isEmpty()) {
                return findConnectionByReference(organizationId, connection.patientId(),
                        connection.unitId(), connection.contextReference()).orElseThrow();
            }
            return inserted.getFirst();
        });
    }

    @Override
    public void revokeConnection(String organizationId, String connectionId, Instant revokedAt) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                UPDATE public.patient_unit_connection
                   SET state = 'revoked', revoked_at = CAST(:revokedAt AS timestamptz)
                 WHERE id = CAST(:connectionId AS uuid) AND state = 'active'
                """, new MapSqlParameterSource().addValue("connectionId", connectionId)
                        .addValue("revokedAt", dateTime(revokedAt))));
    }

    @Override
    public void appendAudit(MessagingAudit audit) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.messaging_audit
                    (organization_id, subject, action, resource_id, outcome, occurred_at)
                VALUES (CAST(:organizationId AS uuid), :subject, :action, :resourceId,
                        :outcome, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", audit.organizationId())
                        .addValue("subject", audit.subject())
                        .addValue("action", audit.action())
                        .addValue("resourceId", audit.resourceId())
                        .addValue("outcome", audit.outcome())
                        .addValue("occurredAt", dateTime(audit.occurredAt()))));
    }

    @Override
    public List<MessagingAudit> auditEvents(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + AUDIT_COLUMNS
                + "FROM public.messaging_audit ORDER BY seq", AUDIT_MAPPER));
    }

    private static MapSqlParameterSource connectionParameters(
            String organizationId, PatientUnitConnection connection) {
        return new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("id", connection.id())
                .addValue("patientId", connection.patientId())
                .addValue("unitId", connection.unitId())
                .addValue("context", connection.context().wire())
                .addValue("contextReference", connection.contextReference())
                .addValue("relationshipId", connection.relationshipId())
                .addValue("state", connection.state().wire())
                .addValue("verifiedBy", connection.verifiedBy())
                .addValue("verifiedAt", dateTime(connection.verifiedAt()))
                .addValue("createdAt", dateTime(connection.createdAt()))
                .addValue("revokedAt", dateTime(connection.revokedAt()));
    }

    private static CareUnit mapCareUnit(ResultSet resultSet, int rowNumber) throws SQLException {
        Array topics = resultSet.getArray("topics");
        List<String> topicList = List.of((String[]) topics.getArray());
        return new CareUnit(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("facility_id"), resultSet.getString("name"), topicList,
                resultSet.getBoolean("active"), resultSet.getString("unit_key"));
    }

    private static EligibleRelationship mapRelationship(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new EligibleRelationship(resultSet.getString("id"),
                resultSet.getString("organization_id"), resultSet.getString("patient_id"),
                resultSet.getString("unit_id"), ConnectionContext.from(resultSet.getString("context")),
                resultSet.getString("reference"), resultSet.getBoolean("active"),
                instant(resultSet, "expires_at"), resultSet.getBoolean("consumed"));
    }

    private static PatientUnitConnection mapConnection(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new PatientUnitConnection(resultSet.getString("id"),
                resultSet.getString("organization_id"), resultSet.getString("patient_id"),
                resultSet.getString("unit_id"), ConnectionContext.from(resultSet.getString("context")),
                resultSet.getString("context_reference"), resultSet.getString("relationship_id"),
                ConnectionState.from(resultSet.getString("state")), resultSet.getString("verified_by"),
                instant(resultSet, "verified_at"), instant(resultSet, "created_at"),
                instant(resultSet, "revoked_at"));
    }

    private static MessagingAudit mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MessagingAudit(resultSet.getString("organization_id"),
                resultSet.getString("subject"), resultSet.getString("action"),
                resultSet.getString("resource_id"), resultSet.getString("outcome"),
                instant(resultSet, "occurred_at"));
    }

    /**
     * Convert a server instant to the driver's timestamptz representation.
     *
     * <p>pgjdbc cannot infer a SQL type for {@link java.time.Instant}; the UTC
     * offset keeps the wire value unambiguous. A missing instant stays null so
     * an optional column is written as SQL NULL.</p>
     *
     * @param instant server instant, or {@code null}
     * @return an offset date time, or {@code null}
     */
    private static OffsetDateTime dateTime(Instant instant) {
        return Optional.ofNullable(instant)
                .map(value -> OffsetDateTime.ofInstant(value, ZoneOffset.UTC)).orElse(null);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return Optional.ofNullable(resultSet.getObject(column, OffsetDateTime.class))
                .map(OffsetDateTime::toInstant).orElse(null);
    }
}
