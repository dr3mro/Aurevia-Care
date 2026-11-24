package com.aurevia.tenant.export;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.documents.storage.ObjectKey;
import com.aurevia.documents.storage.ObjectStorePort;
import com.aurevia.tenant.TenantContext;
import com.aurevia.tenant.export.ExportModel.ExportedDocument;
import com.aurevia.tenant.export.ExportModel.ExportJob;
import com.aurevia.tenant.export.ExportModel.ExportRecord;
import com.aurevia.tenant.export.ExportModel.ExportResult;
import com.aurevia.tenant.export.ExportModel.ExportSource;
import com.aurevia.tenant.export.ExportModel.Manifest;

/**
 * Tenant export orchestration (OPS-07).
 *
 * <p>A tenant admin starts one job for the active organization. Only documents
 * released under the current policy are read, their bytes are re-checksummed,
 * and the manifest carries schema, policy, provenance and audit history before
 * it is promoted in the private object store. Every start, download and expiry
 * is appended to the OPS-02 audit chain. A downloaded artifact is refused as
 * soon as its job is expired or its retention instant has passed, and the object
 * is removed on expiry.</p>
 */
@Service
public class TenantExportService {

    /** Evidence-envelope schema version published by the evaluator. */
    public static final String ENVELOPE_SCHEMA_VERSION = "1.0";
    /** Fixed in-code stand-in for the market policy pack that authorizes a release. */
    public static final String POLICY_VERSION = "export-policy-1.0";
    /** Retention of a promoted export artifact. */
    public static final Duration RETENTION = Duration.ofHours(24);

    private static final String PURPOSE = "TENANT_EXPORT";
    private static final String RESOURCE_TYPE = "EXPORT";
    private static final String SCOPE = "TENANT_EXPORT";

    private final ExportStore store;
    private final ObjectStorePort objects;
    private final ExportDigests digests;
    private final ExportPermissionGuard authorization;
    private final AuditAppender audit;
    private final Clock clock;

