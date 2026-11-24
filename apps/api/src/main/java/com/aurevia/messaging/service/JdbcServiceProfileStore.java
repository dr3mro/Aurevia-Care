package com.aurevia.messaging.service;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.messaging.service.ServiceProfileContract.LocalizedText;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileStatus;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceHours;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceWindow;
import com.aurevia.messaging.service.ServiceProfileContract.WorkItemFact;
import com.aurevia.messaging.service.ServiceProfileContract.WorkState;
import com.aurevia.messaging.units.MessagingContract.MessagingAudit;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL-backed persistence boundary for the MSG-06 service-profile slice
 * (ARC-01, ARC-02, OPS-02).
 *
 * <p>V92 owns the four tenant tables and their forced row-level security. Every
 * operation runs in the tenant-guarded transaction, which binds
 * {@code app.current_org} and {@code app.actor} after the transaction opens;
 * forced RLS therefore confines each statement to the active organization and
 * every read also names the active organization explicitly. Writes carry the
 * method's {@code organizationId} so the policy's {@code WITH CHECK} rejects a
 * mismatch. The organizationId argument on fold reads is likewise passed to
 * the SQL so a wrong tenant cannot be served even if a context is stale.</p>
 *
 * <p>Membership, roles, verified charts and patient-unit connections are owned
 * by the tenancy, patient and messaging modules. This adapter reads their
 * authoritative folds ({@code has_active_membership}, {@code active_roles},
 * {@code current_chart_links} and {@code patient_unit_connection}) rather than
 * projecting a second copy, so a deactivation, unlink or revocation takes
 * effect on the next request. Staff-to-unit assignment has no other durable
 * source and is read from the V92 reference table. Profile versions are
 * insert-only except for the single, database-guarded draft-to-approved
 * transition; a replay of a publish returns the row that already won.</p>
 */
