package com.aurevia.documents;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.ArtifactState;
import com.aurevia.documents.DocumentContract.DocumentAuthorization;
import com.aurevia.documents.DocumentContract.DocumentEvent;
import com.aurevia.documents.DocumentContract.EventType;
import com.aurevia.documents.DocumentContract.Language;
import com.aurevia.documents.DocumentContract.MediaType;
import com.aurevia.documents.DocumentContract.PdfBinding;
import com.aurevia.documents.DocumentContract.PdfJob;
import com.aurevia.documents.DocumentContract.PdfJobState;
import com.aurevia.documents.DocumentContract.ReleaseDecision;
import com.aurevia.documents.DocumentContract.ScanVerdict;

/**
 * PostgreSQL-backed persistence boundary for document artifacts and PDF renders
 * (ARC-06, CLN-07).
 *
 * <p>V81 owns the seven tenant tables and their forced row-level security. All
 * statements run in the tenant-guarded transaction, so forced RLS confines every
 * read and write to the active organization. The {@code organizationId} argument
 * is always the active tenant and is written explicitly on inserts so the
 * policy's {@code WITH CHECK} rejects a mismatch.</p>
 *
 * <p>Only metadata is persisted here: private object keys, immutable versions,
 * checksums and sizes travel through the object-store port, never as bytes in a
 * table. A synthetic grant whose claimed organization differs is stored in a
 * separate column so the service can reject it without weakening RLS.</p>
 */
@Repository
public class JdbcDocumentStore implements DocumentStore {

