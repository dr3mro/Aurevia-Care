package com.aurevia.exchange.result;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.aurevia.exchange.result.ReconciliationModel.ReconciliationAction;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;

/**
 * Tenant-guarded JDBC implementation of the durable reconciliation-resolution port.
 *
 * <p>Every statement binds the active organization explicitly and forced row-level security confines it
 * further. The insert is idempotent under concurrency: the tenant-scoped unique key lets exactly one
 * retry append a fact, and an empty {@code RETURNING} result tells the caller it lost the race.</p>
 */
@Component
public final class JdbcReconciliationStore implements ReconciliationStore {

    private static final String COLUMNS = """
            entry_id::text AS entry_id, action, reason, assigned_owner, resolved_at
            """;
    private static final RowMapper<Resolution> MAPPER = JdbcReconciliationStore::mapResolution;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the store over the shared JDBC boundary.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public JdbcReconciliationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Read one decision through both the tenant predicate and forced row-level security.
     *
     * @param organizationId active tenant identifier as text
     * @param entryId        queue entry identifier
     * @return the persisted decision, or {@code null} when this tenant has none
     */
    @Override
    public Resolution find(String organizationId, String entryId) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM public.reconciliation_resolution"
                + " WHERE organization_id = CAST(:organizationId AS uuid)"
                + " AND entry_id = CAST(:entryId AS uuid)",
                new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("entryId", entryId),
                MAPPER).stream().findFirst().orElse(null);
    }

    /**
     * Append one decision, letting the tenant-scoped unique key arbitrate a retry.
     *
     * @param organizationId active tenant identifier as text
     * @param resolution     decision to persist
     * @return the row mapped from {@code RETURNING}, or {@code null} when a fact already exists
     */
    @Override
    public Resolution insert(String organizationId, Resolution resolution) {
        List<Resolution> inserted = jdbc.query("""
                INSERT INTO public.reconciliation_resolution
                    (organization_id, entry_id, action, reason, assigned_owner)
                VALUES (CAST(:organizationId AS uuid), CAST(:entryId AS uuid), :action, :reason,
                        CAST(:assignedOwner AS text))
                ON CONFLICT (organization_id, entry_id) DO NOTHING
                RETURNING entry_id::text AS entry_id, action, reason, assigned_owner, resolved_at
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("entryId", resolution.entryId())
                .addValue("action", resolution.action().wire())
                .addValue("reason", resolution.reason())
                .addValue("assignedOwner", resolution.assignedOwner(), Types.VARCHAR),
                MAPPER);
        return inserted.isEmpty() ? null : inserted.get(0);
    }

    private static Resolution mapResolution(ResultSet resultSet, int rowNumber) throws SQLException {
        OffsetDateTime timestamp = Objects.requireNonNull(
                resultSet.getObject("resolved_at", OffsetDateTime.class),
                "a stored resolution must carry its server timestamp");
        return new Resolution(resultSet.getString("entry_id"),
                ReconciliationAction.valueOf(resultSet.getString("action").toUpperCase(Locale.ROOT)),
                resultSet.getString("reason"), resultSet.getString("assigned_owner"),
                timestamp.toInstant());
    }
}
