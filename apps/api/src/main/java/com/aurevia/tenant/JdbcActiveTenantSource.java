package com.aurevia.tenant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL implementation of {@link com.aurevia.tenant.ActiveTenantSource}.
 *
 * <p>The only statement issued is a call to the narrow SECURITY DEFINER
 * function {@code public.active_organization_ids()}. No tenant context, tenant
 * GUC or tenant-owned table is referenced on this path, so the result is the
 * same for a scheduler with no context and for a request in any tenant. The
 * function returns identifiers only, never clinical or staff data.</p>
 */
@Repository
public class JdbcActiveTenantSource implements ActiveTenantSource {

    /** Qualified call to the authoritative enumerator; no other read is permitted. */
    static final String ACTIVE_ORGANIZATION_IDS_SQL =
            "SELECT organization_id FROM public.active_organization_ids()";

    private static final RowMapper<UUID> ORGANIZATION_ID_MAPPER = JdbcActiveTenantSource::mapOrganizationId;

    private final NamedParameterJdbcOperations jdbc;

    /**
     * Create the source over the shared named-parameter JDBC operations.
     *
     * @param jdbc shared JDBC operations, connected as the runtime role
     */
    public JdbcActiveTenantSource(NamedParameterJdbcOperations jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> activeOrganizationIds() {
        return List.copyOf(jdbc.query(ACTIVE_ORGANIZATION_IDS_SQL, ORGANIZATION_ID_MAPPER));
    }

    /** Read the function's single identifier column; malformed values fail closed. */
    private static UUID mapOrganizationId(ResultSet resultSet, int rowNumber) throws SQLException {
        return UUID.fromString(resultSet.getString("organization_id"));
    }
}
