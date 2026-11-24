package com.aurevia.staff;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for staff lifecycle commands.
 *
 * <p>Every statement runs inside the tenant-guarded transaction. The forced RLS
 * policy set in V2 confines all reads and writes to the active organization;
 * V4 makes membership and role history immutable and folds current status from
 * the latest effective event.</p>
 */
@Repository
public class StaffRepository {

    /** Business purpose stamped on staff administration audit events (OPS-02). */
    public static final String PURPOSE = "STAFF_ADMIN";
    /** Audit resource scope for staff lifecycle events (OPS-02). */
    public static final String SCOPE = "STAFF";

    private static final RowMapper<StaffMember> MEMBER_MAPPER = StaffRepository::mapMember;
    private static final RowMapper<StaffRow> STAFF_ROW_MAPPER = StaffRepository::mapRow;
    private static final RowMapper<StaffHistory.MembershipEvent> MEMBERSHIP_MAPPER =
            StaffRepository::mapMembership;
    private static final RowMapper<StaffHistory.RoleGrant> ROLE_GRANT_MAPPER = StaffRepository::mapRoleGrant;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public StaffRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Check active membership using the authoritative database fold.
     *
     * @param subject authenticated subject
     * @return {@code true} when the latest effective event is an active grant
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
     * Append a new user identity for an invitation.
     *
     * @param organizationId active organization identifier
     * @param subject        invited subject
     * @param displayName    invited display name
     * @return the generated user identifier
     */
    public String insertIdentity(String organizationId, String subject, String displayName) {
        return jdbc.queryForObject("""
                INSERT INTO public.user_identity (organization_id, subject, display_name)
                VALUES (CAST(:organizationId AS uuid), :subject, :displayName)
                RETURNING id::text
                """, params(organizationId).addValue("subject", subject).addValue("displayName", displayName),
                String.class);
    }