    /**
     * Create the export service with injected boundaries and a clock.
     *
     * @param store         durable job and source store
     * @param objects       private object-store port
     * @param digests       canonical checksum primitive
     * @param authorization fresh export permission check
     * @param audit         append-only audit appender
     * @param clock         clock used for timestamps and expiry decisions
     */
    public TenantExportService(ExportStore store, ObjectStorePort objects, ExportDigests digests,
                               ExportPermissionGuard authorization, AuditAppender audit, Clock clock) {
        this.store = store;
        this.objects = objects;
        this.digests = digests;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Build and promote a tenant export artifact for the active organization.
     *
     * @return opaque job identifier, manifest checksum and expiry instant
     * @throws ApiException when the caller is denied, no document is authorized,
     *                      a source cannot be verified, or the job is not durable
     */
    public ExportResult start() {
        TenantContext context = authorize("start");
        List<ExportSource> sources = store.authorizedSources(POLICY_VERSION);
        if (sources.isEmpty()) {
            throw new ApiException(404, "NO_AUTHORIZED_DOCUMENTS", "no document is authorized for export");
        }
        List<ExportRecord> records = new ArrayList<>();
        for (ExportSource source : sources) {
            ObjectKey sourceKey = new ObjectKey(context.organizationId(),
                    source.objectId(), source.objectVersion());
            byte[] content = objects.getVerified(sourceKey, source.checksum())
                    .orElseThrow(() -> new ApiException(409, "EXPORT_SOURCE_MISSING",
                            "an authorized source is unavailable"));
            String checksum = digests.sha256Hex(content);
            if (!checksum.equals(source.checksum())) {
                throw new ApiException(409, "EXPORT_CHECKSUM_MISMATCH",
                        "source bytes do not match their recorded checksum");
            }
            // The manifest cites the verified source and its checksum. Copying the
            // bytes here would create an unreferenced object with no expiry path:
            // only the manifest key is durable and only that object can be removed.
            records.add(new ExportRecord(source.recordId(), checksum, source.provenance(),
                    List.of(new ExportedDocument(source.objectId(), checksum,
                            source.authorization(), POLICY_VERSION)),
                    store.auditFor(source.recordId())));
        }

        Instant now = clock.instant();
        Manifest manifest = new Manifest(ENVELOPE_SCHEMA_VERSION, POLICY_VERSION,
                context.organizationId(), now.toString(), records.size(), records);
        byte[] manifestBytes = manifest.bytes();
        String manifestChecksum = digests.sha256Hex(manifestBytes);
        ObjectKey manifestKey = ObjectKey.generate(context.organizationId(), 1);
        objects.putInQuarantine(manifestKey, manifestBytes);
        objects.promote(manifestKey, manifestChecksum);

        ExportJob job = new ExportJob(UUID.randomUUID().toString(), "COMPLETE",
                manifestKey.objectId(), manifestKey.version(), manifestChecksum,
                now.toString(), now.plus(RETENTION).toString());
        if (!store.save(job)) {
            // The manifest is already promoted. Without a durable job row nothing
            // can expire this object, so remove it here rather than leak bytes that
            // carry provenance and audit history with no cleanup path.
            objects.delete(manifestKey);
            throw new ApiException(409, "EXPORT_STATE_CONFLICT", "export job was not persisted");
        }
        audit.append(context, PURPOSE, "EXPORT_START", RESOURCE_TYPE, SCOPE, job.id(), "SUCCESS");
        return new ExportResult(job.id(), manifestChecksum, records.size(), job.expiresAt());
    }

    /**
     * Download the manifest of a completed, unexpired job in the active tenant.
     *
     * @param jobId opaque job identifier
     * @return verified manifest bytes
     * @throws ApiException when denied, unknown, expired, or the object is gone
     */
    public byte[] download(String jobId) {
        TenantContext context = authorize("download");
        ExportJob job = findJob(jobId);
        if (job.expired(clock.instant())) {
            throw new ApiException(410, "EXPORT_EXPIRED", "export artifact has expired");
        }
        ObjectKey key = new ObjectKey(context.organizationId(), job.objectId(), job.objectVersion());
        byte[] manifest = objects.getVerified(key, job.checksum())
                .orElseThrow(() -> new ApiException(409, "EXPORT_OBJECT_MISSING",
                        "export artifact is unavailable"));
        audit.append(context, PURPOSE, "EXPORT_DOWNLOAD", RESOURCE_TYPE, SCOPE, jobId, "SUCCESS");
        return manifest;
    }

    /**
     * Expire a job, remove its object and record the event.
     *
     * <p>The durable status transition is the gate: the row is marked EXPIRED
     * before the object is removed. If that sequence is interrupted, the row is already
     * EXPIRED and a retry still removes the orphaned object; only a row in no other
     * recognized lifecycle state is a conflict.</p>
     *
     * @param jobId opaque job identifier
     * @throws ApiException when denied, unknown, or the job is in neither lifecycle state
     */
    public void expire(String jobId) {
        TenantContext context = authorize("expire");
        ExportJob job = findJob(jobId);
        // A false result means the row is no longer COMPLETE. With only two lifecycle
        // states that is EXPIRED, i.e. an earlier expiry interrupted before the object
        // was removed. Such a retry must still clean up the object rather than conflict,
        // otherwise the manifest with its provenance and audit history is orphaned.
        if (!store.markExpired(jobId) && !job.isExpired()) {
            throw new ApiException(409, "EXPORT_STATE_CONFLICT", "export job state changed");
        }
        ObjectKey key = new ObjectKey(context.organizationId(), job.objectId(), job.objectVersion());
        // The row is now EXPIRED. A missing promoted object is already the desired end
        // state (for example after an earlier interrupted expiry), so its false result
        // is not a conflict; the durable transition is the single source of truth.
        objects.expire(key);
        audit.append(context, PURPOSE, "EXPORT_EXPIRE", RESOURCE_TYPE, SCOPE, jobId, "SUCCESS");
    }

    private TenantContext authorize(String action) {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(403, "FORBIDDEN", "no tenant context");
        }
        if (!authorization.mayExport(context)) {
            audit.append(context, PURPOSE, "EXPORT_DENIED", RESOURCE_TYPE, SCOPE, action, "DENIED");
            throw new ApiException(403, "FORBIDDEN", "no export permission");
        }
        return context;
    }

    private ExportJob findJob(String jobId) {
        return store.find(jobId)
                .orElseThrow(() -> new ApiException(404, "EXPORT_NOT_FOUND", "export job not found"));
    }
}
