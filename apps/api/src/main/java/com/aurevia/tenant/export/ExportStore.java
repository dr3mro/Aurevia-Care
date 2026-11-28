package com.aurevia.tenant.export;

import java.util.List;
import java.util.Optional;

import com.aurevia.tenant.export.ExportModel.AuditEntry;
import com.aurevia.tenant.export.ExportModel.ExportJob;
import com.aurevia.tenant.export.ExportModel.ExportSource;

/**
 * Durable boundary for tenant export jobs and their authorized sources.
 *
 * <p>Every method runs in the active tenant context; forced row-level security
 * confines each statement to one organization so a job or audit fact from
 * another tenant cannot be read through this port.</p>
 */
public interface ExportStore {

    /**
     * List documents authorized for export under a policy version.
     *
     * @param policyVersion authorizing policy version
     * @return authorized sources in a stable order
     */
    List<ExportSource> authorizedSources(String policyVersion);

    /**
     * Read the append-only audit history of one exported record.
     *
     * @param recordId tenant-local record identifier
     * @return audit facts in increasing sequence order
     */
    List<AuditEntry> auditFor(String recordId);

    /**
     * Persist a completed export job exactly once.
     *
     * @param job job metadata
     * @return {@code true} when the row was inserted
     */
    boolean save(ExportJob job);

    /**
     * Find a job visible in the active tenant.
     *
     * @param jobId opaque job identifier
     * @return the job, or empty when it is absent or belongs to another tenant
     */
    Optional<ExportJob> find(String jobId);

    /**
     * Mark a completed job as expired.
     *
     * @param jobId opaque job identifier
     * @return {@code true} when a completed job was updated
     */
    boolean markExpired(String jobId);
}