    /**
     * Append a membership event; never updates an existing row.
     *
     * @param organizationId active organization identifier
     * @param userId         target user identifier
     * @param status         {@code active} for an invite, {@code revoked} for deactivation
     * @return the generated membership event identifier
     */
    public String insertMembership(String organizationId, String userId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO public.membership (organization_id, user_id, status)
                VALUES (CAST(:organizationId AS uuid), CAST(:userId AS uuid), :status)
                RETURNING id::text
                """, params(organizationId).addValue("userId", userId).addValue("status", status), String.class);
    }

    /**
     * Append a role grant carrying grantor and effective dates.
     *
     * @param organizationId active organization identifier
     * @param membershipId   membership event the grant belongs to
     * @param roleKey        bounded role key
     * @param grantedBy      grantor user identifier
     * @return number of inserted rows
     */
    public int insertRoleGrant(String organizationId, String membershipId, String roleKey, String grantedBy) {
        return jdbc.update("""
                INSERT INTO public.role_assignment
                    (organization_id, membership_id, role_key, granted_by, valid_from)
                VALUES (CAST(:organizationId AS uuid), CAST(:membershipId AS uuid), :roleKey,
                        CAST(:grantedBy AS uuid), now())
                """, params(organizationId).addValue("membershipId", membershipId)
                .addValue("roleKey", roleKey).addValue("grantedBy", grantedBy));
    }

    /**
     * Read the current effective status of a subject in the active organization.
     *
     * @param subject staff subject
     * @return the raw database status, or empty when the subject is not visible
     */
    public Optional<String> currentStatus(String subject) {
        return jdbc.query("""
                SELECT cm.status FROM public.current_membership() cm
                JOIN public.user_identity u
                  ON u.organization_id = cm.organization_id AND u.id = cm.user_id
                WHERE u.subject = :subject
                """, Map.of("subject", subject), (resultSet, rowNumber) -> resultSet.getString(1))
                .stream().findFirst();
    }

    /**
     * Read the effective projection of a staff member.
     *
     * @param subject staff subject
     * @return the member when visible, otherwise empty
     */
    public Optional<StaffMember> findBySubject(String subject) {
        return jdbc.query("""
                SELECT u.subject, u.display_name, cm.status,
                       coalesce((SELECT array_agg(ra.role_key ORDER BY ra.role_key)
                                   FROM public.role_assignment ra
                                  WHERE ra.organization_id = cm.organization_id
                                    AND ra.membership_id = cm.membership_id
                                    AND ra.valid_from <= now()
                                    AND (ra.valid_to IS NULL OR ra.valid_to > now())), '{}') AS roles
                  FROM public.user_identity u
                  JOIN public.current_membership() cm
                    ON cm.organization_id = u.organization_id AND cm.user_id = u.id
                 WHERE u.subject = :subject
                """, Map.of("subject", subject), MEMBER_MAPPER).stream().findFirst();
    }

    /**
     * Read the immutable membership events for a subject.
     *
     * @param subject staff subject
     * @return events in effective-time order
     */
    public List<StaffHistory.MembershipEvent> findMembershipEvents(String subject) {
        return jdbc.query("""
                SELECT m.id::text AS id, m.status, m.valid_from::text AS valid_from,
                       m.valid_to::text AS valid_to, m.created_at::text AS created_at
                  FROM public.membership m
                  JOIN public.user_identity u
                    ON u.organization_id = m.organization_id AND u.id = m.user_id
                 WHERE u.subject = :subject
                 ORDER BY m.valid_from, m.created_at
                """, Map.of("subject", subject), MEMBERSHIP_MAPPER);
    }

    /**
     * Read the immutable role grants for a subject.
     *
     * @param subject staff subject
     * @return grants in effective-time order
     */
    public List<StaffHistory.RoleGrant> findRoleGrants(String subject) {
        return jdbc.query("""
                SELECT ra.id::text AS id, ra.role_key, ra.granted_by::text AS granted_by,
                       ra.valid_from::text AS valid_from, ra.valid_to::text AS valid_to
                  FROM public.role_assignment ra
                  JOIN public.membership m
                    ON m.organization_id = ra.organization_id AND m.id = ra.membership_id
                  JOIN public.user_identity u
                    ON u.organization_id = m.organization_id AND u.id = m.user_id
                 WHERE u.subject = :subject
                 ORDER BY ra.valid_from
                """, Map.of("subject", subject), ROLE_GRANT_MAPPER);
    }

    /**
     * Read one cursor-paged slice of the active tenant's staff members.
     *
     * <p>Forced RLS confines the query to the active organization, so no manual
     * organization predicate is needed. Ordering by subject with the immutable
     * user id as tie-break makes the cursor stable even when subjects repeat.</p>
     *
     * @param cursorSubject    exclusive lower subject bound, or {@code null} for the first page
     * @param cursorId         immutable id that breaks a tie at the cursor subject
     * @param membershipStatus optional exact database membership status filter
     * @param limit            maximum rows to fetch (one more than the requested page size)
     * @return members with their immutable row id, in subject-ascending order
     */
    public List<StaffRow> query(String cursorSubject, String cursorId, String membershipStatus, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                SELECT u.subject, u.display_name, cm.status,
                       coalesce((SELECT array_agg(ra.role_key ORDER BY ra.role_key)
                                   FROM public.role_assignment ra
                                  WHERE ra.organization_id = cm.organization_id
                                    AND ra.membership_id = cm.membership_id
                                    AND ra.valid_from <= now()
                                    AND (ra.valid_to IS NULL OR ra.valid_to > now())), '{}') AS roles,
                       u.id::text AS id
                  FROM public.user_identity u
                  JOIN public.current_membership() cm
                    ON cm.organization_id = u.organization_id AND cm.user_id = u.id
                 WHERE 1=1
                """);
        if (cursorSubject != null) {
            sql.append("""
                     AND (u.subject > :cursorSubject
                          OR (u.subject = :cursorSubject AND u.id > CAST(:cursorId AS uuid)))
                    """);
            params.addValue("cursorSubject", cursorSubject).addValue("cursorId", cursorId);
        }
        if (membershipStatus != null) {
            sql.append(" AND cm.status = :membershipStatus");
            params.addValue("membershipStatus", membershipStatus);
        }
        sql.append(" ORDER BY u.subject, u.id LIMIT :limit");
        return jdbc.query(sql.toString(), params, STAFF_ROW_MAPPER);
    }

    /**
     * Append one OPS-02 audit event inside the caller's transaction.
     *
     * @param context    active tenant context
     * @param action     staff action key
     * @param resourceId staff subject addressed by the command
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

    private static StaffRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StaffRow(mapMember(resultSet, rowNumber), resultSet.getString("id"));
    }

    /**
     * One paged row: the effective member projection and its immutable row id.
     *
     * @param member effective staff projection
     * @param id     immutable user-identity id used as the cursor tie-break
     * @return this record
     */
    public record StaffRow(StaffMember member, String id) {
    }

    private static StaffMember mapMember(ResultSet resultSet, int rowNumber) throws SQLException {
        // The query COALESCEs the aggregate to '{}', so the array is always present.
        Array roles = resultSet.getArray("roles");
        List<String> roleKeys = Arrays.asList((String[]) roles.getArray());
        String status = resultSet.getString("status");
        return new StaffMember(resultSet.getString("subject"), resultSet.getString("display_name"),
                "revoked".equals(status) ? "deactivated" : status, List.copyOf(roleKeys));
    }

    private static StaffHistory.MembershipEvent mapMembership(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new StaffHistory.MembershipEvent(resultSet.getString("id"), resultSet.getString("status"),
                resultSet.getString("valid_from"), resultSet.getString("valid_to"),
                resultSet.getString("created_at"));
    }

    private static StaffHistory.RoleGrant mapRoleGrant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StaffHistory.RoleGrant(resultSet.getString("id"), resultSet.getString("role_key"),
                resultSet.getString("granted_by"), resultSet.getString("valid_from"),
                resultSet.getString("valid_to"));
    }
}
