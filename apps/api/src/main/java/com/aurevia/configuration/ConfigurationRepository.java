package com.aurevia.configuration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for tenant configuration commands.
 *
 * <p>Every statement runs inside the tenant-guarded transaction. V5 applies the
 * forced RLS policy to both tables, makes version and actor identity database-owned,
 * and folds the effective version from approvals and effective dates.</p>
 */
@Repository
public class ConfigurationRepository {

    /** Business purpose stamped on configuration administration audit events (OPS-02). */
    public static final String PURPOSE = "CONFIG_ADMIN";
    /** Audit resource scope for configuration events (OPS-02). */
    public static final String SCOPE = "CONFIG";

    private static final RowMapper<ConfigEntry> ENTRY_MAPPER = ConfigurationRepository::mapEntry;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public ConfigurationRepository(NamedParameterJdbcTemplate jdbc) {
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
     * Find a user identifier by subject in the active organization.
     *
     * @param subject subject to look up
     * @return the user identifier when visible, otherwise empty
     */
    public Optional<String> findUserIdBySubject(String subject) {
        return jdbc.query("""
                SELECT id::text FROM public.user_identity WHERE subject = :subject
                """, Map.of("subject", subject), (resultSet, rowNumber) -> resultSet.getString(1))
                .stream().findFirst();
    }

    /**
     * Allocate the next version under a tenant-and-key advisory lock.
     *
     * @param key tenant-local configuration key
     * @return the next monotonic version
     */
    public int nextVersion(String key) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT public.next_config_version(:key)", Map.of("key", key), Integer.class),
                "database did not allocate a configuration version");
    }

    /**
     * Append a draft configuration entry; the author is forced from the actor.
     *
     * @param organizationId active organization identifier
     * @param key            tenant-local configuration key
     * @param version        database-allocated version
     * @param value          opaque configuration value
     * @param effectiveFrom  instant from which the version may become effective
     * @return the generated entry identifier
     */
    public String insertEntry(String organizationId, String key, int version, String value, String effectiveFrom) {
        return jdbc.queryForObject("""
                INSERT INTO public.config_entry
                    (organization_id, key, version, value, effective_from)
                VALUES (CAST(:organizationId AS uuid), :key, :version, :value,
                        CAST(:effectiveFrom AS timestamptz))
                RETURNING id::text
                """, params(organizationId).addValue("key", key).addValue("version", version)
                .addValue("value", value).addValue("effectiveFrom", effectiveFrom), String.class);
    }

    /**
     * Append a named approval; the approver is forced from the actor.
     *
     * @param organizationId active organization identifier
     * @param entryId        entry being approved
     * @param note           optional approval note
     * @return number of inserted rows
     */
    public int insertApproval(String organizationId, String entryId, String note) {
        return jdbc.update("""
                INSERT INTO public.config_approval (organization_id, entry_id, note)
                VALUES (CAST(:organizationId AS uuid), CAST(:entryId AS uuid), :note)
                """, params(organizationId).addValue("entryId", entryId).addValue("note", note));
    }

    /**
     * Find the database identifier of one version of a key.
     *
     * @param key     tenant-local configuration key
     * @param version version to resolve
     * @return the entry identifier when visible, otherwise empty
     */
    public Optional<String> findEntryId(String key, int version) {
        return jdbc.query("""
                SELECT id::text FROM public.config_entry WHERE key = :key AND version = :version
                """, new MapSqlParameterSource().addValue("key", key).addValue("version", version),
                (resultSet, rowNumber) -> resultSet.getString(1)).stream().findFirst();
    }

    /**
     * Find one version of a key with its approval facts.
     *
     * @param key     tenant-local configuration key
     * @param version version to find
     * @return the entry when visible, otherwise empty
     */
    public Optional<ConfigEntry> findEntry(String key, int version) {
        return jdbc.query("""
                SELECT e.key, e.version, e.value,
                       CASE WHEN a.approved_at IS NOT NULL THEN 'approved' ELSE 'pending' END AS status,
                       e.effective_from::text AS effective_from, e.created_by::text AS created_by,
                       a.approved_by::text AS approved_by, a.approved_at::text AS approved_at, a.note
                  FROM public.config_entry e
                  LEFT JOIN public.config_approval a
                    ON a.organization_id = e.organization_id AND a.entry_id = e.id
                 WHERE e.key = :key AND e.version = :version
                """, new MapSqlParameterSource().addValue("key", key).addValue("version", version),
                ENTRY_MAPPER).stream().findFirst();
    }

    /**
     * Read the single effective approved version of a key.
     *
     * @param key tenant-local configuration key
     * @return the effective entry when visible and in force, otherwise empty
     */
    public Optional<ConfigEntry> effective(String key) {
        return jdbc.query("""
                SELECT key, version, value, 'approved' AS status,
                       effective_from::text AS effective_from, created_by::text AS created_by,
                       approved_by::text AS approved_by, approved_at::text AS approved_at, note
                  FROM public.effective_config(:key)
                """, Map.of("key", key), ENTRY_MAPPER).stream().findFirst();
    }

    /**
     * Read all versions of a key with their approval facts.
     *
     * @param key tenant-local configuration key
     * @return all visible versions ordered by version
     */
    public List<ConfigEntry> findAll(String key) {
        return jdbc.query("""
                SELECT e.key, e.version, e.value,
                       CASE WHEN a.approved_at IS NOT NULL THEN 'approved' ELSE 'pending' END AS status,
                       e.effective_from::text AS effective_from, e.created_by::text AS created_by,
                       a.approved_by::text AS approved_by, a.approved_at::text AS approved_at, a.note
                  FROM public.config_entry e
                  LEFT JOIN public.config_approval a
                    ON a.organization_id = e.organization_id AND a.entry_id = e.id
                 WHERE e.key = :key
                 ORDER BY e.version
                """, Map.of("key", key), ENTRY_MAPPER);
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     configuration action key
     * @param resourceId configuration key addressed by the command
     * @param outcome    success, denied, not-found or conflict outcome
     */
    public void appendAudit(TenantContext context, String action, String resourceId, String outcome) {
        jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, actor_id, action, resource_type, resource_id,
                     purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), NULL, :action, :resourceType, :resourceId,
                        :purpose, :scope, :correlationId, :outcome)
                """, params(context.organizationId())
                .addValue("action", action)
                .addValue("resourceType", SCOPE)
                .addValue("resourceId", resourceId)
                .addValue("purpose", PURPOSE)
                .addValue("scope", SCOPE)
                .addValue("correlationId", context.correlationId())
                .addValue("outcome", outcome));
    }

    private MapSqlParameterSource params(String organizationId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId);
    }

    private static ConfigEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ConfigEntry(resultSet.getString("key"), resultSet.getInt("version"),
                resultSet.getString("value"), resultSet.getString("status"),
                resultSet.getString("effective_from"), resultSet.getString("created_by"),
                resultSet.getString("approved_by"), resultSet.getString("approved_at"),
                resultSet.getString("note"));
    }
}
