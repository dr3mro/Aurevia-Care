package com.aurevia.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
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
 * Deterministic mapping and boundary coverage for the PostgreSQL document store.
 *
 * <p>The mocked boundary forces the empty-result and false-compare-and-set sides
 * that a real database race cannot produce deterministically. Normal paths and
 * tenant isolation are proven against real PostgreSQL in
 * {@code JdbcDocumentStoreIntegrationTest}. Every value is synthetic.</p>
 */
class JdbcDocumentStoreTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String FOREIGN_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String CONTEXT = "ctx-1";
    private static final String ARTIFACT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String JOB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REVISION = "rev-1";
    private static final String EVENT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";
    private static final String CHECKSUM = "a".repeat(64);
    private static final String RENDER_CHECKSUM = "c".repeat(64);
    private static final String OBJECT_KEY = "quarantine/" + ORG + "/" + ARTIFACT + "/v1";
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcDocumentStore store;
    private boolean rowPresent;
    private boolean casReturnsRow;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        store = new JdbcDocumentStore(jdbc, transactions);
        rowPresent = true;
        casReturnsRow = true;
        answerQueries();
    }

    @SuppressWarnings("unchecked")
    private void answerQueries() {
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    if (sql.contains("RETURNING")) {
                        if (!casReturnsRow) {
                            return List.of();
                        }
                        ResultSet row = mock(ResultSet.class);
                        when(row.getString("id")).thenReturn("id");
                        when(row.getString("job_id")).thenReturn("id");
                        when(row.getString("revision_id")).thenReturn("id");
                        return List.of(mapper.mapRow(row, 1));
                    }
                    if (sql.contains("document_verified_object")) {
                        if (!rowPresent) {
                            return List.of();
                        }
                        ResultSet row = mock(ResultSet.class);
                        when(row.getString("object_key")).thenReturn("key");
                        return List.of(mapper.mapRow(row, 1));
                    }
                    if (!rowPresent) {
                        return List.of();
                    }
                    if (sql.contains("document_event")) {
                        return List.of(mapper.mapRow(eventRow(), 1));
                    }
                    if (sql.contains("document_release")) {
                        return List.of(mapper.mapRow(releaseRow(), 1));
                    }
                    if (sql.contains("document_pdf_binding")) {
                        return List.of(mapper.mapRow(bindingRow(), 1));
                    }
                    if (sql.contains("document_pdf_job")) {
                        return List.of(mapper.mapRow(jobRow(), 1));
                    }
                    if (sql.contains("document_artifact")) {
                        return List.of(mapper.mapRow(artifactRow(), 1));
                    }
                    return List.of(mapper.mapRow(grantRow(), 1));
                });
    }

    private static DocumentAuthorization grant() {
        return new DocumentAuthorization(ORG, CONTEXT, "pat-1", true);
    }

    private static Artifact artifact() {
        return new Artifact(ORG, ARTIFACT, 1, "patient-a", CONTEXT, "pat-1", "notes.pdf",
                MediaType.PDF, 128, CHECKSUM, OBJECT_KEY, 1, ScanVerdict.PENDING,
                ArtifactState.QUARANTINED, NOW);
    }

    private static PdfJob job() {
        return new PdfJob(ORG, JOB, REVISION, 1, CHECKSUM, "tpl-1", Language.EN,
                PdfJobState.REQUESTED, 1, null, 0, null, 0, NOW);
    }

    private static PdfBinding binding() {
        return new PdfBinding(ORG, REVISION, 1, CHECKSUM, JOB, "rendered/v1", 1,
                RENDER_CHECKSUM, Language.AR, NOW);
    }

    private static ReleaseDecision release() {
        return new ReleaseDecision(ORG, REVISION, 1, CHECKSUM, "policy-1", "reviewer-a", NOW);
    }

    private static DocumentEvent event() {
        return new DocumentEvent(EVENT, ORG, ARTIFACT, EventType.UPLOAD_ACCEPTED,
                "patient-a", "success", null, NOW);
    }

    private static ResultSet grantRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("context_id")).thenReturn(CONTEXT);
        when(row.getString("grant_organization_id")).thenReturn(ORG);
        when(row.getString("patient_id")).thenReturn("pat-1");
        when(row.getBoolean("active")).thenReturn(true);
        return row;
    }

    private static ResultSet artifactRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("id")).thenReturn(ARTIFACT);
        when(row.getInt("version")).thenReturn(1);
        when(row.getString("uploader_subject")).thenReturn("patient-a");
        when(row.getString("context_id")).thenReturn(CONTEXT);
        when(row.getString("patient_id")).thenReturn("pat-1");
        when(row.getString("file_name")).thenReturn("notes.pdf");
        when(row.getString("file_type")).thenReturn("application/pdf");
        when(row.getLong("size_bytes")).thenReturn(128L);
        when(row.getString("checksum")).thenReturn(CHECKSUM);
        when(row.getString("object_key")).thenReturn(OBJECT_KEY);
        when(row.getInt("object_version")).thenReturn(1);
        when(row.getString("verdict")).thenReturn("pending");
        when(row.getString("state")).thenReturn("quarantined");
        when(row.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    private static ResultSet jobRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("job_id")).thenReturn(JOB);
        when(row.getString("revision_id")).thenReturn(REVISION);
        when(row.getInt("revision_version")).thenReturn(1);
        when(row.getString("content_checksum")).thenReturn(CHECKSUM);
        when(row.getString("template_id")).thenReturn("tpl-1");
        when(row.getString("language")).thenReturn("en");
        when(row.getString("state")).thenReturn("requested");
        when(row.getInt("version")).thenReturn(1);
        when(row.getString("object_key")).thenReturn(null);
        when(row.getInt("object_version")).thenReturn(0);
        when(row.getString("checksum")).thenReturn(null);
        when(row.getLong("size_bytes")).thenReturn(0L);
        when(row.getObject("created_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    private static ResultSet bindingRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("revision_id")).thenReturn(REVISION);
        when(row.getInt("revision_version")).thenReturn(1);
        when(row.getString("content_checksum")).thenReturn(CHECKSUM);
        when(row.getString("job_id")).thenReturn(JOB);
        when(row.getString("object_key")).thenReturn("rendered/v1");
        when(row.getInt("object_version")).thenReturn(1);
        when(row.getString("checksum")).thenReturn(RENDER_CHECKSUM);
        when(row.getString("language")).thenReturn("ar");
        when(row.getObject("bound_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    private static ResultSet releaseRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("revision_id")).thenReturn(REVISION);
        when(row.getInt("revision_version")).thenReturn(1);
        when(row.getString("content_checksum")).thenReturn(CHECKSUM);
        when(row.getString("policy_version")).thenReturn("policy-1");
        when(row.getString("released_by")).thenReturn("reviewer-a");
        when(row.getObject("released_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    private static ResultSet eventRow() throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn(EVENT);
        when(row.getString("organization_id")).thenReturn(ORG);
        when(row.getString("resource_id")).thenReturn(ARTIFACT);
        when(row.getString("event_type")).thenReturn("uploaded");
        when(row.getString("actor_subject")).thenReturn("patient-a");
        when(row.getString("outcome")).thenReturn("success");
        when(row.getString("detail")).thenReturn(null);
        when(row.getObject("occurred_at", OffsetDateTime.class))
                .thenReturn(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return row;
    }

    @Test
    void authorizationReadsMappedRowOrEmpty() {
        assertEquals(grant(), store.authorization(ORG, CONTEXT).orElseThrow());
        rowPresent = false;
        assertTrue(store.authorization(ORG, CONTEXT).isEmpty());
    }

    @Test
    void putAuthorizationWritesGrantUnderItsOwnTenant() {
        store.putAuthorization(grant());
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(ORG, params.getValue().getValue("organizationId"));
        assertEquals(ORG, params.getValue().getValue("grantOrganizationId"));
    }

    @Test
    void putAuthorizationForKeepsLookupTenantAndClaimedGrantTenantSeparate() {
        store.putAuthorizationFor(ORG, CONTEXT, new DocumentAuthorization(FOREIGN_ORG, CONTEXT, "pat-1", true));
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(ORG, params.getValue().getValue("organizationId"));
        assertEquals(FOREIGN_ORG, params.getValue().getValue("grantOrganizationId"));
    }

    @Test
    void artifactReadsMappedRowOrEmpty() {
        assertEquals(artifact(), store.artifact(ORG, ARTIFACT).orElseThrow());
        rowPresent = false;
        assertTrue(store.artifact(ORG, ARTIFACT).isEmpty());
    }

    @Test
    void putArtifactWritesMetadataWithoutBytes() {
        store.putArtifact(artifact());
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(CHECKSUM, params.getValue().getValue("checksum"));
        assertEquals(128L, params.getValue().getValue("sizeBytes"));
    }

    @Test
    void replaceArtifactIfVersionReturnsBothOutcomes() {
        assertTrue(store.replaceArtifactIfVersion(ORG, ARTIFACT, 1, artifact()));
        casReturnsRow = false;
        assertFalse(store.replaceArtifactIfVersion(ORG, ARTIFACT, 1, artifact()));
    }

    @Test
    void artifactsReturnsMappedSnapshotOrEmptyList() {
        assertEquals(List.of(artifact()), store.artifacts(ORG));
        rowPresent = false;
        assertTrue(store.artifacts(ORG).isEmpty());
    }

    @Test
    void putObjectWritesVerifiedObjectMetadata() {
        store.putObject(ORG, OBJECT_KEY, 1, CHECKSUM, 128L);
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        assertEquals(OBJECT_KEY, params.getValue().getValue("objectKey"));
        assertEquals(1, params.getValue().getValue("objectVersion"));
    }

    @Test
    void verifyObjectMatchesOnlyAKeyVersionAndChecksumTriple() {
        assertTrue(store.verifyObject(ORG, OBJECT_KEY, 1, CHECKSUM));
        rowPresent = false;
        assertFalse(store.verifyObject(ORG, OBJECT_KEY, 1, CHECKSUM));
    }

    @Test
    void removeObjectDeletesTheVerifiedRecord() {
        store.removeObject(ORG, OBJECT_KEY, 1);
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void pdfJobReadsMappedRowOrEmpty() {
        assertEquals(job(), store.pdfJob(ORG, JOB).orElseThrow());
        rowPresent = false;
        assertTrue(store.pdfJob(ORG, JOB).isEmpty());
    }

    @Test
    void pdfJobsReturnsMappedSnapshotOrEmptyList() {
        assertEquals(List.of(job()), store.pdfJobs(ORG));
        rowPresent = false;
        assertTrue(store.pdfJobs(ORG).isEmpty());
    }

    @Test
    void putPdfJobWritesTheJob() {
        store.putPdfJob(job());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void replacePdfJobIfVersionReturnsBothOutcomes() {
        assertTrue(store.replacePdfJobIfVersion(ORG, JOB, 1, job()));
        casReturnsRow = false;
        assertFalse(store.replacePdfJobIfVersion(ORG, JOB, 1, job()));
    }

    @Test
    void pdfBindingReadsMappedRowOrEmpty() {
        assertEquals(binding(), store.pdfBinding(ORG, REVISION).orElseThrow());
        rowPresent = false;
        assertTrue(store.pdfBinding(ORG, REVISION).isEmpty());
    }

    @Test
    void putPdfBindingWritesAnImmutableBinding() {
        store.putPdfBinding(binding());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void releaseReadsMappedRowOrEmpty() {
        assertEquals(release(), store.release(ORG, REVISION, 1, "policy-1").orElseThrow());
        rowPresent = false;
        assertTrue(store.release(ORG, REVISION, 1, "policy-1").isEmpty());
    }

    @Test
    void releasesReturnsMappedSnapshotOrEmptyList() {
        assertEquals(List.of(release()), store.releases(ORG));
        rowPresent = false;
        assertTrue(store.releases(ORG).isEmpty());
    }

    @Test
    void insertReleaseIfAbsentReturnsBothOutcomes() {
        assertTrue(store.insertReleaseIfAbsent(release()));
        casReturnsRow = false;
        assertFalse(store.insertReleaseIfAbsent(release()));
    }

    @Test
    void appendEventWritesTheFact() {
        store.appendEvent(event());
        verify(jdbc).update(anyString(), any(SqlParameterSource.class));
    }

    @Test
    void eventsReturnsMappedTrailOrEmptyList() {
        assertEquals(List.of(event()), store.events(ORG));
        rowPresent = false;
        assertTrue(store.events(ORG).isEmpty());
    }
}
