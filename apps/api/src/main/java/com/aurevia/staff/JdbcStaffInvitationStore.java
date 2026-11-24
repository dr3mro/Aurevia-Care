package com.aurevia.staff;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed persistence boundary for staff invitations (IAM-02, ARC-02).
 *
 * <p>V70 owns the table and its forced row-level security. The active tenant and
 * actor are bound transaction-locally before any statement runs, so reads and
 * writes are confined to the selected organization. The raw token is hashed by
 * PostgreSQL with core {@code sha256}; no plaintext token reaches a column.</p>
 */
@Repository
public class JdbcStaffInvitationStore implements StaffInvitationStore {

    private static final RowMapper<StaffInvitation> INVITATION_MAPPER = JdbcStaffInvitationStore::mapInvitation;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the invitation store.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public JdbcStaffInvitationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insert(String organizationId, String id, String subject, String displayName, String facilityId,
                      List<String> roles, String invitedBy, String token, Instant expiresAt) {
        return jdbc.update("""
                INSERT INTO public.staff_invitation
                    (organization_id, id, subject, display_name, facility_id, roles,
                     token_hash, invited_by, expires_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :subject, :displayName,
                        CAST(:facilityId AS uuid), string_to_array(:roles, ','),
                        encode(sha256(convert_to(:token, 'UTF8')), 'hex'), :invitedBy,
                        CAST(:expiresAt AS timestamptz))
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                        .addValue("id", id).addValue("subject", subject).addValue("displayName", displayName)
                        .addValue("facilityId", facilityId).addValue("roles", String.join(",", roles))
                        .addValue("invitedBy", invitedBy).addValue("token", token)
                        .addValue("expiresAt", expiresAt.toString()));
    }

    @Override
    public List<StaffInvitation> findPending() {
        return jdbc.query("""
                SELECT id::text AS id, subject, display_name, facility_id::text AS facility_id,
                       roles, expires_at::text AS expires_at, 'pending' AS status, invited_by
                  FROM public.staff_invitation
                 WHERE used_at IS NULL AND revoked_at IS NULL AND expires_at > now()
                 ORDER BY created_at, id
                """, new MapSqlParameterSource(), INVITATION_MAPPER);
    }

    @Override
    public Optional<StaffInvitation> revoke(String id) {
        return jdbc.query("""
                UPDATE public.staff_invitation
                   SET revoked_at = now()
                 WHERE id = CAST(:id AS uuid)
                   AND revoked_at IS NULL AND used_at IS NULL AND expires_at > now()
                RETURNING id::text AS id, subject, display_name, facility_id::text AS facility_id,
                          roles, expires_at::text AS expires_at, 'revoked' AS status, invited_by
                """, new MapSqlParameterSource().addValue("id", id), INVITATION_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<String> accept(String token) {
        String subject = jdbc.queryForObject("SELECT public.accept_staff_invitation(:token)",
                new MapSqlParameterSource().addValue("token", token), String.class);
        return Optional.ofNullable(subject);
    }

    private static StaffInvitation mapInvitation(ResultSet resultSet, int rowNumber) throws SQLException {
        Array roles = resultSet.getArray("roles");
        List<String> roleKeys = Arrays.asList((String[]) roles.getArray());
        return new StaffInvitation(resultSet.getString("id"), resultSet.getString("subject"),
                resultSet.getString("display_name"), resultSet.getString("facility_id"),
                List.copyOf(roleKeys), resultSet.getString("expires_at"), resultSet.getString("status"),
                resultSet.getString("invited_by"));
    }
}
