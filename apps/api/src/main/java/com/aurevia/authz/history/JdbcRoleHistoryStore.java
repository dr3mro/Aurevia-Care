package com.aurevia.authz.history;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Single SQL boundary for IAM-02 role-history reads.
 *
 * <p>V71 creates the append-only ledger with forced row-level security: the policy
 * binds every row to the active organization, and triggers reject update, delete
 * and truncate for every role including the migration owner. Timestamps are cast
 * to text in SQL so the projection carries stable, time-zone-qualified strings.</p>
 */
@Repository
public class JdbcRoleHistoryStore implements RoleHistoryStore {

    private static final RowMapper<RoleHistory.RoleChange> CHANGE_MAPPER = JdbcRoleHistoryStore::mapChange;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the store.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public JdbcRoleHistoryStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<RoleHistory.RoleChange> findBySubject(String subject) {
        return jdbc.query("""
                SELECT h.id::text AS id, h.subject, h.role_key, h.change_kind,
                       h.actor, h.recorded_at::text AS recorded_at,
                       h.effective_from::text AS effective_from,
                       h.effective_to::text AS effective_to
                  FROM public.role_change_history h
                 WHERE h.subject = :subject
                 ORDER BY h.effective_from, h.recorded_at, h.id
                """, Map.of("subject", subject), CHANGE_MAPPER);
    }

    private static RoleHistory.RoleChange mapChange(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new RoleHistory.RoleChange(resultSet.getString("id"), resultSet.getString("subject"),
                resultSet.getString("role_key"), resultSet.getString("change_kind"),
                resultSet.getString("actor"), resultSet.getString("recorded_at"),
                resultSet.getString("effective_from"), resultSet.getString("effective_to"));
    }
}
