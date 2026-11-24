package com.aurevia.tenancy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;

/**
 * The single SQL boundary for the G0 facility slice.
 *
 * <p>Every statement is bounded by the transaction-local tenant context set by
 * {@link com.aurevia.tenant.TenantGuardTransactionManager}; forced
 * row-level security is the authoritative isolation control.</p>
 */
@Repository
public class FacilityRepository {

    private static final RowMapper<Facility> FACILITY_MAPPER = (resultSet, rowNumber) ->
            new Facility(resultSet.getString("organization_id"),
                    resultSet.getString("code"), resultSet.getString("name"),
                    resultSet.getString("time_zone"));

    private static final RowMapper<FacilityRow> FACILITY_ROW_MAPPER = (resultSet, rowNumber) ->
            new FacilityRow(FACILITY_MAPPER.mapRow(resultSet, rowNumber), resultSet.getString("id"));

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the repository.
     *
     * @param jdbc shared named-parameter JDBC template
     */
    public FacilityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Find a facility by tenant-local code in the active organization.
     *
     * @param code tenant-local facility code
     * @return the facility when visible, otherwise empty
     */
    public Optional<Facility> findByCode(String code) {
        return jdbc.query("""
                SELECT organization_id, code, name, time_zone FROM public.facility WHERE code = :code
                """, Map.of("code", code), FACILITY_MAPPER).stream().findFirst();
    }

    /**
     * Read one cursor-paged slice of the active tenant's facilities.
     *
     * <p>Forced row-level security confines the query to the active organization,
     * so no manual organization predicate is needed. Ordering by code with the
     * immutable row id as tie-break keeps the cursor stable even when codes
     * repeat across organizations.</p>
     *
     * @param cursorCode exclusive lower code bound, or {@code null} for the first page
     * @param cursorId   immutable id that breaks a tie at the cursor code
     * @param limit      maximum rows to fetch (one more than the requested page size)
     * @return facilities with their immutable row id, in code-ascending order
     */
    public List<FacilityRow> query(String cursorCode, String cursorId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder sql = new StringBuilder("""
                SELECT organization_id, code, name, time_zone, id::text AS id
                  FROM public.facility
                 WHERE 1=1
                """);
        if (cursorCode != null) {
            sql.append("""
                     AND (code > :cursorCode OR (code = :cursorCode AND id > CAST(:cursorId AS uuid)))
                    """);
            params.addValue("cursorCode", cursorCode).addValue("cursorId", cursorId);
        }
        sql.append(" ORDER BY code, id LIMIT :limit");
        return jdbc.query(sql.toString(), params, FACILITY_ROW_MAPPER);
    }

    /**
     * Insert a facility owned by the active organization.
     *
     * @param facility facility to insert
     * @return number of inserted rows
     */
    public int insert(Facility facility) {
        return jdbc.update("""
                INSERT INTO public.facility (organization_id, code, name, time_zone)
                VALUES (CAST(:organizationId AS uuid), :code, :name, :timeZone)
                """, new MapSqlParameterSource()
                .addValue("organizationId", facility.organizationId())
                .addValue("code", facility.code())
                .addValue("name", facility.name())
                .addValue("timeZone", facility.timeZone()));
    }

    /**
     * Check whether the subject has an active membership in the active organization.
     *
     * @param subject authenticated subject
     * @return {@code true} when an active membership is visible
     */
    public boolean hasActiveMembership(String subject) {
        Boolean active = jdbc.queryForObject(
                "SELECT public.has_active_membership(:subject)", Map.of("subject", subject), Boolean.class);
        return Boolean.TRUE.equals(active);
    }

    /**
     * List active role keys for the subject in the active organization.
     *
     * @param subject authenticated subject
     * @return distinct role keys with active membership and assignment windows
     */
    public List<String> activeRoles(String subject) {
        return jdbc.queryForList(
                "SELECT role_key FROM public.active_roles(:subject)", Map.of("subject", subject), String.class);
    }

    /**
     * Append one audit event inside the caller's transaction.
     *
     * @param context      active tenant context
     * @param action       action key
     * @param resourceType audited resource type
     * @param resourceId   audited resource identifier
     * @param outcome      success or denial outcome
     */
    public void appendAudit(TenantContext context, String action, String resourceType,
                            String resourceId, String outcome) {
        jdbc.update("""
                INSERT INTO public.audit_event
                    (organization_id, actor_id, action, resource_type, resource_id,
                     purpose, scope, correlation_id, outcome)
                VALUES (CAST(:organizationId AS uuid), NULL, :action, :resourceType, :resourceId,
                        :purpose, :scope, :correlationId, :outcome)
                """, new MapSqlParameterSource()
                .addValue("organizationId", context.organizationId())
                .addValue("action", action)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("purpose", context.purpose())
                .addValue("scope", "FACILITY")
                .addValue("correlationId", context.correlationId())
                .addValue("outcome", outcome));
    }

    /**
     * One paged row: the facility projection and its immutable row id.
     *
     * @param facility facility projection
     * @param id       immutable facility id used as the cursor tie-break
     * @return this record
     */
    public record FacilityRow(Facility facility, String id) {
    }
}
