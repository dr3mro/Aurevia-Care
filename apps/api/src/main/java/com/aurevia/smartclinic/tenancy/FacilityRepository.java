package com.aurevia.smartclinic.tenancy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.smartclinic.tenant.TenantContext;

/** The only SQL boundary for the G0 facility slice; every query is RLS-bounded. */
@Repository
public class FacilityRepository {

    private static final RowMapper<Facility> FACILITY_MAPPER = (resultSet, rowNumber) ->
            new Facility(resultSet.getString("organization_id"),
                    resultSet.getString("code"), resultSet.getString("name"));

    private final NamedParameterJdbcTemplate jdbc;

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = "EI_EXPOSE_REP2", justification = "Spring-managed singleton collaborator")
    public FacilityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Facility> findByCode(String code) {
        return jdbc.query("""
                SELECT organization_id, code, name FROM public.facility WHERE code = :code
                """, Map.of("code", code), FACILITY_MAPPER).stream().findFirst();
    }

    public int insert(Facility facility) {
        return jdbc.update("""
                INSERT INTO public.facility (organization_id, code, name)
                VALUES (CAST(:organizationId AS uuid), :code, :name)
                """, new MapSqlParameterSource()
                .addValue("organizationId", facility.organizationId())
                .addValue("code", facility.code())
                .addValue("name", facility.name()));
    }

    public boolean hasActiveMembership(String subject) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM public.membership m
                JOIN public.user_identity u
                  ON u.organization_id = m.organization_id AND u.id = m.user_id
                WHERE u.subject = :subject AND m.status = 'active'
                  AND m.valid_from <= now() AND (m.valid_to IS NULL OR m.valid_to > now())
                """, Map.of("subject", subject), Integer.class);
        return java.util.Optional.ofNullable(count).orElse(0) > 0;
    }

    public List<String> activeRoles(String subject) {
        return jdbc.queryForList("""
                SELECT DISTINCT ra.role_key FROM public.role_assignment ra
                JOIN public.membership m
                  ON m.organization_id = ra.organization_id AND m.id = ra.membership_id
                JOIN public.user_identity u
                  ON u.organization_id = m.organization_id AND u.id = m.user_id
                WHERE u.subject = :subject AND m.status = 'active'
                  AND m.valid_from <= now() AND (m.valid_to IS NULL OR m.valid_to > now())
                  AND ra.valid_from <= now() AND (ra.valid_to IS NULL OR ra.valid_to > now())
                """, Map.of("subject", subject), String.class);
    }

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
}