@Repository
public class JdbcServiceProfileStore implements ServiceProfileStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<LocalizedText>> INSTRUCTIONS = new TypeReference<>() { };

    private static final String PROFILE_COLUMNS = """
            id::text, organization_id::text, unit_id::text, version, status, time_zone, windows,
            response_target_minutes, topics, urgent_instructions::text, queue_owner, backup_coverage,
            escalation_channel, urgent_channel, downtime_channel, created_by, approved_by,
            approved_at, approval_note, effective_from, created_at
            """;
    private static final String WORK_ITEM_COLUMNS = """
            organization_id::text, id::text, unit_id::text, state, assignee, occurred_at,
            last_staff_response_at, notification_failed
            """;
    private static final String AUDIT_COLUMNS = """
            organization_id::text, subject, action, resource_id, outcome, occurred_at
            """;

    private static final RowMapper<ServiceProfile> PROFILE_MAPPER = JdbcServiceProfileStore::mapProfile;
    private static final RowMapper<WorkItemFact> WORK_ITEM_MAPPER = JdbcServiceProfileStore::mapWorkItem;
    private static final RowMapper<MessagingAudit> AUDIT_MAPPER = JdbcServiceProfileStore::mapAudit;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL service-profile store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcServiceProfileStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public boolean hasActiveMembership(String organizationId, String subject) {
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class)));
    }

    @Override
    public List<String> activeRoles(String organizationId, String subject) {
        return transactions.execute(status -> jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject) ORDER BY role_key",
                Map.of("subject", subject), String.class));
    }

    @Override
    public boolean unitExists(String organizationId, String unitId) {
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.care_unit WHERE id = CAST(:unitId AS uuid))
                """, Map.of("unitId", unitId), Boolean.class)));
    }

    @Override
    public boolean hasConnectedChart(String organizationId, String subject, String unitId) {
        // Derive candidate charts from the CURRENT tenant only and require both a
        // live chart link and an active connection. Scanning all links or ignoring
        // connection state would let a revoked or foreign connection resurrect a
        // local one (ARC-02, IAM-05).
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.current_chart_links() l
                      JOIN public.patient_unit_connection c
                        ON c.organization_id = l.organization_id
                       AND c.patient_id = l.chart_id
                       AND c.unit_id = CAST(:unitId AS uuid)
                       AND c.state = 'active'
                     WHERE l.organization_id = CAST(:organizationId AS uuid)
                       AND l.account_subject = :subject
                       AND l.event = 'linked')
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject).addValue("unitId", unitId), Boolean.class)));
    }

    @Override
    public boolean isUnitAssigned(String organizationId, String subject, String unitId) {
        return transactions.execute(status -> Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.service_profile_unit_assignment
                                WHERE organization_id = CAST(:organizationId AS uuid)
                                  AND subject = :subject
                                  AND unit_id = CAST(:unitId AS uuid))
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("subject", subject).addValue("unitId", unitId), Boolean.class)));
    }

    /**
     * Read the effective, named-approved channel codes of one tenant policy.
     *
     * @param organizationId active tenant identifier
     * @param key            tenant-local approved-channel configuration key
     * @return approved codes, or an empty set when no version is effective
     */
    @Override
    public Set<String> approvedChannels(String organizationId, String key) {
        // Read the authoritative fold rather than a copied catalog, so an
        // approval or revocation takes effect on the next publish. The outer
        // organization predicate is a second fail-closed constraint: if the
        // bound GUC and this argument ever disagree, no policy is visible and
        // the caller denies the channel instead of serving a foreign one.
        return transactions.execute(status -> {
            List<String> values = jdbc.queryForList("""
                    SELECT value FROM public.effective_config(:key)
                     WHERE organization_id = CAST(:organizationId AS uuid)
                    """, new MapSqlParameterSource().addValue("key", key)
                            .addValue("organizationId", organizationId), String.class);
            Set<String> channels = new HashSet<>();
            for (String value : values) {
                for (String code : value.split(",")) {
                    String trimmed = code.strip();
                    // An empty token from a stray comma must never become an
                    // approvable code; an empty channel is refused upstream.
                    if (!trimmed.isEmpty()) {
                        channels.add(trimmed);
                    }
                }
            }
            return Set.copyOf(channels);
        });
    }

    @Override
    public Optional<ServiceProfile> findProfile(String organizationId, String profileId) {
        return transactions.execute(status -> jdbc.query("SELECT " + PROFILE_COLUMNS
                + "FROM public.service_profile "
                + "WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:profileId AS uuid)",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("profileId", profileId), PROFILE_MAPPER).stream().findFirst());
    }

    @Override
    public Optional<ServiceProfile> effectiveProfile(String organizationId, String unitId, Instant at) {
        // Only an approved version whose effective date has arrived may be served;
        // a higher but future-dated version must not displace the current one.
        return transactions.execute(status -> jdbc.query("SELECT " + PROFILE_COLUMNS
                + "FROM public.service_profile "
                + "WHERE organization_id = CAST(:organizationId AS uuid) "
                + "AND unit_id = CAST(:unitId AS uuid) AND status = 'approved' "
                + "AND effective_from <= CAST(:at AS timestamptz) "
                + "ORDER BY version DESC LIMIT 1",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("unitId", unitId).addValue("at", dateTime(at)), PROFILE_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<ServiceProfile> versions(String organizationId, String unitId) {
        return transactions.execute(status -> jdbc.query("SELECT " + PROFILE_COLUMNS
                + "FROM public.service_profile "
                + "WHERE organization_id = CAST(:organizationId AS uuid) "
                + "AND unit_id = CAST(:unitId AS uuid) ORDER BY version",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("unitId", unitId), PROFILE_MAPPER));
    }

    @Override
    public int latestVersion(String organizationId, String unitId) {
        return transactions.execute(status -> {
            Integer version = jdbc.queryForObject("""
                    SELECT MAX(version) FROM public.service_profile
                     WHERE organization_id = CAST(:organizationId AS uuid)
                       AND unit_id = CAST(:unitId AS uuid)
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("unitId", unitId), Integer.class);
            return version == null ? Integer.valueOf(0) : version;
        });
    }

    @Override
    public ServiceProfile insertProfile(String organizationId, ServiceProfile profile) {
        return transactions.execute(status -> {
            jdbc.update("""
                    INSERT INTO public.service_profile
                        (organization_id, id, unit_id, version, status, time_zone, windows,
                         response_target_minutes, topics, urgent_instructions, queue_owner,
                         backup_coverage, escalation_channel, urgent_channel, downtime_channel,
                         created_by, approved_by, approved_at, approval_note,
                         effective_from, created_at)
                    VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:unitId AS uuid),
                            :version, :status, :timeZone, CAST(:windows AS text[]),
                            :responseTargetMinutes, CAST(:topics AS text[]),
                            CAST(:urgentInstructions AS jsonb), :queueOwner,
                            CAST(:backupCoverage AS text[]), :escalationChannel, :urgentChannel,
                            :downtimeChannel, :createdBy, CAST(:approvedBy AS text),
                            CAST(:approvedAt AS timestamptz), :approvalNote,
                            CAST(:effectiveFrom AS timestamptz), CAST(:createdAt AS timestamptz))
                    ON CONFLICT (organization_id, unit_id, version) DO NOTHING
                    """, profileParameters(organizationId, profile));
            return jdbc.query("SELECT " + PROFILE_COLUMNS + "FROM public.service_profile "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) "
                            + "AND unit_id = CAST(:unitId AS uuid) AND version = :version",
                    new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("unitId", profile.unitId()).addValue("version", profile.version()),
                    PROFILE_MAPPER).stream().findFirst().orElseThrow();
        });
    }

    @Override
    public ServiceProfile updateProfile(String organizationId, ServiceProfile profile) {
        return transactions.execute(status -> {
            jdbc.update("""
                    UPDATE public.service_profile
                       SET status = :status, approved_by = :approvedBy,
                           approved_at = CAST(:approvedAt AS timestamptz),
                           approval_note = :approvalNote
                     WHERE organization_id = CAST(:organizationId AS uuid)
                       AND id = CAST(:id AS uuid) AND status = 'draft'
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("id", profile.id()).addValue("status", profile.status().wire())
                            .addValue("approvedBy", profile.approvedBy())
                            .addValue("approvedAt", dateTime(profile.approvedAt()))
                            .addValue("approvalNote", profile.approvalNote()));
            return jdbc.query("SELECT " + PROFILE_COLUMNS + "FROM public.service_profile "
                            + "WHERE organization_id = CAST(:organizationId AS uuid) "
                            + "AND id = CAST(:id AS uuid)",
                    new MapSqlParameterSource().addValue("organizationId", organizationId)
                            .addValue("id", profile.id()), PROFILE_MAPPER).stream().findFirst().orElseThrow();
        });
    }

    @Override
    public List<WorkItemFact> workItems(String organizationId, String unitId) {
        return transactions.execute(status -> jdbc.query("SELECT " + WORK_ITEM_COLUMNS
                + "FROM public.service_profile_work_item "
                + "WHERE organization_id = CAST(:organizationId AS uuid) "
                + "AND unit_id = CAST(:unitId AS uuid) ORDER BY seq",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("unitId", unitId), WORK_ITEM_MAPPER));
    }

    @Override
    public void addWorkItem(String organizationId, WorkItemFact item) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.service_profile_work_item
                    (organization_id, id, unit_id, state, assignee, occurred_at,
                     last_staff_response_at, notification_failed)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), CAST(:unitId AS uuid),
                        :state, :assignee, CAST(:occurredAt AS timestamptz),
                        CAST(:lastResponseAt AS timestamptz), :notificationFailed)
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", item.id()).addValue("unitId", item.unitId())
                        .addValue("state", item.state().wire()).addValue("assignee", item.assignee())
                        .addValue("occurredAt", dateTime(item.createdAt()))
                        .addValue("lastResponseAt", dateTime(item.lastStaffResponseAt()))
                        .addValue("notificationFailed", item.notificationFailed())));
    }

    @Override
    public void appendAudit(MessagingAudit audit) {
        transactions.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO public.service_profile_audit
                    (organization_id, subject, action, resource_id, outcome, occurred_at)
                VALUES (CAST(:organizationId AS uuid), :subject, :action, :resourceId,
                        :outcome, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource().addValue("organizationId", audit.organizationId())
                        .addValue("subject", audit.subject()).addValue("action", audit.action())
                        .addValue("resourceId", audit.resourceId()).addValue("outcome", audit.outcome())
                        .addValue("occurredAt", dateTime(audit.occurredAt()))));
    }

    @Override
    public List<MessagingAudit> auditEvents(String organizationId) {
        return transactions.execute(status -> jdbc.query("SELECT " + AUDIT_COLUMNS
                + "FROM public.service_profile_audit "
                + "WHERE organization_id = CAST(:organizationId AS uuid) ORDER BY seq",
                Map.of("organizationId", organizationId), AUDIT_MAPPER));
    }

    private static SqlParameterSource profileParameters(String organizationId, ServiceProfile profile) {
        return new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("id", profile.id())
                .addValue("unitId", profile.unitId())
                .addValue("version", profile.version())
                .addValue("status", profile.status().wire())
                .addValue("timeZone", profile.hours().zone().getId())
                .addValue("windows", windowWire(profile.hours().windows()))
                .addValue("responseTargetMinutes", profile.responseTargetMinutes())
                .addValue("topics", profile.topics().toArray(String[]::new))
                .addValue("urgentInstructions", JSON.writeValueAsString(profile.urgentInstructions()))
                .addValue("queueOwner", profile.queueOwner())
                .addValue("backupCoverage", profile.backupCoverage().toArray(String[]::new))
                .addValue("escalationChannel", profile.escalationChannel())
                .addValue("urgentChannel", profile.urgentChannel())
                .addValue("downtimeChannel", profile.downtimeChannel())
                .addValue("createdBy", profile.createdBy())
                .addValue("approvedBy", profile.approvedBy())
                .addValue("approvedAt", dateTime(profile.approvedAt()))
                .addValue("approvalNote", profile.approvalNote())
                .addValue("effectiveFrom", dateTime(profile.effectiveFrom()))
                .addValue("createdAt", dateTime(profile.createdAt()));
    }

    private static String[] windowWire(List<ServiceWindow> windows) {
        return windows.stream()
                .map(window -> window.day().name() + "|" + window.start() + "|" + window.end())
                .toArray(String[]::new);
    }

    private static List<ServiceWindow> windows(List<String> values) {
        return values.stream().map(ServiceProfileInputs::window).toList();
    }

    private static List<String> strings(ResultSet resultSet, String column) throws SQLException {
        Array array = resultSet.getArray(column);
        return List.of((String[]) array.getArray());
    }

    private static ServiceProfile mapProfile(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ServiceProfile(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("unit_id"), resultSet.getInt("version"),
                ProfileStatus.from(resultSet.getString("status")),
                new ServiceHours(ZoneId.of(resultSet.getString("time_zone")),
                        windows(strings(resultSet, "windows"))),
                resultSet.getInt("response_target_minutes"),
                strings(resultSet, "topics"),
                JSON.readValue(resultSet.getString("urgent_instructions"), INSTRUCTIONS),
                resultSet.getString("queue_owner"), strings(resultSet, "backup_coverage"),
                resultSet.getString("escalation_channel"), resultSet.getString("urgent_channel"),
                resultSet.getString("downtime_channel"), resultSet.getString("created_by"),
                resultSet.getString("approved_by"), nullableInstant(resultSet, "approved_at"),
                resultSet.getString("approval_note"), instant(resultSet, "effective_from"),
                instant(resultSet, "created_at"));
    }

    private static WorkItemFact mapWorkItem(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WorkItemFact(resultSet.getString("organization_id"), resultSet.getString("id"),
                resultSet.getString("unit_id"), WorkState.from(resultSet.getString("state")),
                resultSet.getString("assignee"), instant(resultSet, "occurred_at"),
                nullableInstant(resultSet, "last_staff_response_at"),
                resultSet.getBoolean("notification_failed"));
    }

    private static MessagingAudit mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new MessagingAudit(resultSet.getString("organization_id"), resultSet.getString("subject"),
                resultSet.getString("action"), resultSet.getString("resource_id"),
                resultSet.getString("outcome"), instant(resultSet, "occurred_at"));
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
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Read a non-null timestamptz column as a server instant.
     *
     * @param resultSet result row
     * @param column    column label
     * @return the instant
     * @throws SQLException when the column cannot be read
     */
    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    /**
     * Read a nullable timestamptz column, preserving "not yet recorded" as null.
     *
     * @param resultSet result row
     * @param column    column label
     * @return the instant, or {@code null} when the column is null
     * @throws SQLException when the column cannot be read
     */
    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
