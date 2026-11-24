package com.aurevia.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for tenant catalogs.
 *
 * <p>Catalog entries are versioned configuration keys in the append-only V5
 * tables. Writes delegate to the configuration domain; this repository only
 * reads the effective fold and appends audit events for catalog commands.</p>
 */
@Repository
public class CatalogRepository {

    /** Business purpose stamped on catalog administration audit events (OPS-02). */
    public static final String PURPOSE = "CATALOG_ADMIN";
    /** Audit scope for catalog events (OPS-02). */
    public static final String SCOPE = "CATALOG";

    private static final RowMapper<CatalogEntry> ENTRY_MAPPER = CatalogRepository::mapEntry;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public CatalogRepository(NamedParameterJdbcTemplate jdbc) {
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
     * Read the single effective approved version of one catalog code.
     *
     * @param key full catalog key
     * @return the effective entry when visible, otherwise empty
     */
    public java.util.Optional<CatalogEntry> effective(String key) {
        return jdbc.query("""
                SELECT key, version, value, effective_from::text AS effective_from,
                       approved_by::text AS approved_by, approved_at::text AS approved_at, note
                  FROM public.effective_config(:key)
                """, Map.of("key", key), ENTRY_MAPPER).stream().findFirst();
    }

    /**
     * Read all effective approved entries in a catalog family.
     *
     * @param keyPrefix family key prefix including the trailing dot
     * @return effective entries ordered by key
     */
    public List<CatalogEntry> family(String keyPrefix) {
        return jdbc.query("""
                SELECT key, version, value, effective_from::text AS effective_from,
                       approved_by::text AS approved_by, approved_at::text AS approved_at, note
                  FROM public.effective_catalog_family(:prefix)
                """, Map.of("prefix", keyPrefix), ENTRY_MAPPER);
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     catalog action key
     * @param resourceId catalog key addressed by the command
     * @param outcome    success, denied or not-found outcome
     */
    public void appendAudit(TenantContext context, String action, String resourceId, String outcome) {
        jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, actor_id, action, resource_type, resource_id,
                     purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), NULL, :action, :resourceType, :resourceId,
                        :purpose, :scope, :correlationId, :outcome)
                """, new MapSqlParameterSource()
                .addValue("organizationId", context.organizationId())
                .addValue("action", action)
                .addValue("resourceType", SCOPE)
                .addValue("resourceId", resourceId)
                .addValue("purpose", PURPOSE)
                .addValue("scope", SCOPE)
                .addValue("correlationId", context.correlationId())
                .addValue("outcome", outcome));
    }

    private static CatalogEntry mapEntry(ResultSet resultSet, int rowNumber) throws SQLException {
        String key = resultSet.getString("key");
        int dot = key.lastIndexOf('.');
        String prefix = key.substring(0, dot + 1);
        String family = prefix.substring("catalog.".length(), prefix.length() - 1);
        return new CatalogEntry(family, key.substring(dot + 1), resultSet.getInt("version"),
                resultSet.getString("value"), resultSet.getString("effective_from"),
                resultSet.getString("approved_by"), resultSet.getString("approved_at"),
                resultSet.getString("note"));
    }
}