    private static final RowMapper<DocumentAuthorization> GRANT_MAPPER = JdbcDocumentStore::mapGrant;
    private static final RowMapper<Artifact> ARTIFACT_MAPPER = JdbcDocumentStore::mapArtifact;
    private static final RowMapper<PdfJob> JOB_MAPPER = JdbcDocumentStore::mapJob;
    private static final RowMapper<PdfBinding> BINDING_MAPPER = JdbcDocumentStore::mapBinding;
    private static final RowMapper<ReleaseDecision> RELEASE_MAPPER = JdbcDocumentStore::mapRelease;
    private static final RowMapper<DocumentEvent> EVENT_MAPPER = JdbcDocumentStore::mapEvent;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    /**
     * Create the PostgreSQL document store.
     *
     * @param jdbc         shared named-parameter JDBC template
     * @param transactions tenant-guarded transaction template
     */
    public JdbcDocumentStore(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Optional<DocumentAuthorization> authorization(String organizationId, String contextId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, context_id, grant_organization_id, patient_id, active
                  FROM public.document_authorization
                 WHERE organization_id = CAST(:organizationId AS uuid) AND context_id = :contextId
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("contextId", contextId), GRANT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putAuthorization(DocumentAuthorization grant) {
        writeAuthorization(grant.organizationId(), grant.contextId(), grant);
    }

    @Override
    public void putAuthorizationFor(String organizationId, String contextId, DocumentAuthorization grant) {
        writeAuthorization(organizationId, contextId, grant);
    }

    @Override
    public Optional<Artifact> artifact(String organizationId, String id) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, version, uploader_subject, context_id,
                       patient_id, file_name, file_type, size_bytes, checksum, object_key,
                       object_version, verdict, state, created_at
                  FROM public.document_artifact
                 WHERE organization_id = CAST(:organizationId AS uuid) AND id = CAST(:id AS uuid)
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", id), ARTIFACT_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putArtifact(Artifact artifact) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.document_artifact
                    (organization_id, id, version, uploader_subject, context_id, patient_id,
                     file_name, file_type, size_bytes, checksum, object_key, object_version,
                     verdict, state, created_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :version,
                        :uploaderSubject, :contextId, :patientId, :fileName, :fileType,
                        :sizeBytes, :checksum, :objectKey, :objectVersion, :verdict, :state,
                        CAST(:createdAt AS timestamptz))
                ON CONFLICT (organization_id, id) DO UPDATE SET
                    version = EXCLUDED.version,
                    verdict = EXCLUDED.verdict,
                    state = EXCLUDED.state,
                    checksum = EXCLUDED.checksum,
                    object_version = EXCLUDED.object_version
                """, new MapSqlParameterSource()
                        .addValue("organizationId", artifact.organizationId())
                        .addValue("id", artifact.id())
                        .addValue("version", artifact.version())
                        .addValue("uploaderSubject", artifact.uploaderSubject())
                        .addValue("contextId", artifact.contextId())
                        .addValue("patientId", artifact.patientId())
                        .addValue("fileName", artifact.fileName())
                        .addValue("fileType", artifact.fileType().contentType())
                        .addValue("sizeBytes", artifact.sizeBytes())
                        .addValue("checksum", artifact.checksum())
                        .addValue("objectKey", artifact.objectKey())
                        .addValue("objectVersion", artifact.objectVersion())
                        .addValue("verdict", artifact.verdict().wire())
                        .addValue("state", artifact.state().wire())
                        .addValue("createdAt", dateTime(artifact.createdAt()))));
    }

    @Override
    public boolean replaceArtifactIfVersion(String organizationId, String id,
                                            int expectedVersion, Artifact next) {
        return !transactions.execute(status -> jdbc.query("""
                UPDATE public.document_artifact
                   SET version = :nextVersion, verdict = :verdict, state = :state
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND id = CAST(:id AS uuid) AND version = :expectedVersion
                RETURNING id::text
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("id", id)
                        .addValue("expectedVersion", expectedVersion)
                        .addValue("nextVersion", next.version())
                        .addValue("verdict", next.verdict().wire())
                        .addValue("state", next.state().wire()),
                        (resultSet, rowNumber) -> resultSet.getString("id"))).isEmpty();
    }

    @Override
    public List<Artifact> artifacts(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, id::text, version, uploader_subject, context_id,
                       patient_id, file_name, file_type, size_bytes, checksum, object_key,
                       object_version, verdict, state, created_at
                  FROM public.document_artifact
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY created_at, id
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                ARTIFACT_MAPPER));
    }

    @Override
    public void putObject(String organizationId, String objectKey, int objectVersion,
                          String checksum, long sizeBytes) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.document_verified_object
                    (organization_id, object_key, object_version, checksum, size_bytes)
                VALUES (CAST(:organizationId AS uuid), :objectKey, :objectVersion, :checksum, :sizeBytes)
                ON CONFLICT (organization_id, object_key, object_version) DO UPDATE SET
                    checksum = EXCLUDED.checksum,
                    size_bytes = EXCLUDED.size_bytes
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("objectKey", objectKey)
                        .addValue("objectVersion", objectVersion)
                        .addValue("checksum", checksum)
                        .addValue("sizeBytes", sizeBytes)));
    }

    @Override
    public boolean verifyObject(String organizationId, String objectKey,
                                int objectVersion, String checksum) {
        return !transactions.execute(status -> jdbc.query("""
                SELECT object_key
                  FROM public.document_verified_object
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND object_key = :objectKey AND object_version = :objectVersion
                   AND checksum = :checksum
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("objectKey", objectKey)
                        .addValue("objectVersion", objectVersion)
                        .addValue("checksum", checksum),
                        (resultSet, rowNumber) -> resultSet.getString("object_key"))).isEmpty();
    }

    @Override
    public void removeObject(String organizationId, String objectKey, int objectVersion) {
        transactions.execute(status -> jdbc.update("""
                DELETE FROM public.document_verified_object
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND object_key = :objectKey AND object_version = :objectVersion
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("objectKey", objectKey)
                        .addValue("objectVersion", objectVersion)));
    }

    @Override
    public Optional<PdfJob> pdfJob(String organizationId, String jobId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, job_id, revision_id, revision_version,
                       content_checksum, template_id, language, state, version, object_key,
                       object_version, checksum, size_bytes, created_at
                  FROM public.document_pdf_job
                 WHERE organization_id = CAST(:organizationId AS uuid) AND job_id = :jobId
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("jobId", jobId), JOB_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<PdfJob> pdfJobs(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, job_id, revision_id, revision_version,
                       content_checksum, template_id, language, state, version, object_key,
                       object_version, checksum, size_bytes, created_at
                  FROM public.document_pdf_job
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY created_at, job_id
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                JOB_MAPPER));
    }

    @Override
    public void putPdfJob(PdfJob job) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.document_pdf_job
                    (organization_id, job_id, revision_id, revision_version, content_checksum,
                     template_id, language, state, version, object_key, object_version,
                     checksum, size_bytes, created_at)
                VALUES (CAST(:organizationId AS uuid), :jobId, :revisionId, :revisionVersion,
                        :contentChecksum, :templateId, :language, :state, :version, :objectKey,
                        :objectVersion, :checksum, :sizeBytes, CAST(:createdAt AS timestamptz))
                ON CONFLICT (organization_id, job_id) DO UPDATE SET
                    state = EXCLUDED.state,
                    version = EXCLUDED.version,
                    object_key = EXCLUDED.object_key,
                    object_version = EXCLUDED.object_version,
                    checksum = EXCLUDED.checksum,
                    size_bytes = EXCLUDED.size_bytes
                """, new MapSqlParameterSource()
                        .addValue("organizationId", job.organizationId())
                        .addValue("jobId", job.jobId())
                        .addValue("revisionId", job.revisionId())
                        .addValue("revisionVersion", job.revisionVersion())
                        .addValue("contentChecksum", job.contentChecksum())
                        .addValue("templateId", job.templateId())
                        .addValue("language", job.language().wire())
                        .addValue("state", job.state().wire())
                        .addValue("version", job.version())
                        .addValue("objectKey", job.objectKey())
                        .addValue("objectVersion", job.objectVersion())
                        .addValue("checksum", job.checksum())
                        .addValue("sizeBytes", job.sizeBytes())
                        .addValue("createdAt", dateTime(job.createdAt()))));
    }

    @Override
    public boolean replacePdfJobIfVersion(String organizationId, String jobId,
                                          int expectedVersion, PdfJob next) {
        return !transactions.execute(status -> jdbc.query("""
                UPDATE public.document_pdf_job
                   SET state = :nextState, version = :nextVersion, object_key = :objectKey,
                       object_version = :objectVersion, checksum = :checksum, size_bytes = :sizeBytes
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND job_id = :jobId AND version = :expectedVersion
                RETURNING job_id
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("jobId", jobId)
                        .addValue("expectedVersion", expectedVersion)
                        .addValue("nextState", next.state().wire())
                        .addValue("nextVersion", next.version())
                        .addValue("objectKey", next.objectKey())
                        .addValue("objectVersion", next.objectVersion())
                        .addValue("checksum", next.checksum())
                        .addValue("sizeBytes", next.sizeBytes()),
                        (resultSet, rowNumber) -> resultSet.getString("job_id"))).isEmpty();
    }

    @Override
    public Optional<PdfBinding> pdfBinding(String organizationId, String revisionId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, revision_id, revision_version, content_checksum,
                       job_id, object_key, object_version, checksum, language, bound_at
                  FROM public.document_pdf_binding
                 WHERE organization_id = CAST(:organizationId AS uuid) AND revision_id = :revisionId
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("revisionId", revisionId), BINDING_MAPPER)
                .stream().findFirst());
    }

    @Override
    public void putPdfBinding(PdfBinding binding) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.document_pdf_binding
                    (organization_id, revision_id, revision_version, content_checksum, job_id,
                     object_key, object_version, checksum, language, bound_at)
                VALUES (CAST(:organizationId AS uuid), :revisionId, :revisionVersion,
                        :contentChecksum, :jobId, :objectKey, :objectVersion, :checksum,
                        :language, CAST(:boundAt AS timestamptz))
                ON CONFLICT (organization_id, revision_id) DO NOTHING
                """, new MapSqlParameterSource()
                        .addValue("organizationId", binding.organizationId())
                        .addValue("revisionId", binding.revisionId())
                        .addValue("revisionVersion", binding.revisionVersion())
                        .addValue("contentChecksum", binding.contentChecksum())
                        .addValue("jobId", binding.jobId())
                        .addValue("objectKey", binding.objectKey())
                        .addValue("objectVersion", binding.objectVersion())
                        .addValue("checksum", binding.checksum())
                        .addValue("language", binding.language().wire())
                        .addValue("boundAt", dateTime(binding.boundAt()))));
    }

    @Override
    public Optional<ReleaseDecision> release(String organizationId, String revisionId,
                                             int revisionVersion, String policyVersion) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, revision_id, revision_version, content_checksum,
                       policy_version, released_by, released_at
                  FROM public.document_release
                 WHERE organization_id = CAST(:organizationId AS uuid)
                   AND revision_id = :revisionId AND revision_version = :revisionVersion
                   AND policy_version = :policyVersion
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("revisionId", revisionId)
                        .addValue("revisionVersion", revisionVersion)
                        .addValue("policyVersion", policyVersion), RELEASE_MAPPER)
                .stream().findFirst());
    }

    @Override
    public List<ReleaseDecision> releases(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT organization_id::text, revision_id, revision_version, content_checksum,
                       policy_version, released_by, released_at
                  FROM public.document_release
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY released_at, revision_id, policy_version
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                RELEASE_MAPPER));
    }

    @Override
    public boolean insertReleaseIfAbsent(ReleaseDecision decision) {
        return !transactions.execute(status -> jdbc.query("""
                INSERT INTO public.document_release
                    (organization_id, revision_id, revision_version, content_checksum,
                     policy_version, released_by, released_at)
                VALUES (CAST(:organizationId AS uuid), :revisionId, :revisionVersion,
                        :contentChecksum, :policyVersion, :releasedBy, CAST(:releasedAt AS timestamptz))
                ON CONFLICT (organization_id, revision_id, revision_version, policy_version)
                DO NOTHING
                RETURNING revision_id
                """, new MapSqlParameterSource()
                        .addValue("organizationId", decision.organizationId())
                        .addValue("revisionId", decision.revisionId())
                        .addValue("revisionVersion", decision.revisionVersion())
                        .addValue("contentChecksum", decision.contentChecksum())
                        .addValue("policyVersion", decision.policyVersion())
                        .addValue("releasedBy", decision.releasedBy())
                        .addValue("releasedAt", dateTime(decision.releasedAt())),
                        (resultSet, rowNumber) -> resultSet.getString("revision_id"))).isEmpty();
    }

    @Override
    public void appendEvent(DocumentEvent event) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.document_event
                    (organization_id, id, resource_id, event_type, actor_subject, outcome,
                     detail, occurred_at)
                VALUES (CAST(:organizationId AS uuid), CAST(:id AS uuid), :resourceId,
                        :eventType, :actorSubject, :outcome, :detail, CAST(:occurredAt AS timestamptz))
                """, new MapSqlParameterSource()
                        .addValue("organizationId", event.organizationId())
                        .addValue("id", event.id())
                        .addValue("resourceId", event.resourceId())
                        .addValue("eventType", event.eventType().wire())
                        .addValue("actorSubject", event.actorSubject())
                        .addValue("outcome", event.outcome())
                        .addValue("detail", event.detail())
                        .addValue("occurredAt", dateTime(event.occurredAt()))));
    }

    @Override
    public List<DocumentEvent> events(String organizationId) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id::text, organization_id::text, resource_id, event_type, actor_subject,
                       outcome, detail, occurred_at
                  FROM public.document_event
                 WHERE organization_id = CAST(:organizationId AS uuid)
                 ORDER BY seq
                """, new MapSqlParameterSource().addValue("organizationId", organizationId),
                EVENT_MAPPER));
    }

    private void writeAuthorization(String organizationId, String contextId,
                                    DocumentAuthorization grant) {
        transactions.execute(status -> jdbc.update("""
                INSERT INTO public.document_authorization
                    (organization_id, context_id, grant_organization_id, patient_id, active)
                VALUES (CAST(:organizationId AS uuid), :contextId, :grantOrganizationId,
                        :patientId, :active)
                ON CONFLICT (organization_id, context_id) DO UPDATE SET
                    grant_organization_id = EXCLUDED.grant_organization_id,
                    patient_id = EXCLUDED.patient_id,
                    active = EXCLUDED.active
                """, new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("contextId", contextId)
                        .addValue("grantOrganizationId", grant.organizationId())
                        .addValue("patientId", grant.patientId())
                        .addValue("active", grant.active())));
    }

    private static DocumentAuthorization mapGrant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new DocumentAuthorization(resultSet.getString("grant_organization_id"),
                resultSet.getString("context_id"),
                resultSet.getString("patient_id"),
                resultSet.getBoolean("active"));
    }

    private static Artifact mapArtifact(ResultSet resultSet, int rowNumber) throws SQLException {
        MediaType type = Objects.requireNonNull(
                MediaType.fromContentType(resultSet.getString("file_type")), "file_type");
        ScanVerdict verdict = Objects.requireNonNull(
                ScanVerdict.from(resultSet.getString("verdict")), "verdict");
        ArtifactState state = Objects.requireNonNull(
                ArtifactState.from(resultSet.getString("state")), "state");
        return new Artifact(resultSet.getString("organization_id"), resultSet.getString("id"),
                resultSet.getInt("version"), resultSet.getString("uploader_subject"),
                resultSet.getString("context_id"), resultSet.getString("patient_id"),
                resultSet.getString("file_name"), type, resultSet.getLong("size_bytes"),
                resultSet.getString("checksum"), resultSet.getString("object_key"),
                resultSet.getInt("object_version"), verdict, state,
                instant(resultSet, "created_at"));
    }

    private static PdfJob mapJob(ResultSet resultSet, int rowNumber) throws SQLException {
        Language language = Objects.requireNonNull(
                Language.from(resultSet.getString("language")), "language");
        PdfJobState state = Objects.requireNonNull(
                PdfJobState.from(resultSet.getString("state")), "job state");
        return new PdfJob(resultSet.getString("organization_id"), resultSet.getString("job_id"),
                resultSet.getString("revision_id"), resultSet.getInt("revision_version"),
                resultSet.getString("content_checksum"), resultSet.getString("template_id"),
                language, state, resultSet.getInt("version"), resultSet.getString("object_key"),
                resultSet.getInt("object_version"), resultSet.getString("checksum"),
                resultSet.getLong("size_bytes"), instant(resultSet, "created_at"));
    }

    private static PdfBinding mapBinding(ResultSet resultSet, int rowNumber) throws SQLException {
        Language language = Objects.requireNonNull(
                Language.from(resultSet.getString("language")), "language");
        return new PdfBinding(resultSet.getString("organization_id"), resultSet.getString("revision_id"),
                resultSet.getInt("revision_version"), resultSet.getString("content_checksum"),
                resultSet.getString("job_id"), resultSet.getString("object_key"),
                resultSet.getInt("object_version"), resultSet.getString("checksum"),
                language, instant(resultSet, "bound_at"));
    }

    private static ReleaseDecision mapRelease(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ReleaseDecision(resultSet.getString("organization_id"),
                resultSet.getString("revision_id"), resultSet.getInt("revision_version"),
                resultSet.getString("content_checksum"), resultSet.getString("policy_version"),
                resultSet.getString("released_by"), instant(resultSet, "released_at"));
    }

    private static DocumentEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        EventType type = Objects.requireNonNull(
                EventType.from(resultSet.getString("event_type")), "event_type");
        return new DocumentEvent(resultSet.getString("id"), resultSet.getString("organization_id"),
                resultSet.getString("resource_id"), type, resultSet.getString("actor_subject"),
                resultSet.getString("outcome"), resultSet.getString("detail"),
                instant(resultSet, "occurred_at"));
    }

    private static OffsetDateTime dateTime(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
