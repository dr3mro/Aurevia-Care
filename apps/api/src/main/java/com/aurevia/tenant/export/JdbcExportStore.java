package com.aurevia.tenant.export;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aurevia.tenant.TenantContext;
import com.aurevia.tenant.export.ExportModel.AuditEntry;
import com.aurevia.tenant.export.ExportModel.ExportJob;
import com.aurevia.tenant.export.ExportModel.ExportSource;
import com.aurevia.tenant.export.ExportModel.Provenance;

/**
 * PostgreSQL implementation of the tenant export store (OPS-07).
 *
 * <p>All statements run inside the tenant-guarded transaction. The export job
 * table is tenant-owned with forced RLS declared in V89; a cross-tenant job id
 * therefore resolves to no row rather than to another tenant's artifact. Source
 * documents are taken only from completed clean artifacts with a release
 * decision under the current policy, and audit facts come from the append-only
 * OPS-02 chain for the active tenant.</p>
 */
@Repository
public class JdbcExportStore implements ExportStore {

    private static final RowMapper<ExportJob> JOB_MAPPER = JdbcExportStore::mapJob;
    private static final RowMapper<ExportSource> SOURCE_MAPPER = JdbcExportStore::mapSource;
    private static final RowMapper<AuditEntry> AUDIT_MAPPER = JdbcExportStore::mapAudit;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Create the store over the shared named-parameter JDBC template.
     *
     * @param jdbc shared JDBC template
     */
    public JdbcExportStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExportSource> authorizedSources(String policyVersion) {
        return jdbc.query("""
                SELECT a.id::text AS record_id, a.object_key AS object_id, a.object_version,
                       a.checksum, 'release_policy' AS authorization,
                       'documents' AS source_system, a.context_id AS source_id, a.version AS source_version
                  FROM public.document_artifact a
                  JOIN public.document_release r
                    ON r.organization_id = a.organization_id
                   AND r.revision_id = a.id::text
                   AND r.revision_version = a.version
                   AND r.policy_version = :policyVersion
                 WHERE a.state = 'completed' AND a.verdict = 'clean'
                 ORDER BY a.id
                """, new MapSqlParameterSource().addValue("policyVersion", policyVersion), SOURCE_MAPPER);
    }

    @Override
    public List<AuditEntry> auditFor(String recordId) {
        return jdbc.query("""
                SELECT seq, action, outcome
                  FROM public.audit_event
                 WHERE resource_id = :recordId
                 ORDER BY seq ASC
                """, new MapSqlParameterSource().addValue("recordId", recordId), AUDIT_MAPPER);
    }

    @Override
    public boolean save(ExportJob job) {
        TenantContext context = TenantContext.current();
        return jdbc.update("""
                INSERT INTO public.export_job
                    (organization_id, id, status, object_id, object_version, checksum,
                     created_at, expires_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :status, :objectId,
                        :objectVersion, :checksum, CAST(:createdAt AS timestamptz),
                        CAST(:expiresAt AS timestamptz))
                ON CONFLICT (organization_id, id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("organizationId", context.organizationId())
                .addValue("id", job.id())
                .addValue("status", job.status())
                .addValue("objectId", job.objectId())
                .addValue("objectVersion", job.objectVersion())
                .addValue("checksum", job.checksum())
                .addValue("createdAt", job.createdAt())
                .addValue("expiresAt", job.expiresAt())) == 1;
    }

    @Override
    public Optional<ExportJob> find(String jobId) {
        return jdbc.query("""
                SELECT id::text, status, object_id, object_version, checksum,
                       created_at::text, expires_at::text
                  FROM public.export_job
                 WHERE id = CAST(:id AS uuid)
                """, new MapSqlParameterSource().addValue("id", jobId), JOB_MAPPER)
                .stream().findFirst();
    }

    @Override
    public boolean markExpired(String jobId) {
        return jdbc.update("""
                UPDATE public.export_job SET status = 'EXPIRED'
                 WHERE id = CAST(:id AS uuid) AND status = 'COMPLETE'
                """, new MapSqlParameterSource().addValue("id", jobId)) == 1;
    }

    private static ExportJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExportJob(resultSet.getString("id"), resultSet.getString("status"),
                resultSet.getString("object_id"), resultSet.getInt("object_version"),
                resultSet.getString("checksum"), resultSet.getString("created_at"),
                resultSet.getString("expires_at"));
    }

    private static ExportSource mapSource(ResultSet resultSet, int rowNumber) throws SQLException {
        Provenance provenance = new Provenance(resultSet.getString("source_system"),
                resultSet.getString("source_id"), resultSet.getInt("source_version"));
        return new ExportSource(resultSet.getString("record_id"), resultSet.getString("object_id"),
                resultSet.getInt("object_version"), resultSet.getString("checksum"),
                resultSet.getString("authorization"), provenance);
    }

    private static AuditEntry mapAudit(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuditEntry(resultSet.getLong("seq"), resultSet.getString("action"),
                resultSet.getString("outcome"));
    }
}
