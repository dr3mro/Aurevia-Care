package com.aurevia.tenant.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.documents.storage.FileSystemObjectStore;
import com.aurevia.documents.storage.ObjectKey;
import com.aurevia.documents.storage.ObjectStorePort;
import com.aurevia.tenant.TenantContext;
import com.aurevia.tenant.export.ExportModel.AuditEntry;
import com.aurevia.tenant.export.ExportModel.ExportJob;
import com.aurevia.tenant.export.ExportModel.ExportResult;
import com.aurevia.tenant.export.ExportModel.ExportSource;
import com.aurevia.tenant.export.ExportModel.Provenance;

/** OPS-07 acceptance: checksums, tenant scope, audit and expiry through the service. */
class TenantExportServiceTest {

    /**
     * A transaction template that runs callbacks directly for unit tests.
     *
     * <p>The real template binds tenant GUCs through the guarded manager; here
     * the domain behaviour is exercised without a database.</p>
     *
     * @return a template that executes each callback immediately
     */
    private static TransactionTemplate directTransactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new TransactionTemplate(manager);
    }

    private static final String ORG = "org-a";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final byte[] CONTENT = "synthetic document bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private ExportStore store;
    private ObjectStorePort objects;
    private ExportDigests digests;
    private ExportPermissionGuard authorization;
    private AuditAppender audit;
    private Clock clock;
    private TenantExportService service;
    private String checksum;

    @BeforeEach
    void setUp() {
        store = mock(ExportStore.class);
        objects = new FileSystemObjectStore(root);
        digests = new ExportDigests();
        checksum = digests.sha256Hex(CONTENT);
        authorization = mock(ExportPermissionGuard.class);
        audit = mock(AuditAppender.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new TenantExportService(store, objects, digests, authorization, audit, clock,
                directTransactions());
        TenantContext.set(new TenantContext(ORG, "admin", "EXPORT", "corr-1"));
        when(authorization.mayExport(any())).thenReturn(true);
        when(store.save(any())).thenReturn(true);
        seedSource();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void seedSource() {
        ObjectKey sourceKey = new ObjectKey(ORG, "doc-1", 1);
        objects.putInQuarantine(sourceKey, CONTENT);
        objects.promote(sourceKey, checksum);
        when(store.authorizedSources(TenantExportService.POLICY_VERSION)).thenReturn(List.of(source()));
        when(store.auditFor("rec-1")).thenReturn(List.of(new AuditEntry(1L, "released", "SUCCESS")));
    }

    private ExportSource source() {
        return new ExportSource("rec-1", "doc-1", 1, checksum,
                "release_policy", new Provenance("documents", "doc-1", 1));
    }

    private ExportJob startAndCaptureJob() {
        ExportResult result = service.start();
        ArgumentCaptor<ExportJob> captor = ArgumentCaptor.forClass(ExportJob.class);
        verify(store).save(captor.capture());
        ExportJob job = captor.getValue();
        assertEquals(result.jobId(), job.id());
        return job;
    }

    private long storedObjectCount() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void manifestChecksumsMatchTheFilesAndEveryFactIsAudited() {
        ExportJob job = startAndCaptureJob();
        byte[] manifest = objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).orElseThrow();
        String json = new String(manifest, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"" + checksum + "\""));
        assertTrue(json.contains("\"authorization\":\"release_policy\""));
        assertTrue(json.contains("\"seq\":1"));
        assertTrue(json.contains("\"organization\":\"" + ORG + "\""));
        byte[] exportedFile = objects.getVerified(new ObjectKey(ORG, "doc-1", 1), checksum).orElseThrow();
        assertEquals(checksum, digests.sha256Hex(exportedFile));
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_START"), eq("EXPORT"), eq("TENANT_EXPORT"),
                eq(job.id()), eq("SUCCESS"));
    }

    @Test
    void exportLeavesNoUnreferencedCopiesAfterExpiry() throws IOException {
        ExportJob job = startAndCaptureJob();
        // The manifest is the only artifact a job owns: the authorized source is
        // already managed by the documents domain and keeps its own lifecycle.
        assertEquals(2, storedObjectCount(), "start must not duplicate the authorized document");
        when(store.find(job.id())).thenReturn(Optional.of(job));
        when(store.markExpired(job.id())).thenReturn(true);
        service.expire(job.id());
        assertEquals(1, storedObjectCount(), "expiry must leave only the authorized source object");
    }

    @Test
    void downloadReturnsTheManifestAndAuditsTheDisclosure() throws Exception {
        ExportJob job = startAndCaptureJob();
        byte[] expected = objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).orElseThrow();
        when(store.find(job.id())).thenReturn(Optional.of(job));
        assertArrayEquals(expected, service.download(job.id()));
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_DOWNLOAD"), eq("EXPORT"), eq("TENANT_EXPORT"),
                eq(job.id()), eq("SUCCESS"));
    }

    @Test
    void expiredExportCannotBeDownloaded() {
        ExportJob job = startAndCaptureJob();
        when(store.find(job.id())).thenReturn(Optional.of(job));
        TenantExportService later = new TenantExportService(store, objects, digests, authorization,
                audit, Clock.fixed(NOW.plusSeconds(25 * 3600), ZoneOffset.UTC), directTransactions());
        ApiException failure = assertThrows(ApiException.class, () -> later.download(job.id()));
        assertEquals(410, failure.status());
        assertEquals("EXPORT_EXPIRED", failure.code());
    }

    @Test
    void anExplicitlyExpiredJobCannotBeDownloadedBeforeItsRetentionInstant() {
        ExportJob job = startAndCaptureJob();
        ExportJob expired = new ExportJob(job.id(), "EXPIRED", job.objectId(),
                job.objectVersion(), job.checksum(), job.createdAt(), job.expiresAt());
        when(store.find(job.id())).thenReturn(Optional.of(expired));
        // Explicit expiry marks the row before it removes the object, so the
        // manifest can still be present here. Disclosure must stop at the status
        // change, not wait for the retention instant or the object removal.
        assertTrue(objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).isPresent());
        ApiException failure = assertThrows(ApiException.class, () -> service.download(job.id()));
        assertEquals(410, failure.status());
        assertEquals("EXPORT_EXPIRED", failure.code());
        verify(audit, never()).append(any(), any(), eq("EXPORT_DOWNLOAD"), any(), any(), any(), any());
    }

    @Test
    void expireRemovesTheObjectMarksTheJobAndAudits() {
        ExportJob job = startAndCaptureJob();
        when(store.find(job.id())).thenReturn(Optional.of(job));
        when(store.markExpired(job.id())).thenReturn(true);
        service.expire(job.id());
        assertTrue(objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).isEmpty());
        verify(store).markExpired(job.id());
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_EXPIRE"), eq("EXPORT"), eq("TENANT_EXPORT"),
                eq(job.id()), eq("SUCCESS"));
    }

    @Test
    void anotherTenantCannotSeeTheJob() {
        ExportJob job = startAndCaptureJob();
        when(store.find(job.id())).thenReturn(Optional.empty());
        ApiException failure = assertThrows(ApiException.class, () -> service.download(job.id()));
        assertEquals(404, failure.status());
    }

    @Test
    void nonAdminStartIsDeniedAndAudited() {
        when(authorization.mayExport(any())).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class, () -> service.start());
        assertEquals(403, failure.status());
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_DENIED"), eq("EXPORT"), eq("TENANT_EXPORT"),
                eq("start"), eq("DENIED"));
    }

    @Test
    void missingTenantContextIsDenied() {
        TenantContext.clear();
        ApiException failure = assertThrows(ApiException.class, () -> service.start());
        assertEquals(403, failure.status());
    }

    @Test
    void emptyAuthorizedSetIsRefused() {
        when(store.authorizedSources(TenantExportService.POLICY_VERSION)).thenReturn(List.of());
        ApiException failure = assertThrows(ApiException.class, () -> service.start());
        assertEquals(404, failure.status());
        assertEquals("NO_AUTHORIZED_DOCUMENTS", failure.code());
    }

    @Test
    void missingSourceBytesAreRefused() {
        when(store.authorizedSources(TenantExportService.POLICY_VERSION)).thenReturn(List.of(
                new ExportSource("rec-2", "missing", 1, "b".repeat(64),
                        "release_policy", new Provenance("documents", "missing", 1))));
        ApiException failure = assertThrows(ApiException.class, () -> service.start());
        assertEquals(409, failure.status());
        assertEquals("EXPORT_SOURCE_MISSING", failure.code());
    }

    @Test
    void sourceBytesThatDoNotMatchTheDeclaredChecksumAreRefused() {
        ObjectStorePort permissive = mock(ObjectStorePort.class);
        when(permissive.getVerified(any(), any())).thenReturn(Optional.of(CONTENT));
        when(store.authorizedSources(TenantExportService.POLICY_VERSION)).thenReturn(List.of(
                new ExportSource("rec-1", "doc-1", 1, "d".repeat(64),
                        "release_policy", new Provenance("documents", "doc-1", 1))));
        TenantExportService serviceWithMock = new TenantExportService(store, permissive, digests,
                authorization, audit, clock, directTransactions());
        ApiException failure = assertThrows(ApiException.class, () -> serviceWithMock.start());
        assertEquals(409, failure.status());
        assertEquals("EXPORT_CHECKSUM_MISMATCH", failure.code());
    }

    @Test
    void jobIsRefusedWhenItCouldNotBePersisted() {
        when(store.save(any())).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class, () -> service.start());
        assertEquals(409, failure.status());
        assertEquals("EXPORT_STATE_CONFLICT", failure.code());
    }

    @Test
    void failedJobPersistRemovesThePromotedManifest() throws IOException {
        when(store.save(any())).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class, () -> service.start());
        assertEquals(409, failure.status());
        assertEquals("EXPORT_STATE_CONFLICT", failure.code());
        // The manifest is promoted before the insert. With no job row, nothing can
        // ever expire it, so a refused start must remove the orphan immediately.
        assertEquals(1, storedObjectCount(),
                "a refused job must not leave an unreferenced manifest object");
    }

    @Test
    void unknownJobCannotBeDownloadedOrExpired() {
        when(store.find("unknown")).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ApiException.class, () -> service.download("unknown")).status());
        assertEquals(404, assertThrows(ApiException.class, () -> service.expire("unknown")).status());
    }

    @Test
    void downloadRefusesWhenTheManifestObjectIsGone() {
        ExportJob job = startAndCaptureJob();
        when(store.find(job.id())).thenReturn(Optional.of(job));
        objects.expire(new ObjectKey(ORG, job.objectId(), job.objectVersion()));
        ApiException failure = assertThrows(ApiException.class, () -> service.download(job.id()));
        assertEquals(409, failure.status());
        assertEquals("EXPORT_OBJECT_MISSING", failure.code());
    }

    @Test
    void anInterruptedExpiryCanBeRetriedToCompletion() {
        ExportJob job = startAndCaptureJob();
        when(store.find(job.id())).thenReturn(Optional.of(job));
        when(store.markExpired(job.id())).thenReturn(true);
        // An earlier interrupted expire() removed the object while the row was still
        // COMPLETE. A retry must still reach the durable transition: failing at the
        // object check would leave this job permanently stuck in COMPLETE.
        objects.expire(new ObjectKey(ORG, job.objectId(), job.objectVersion()));
        service.expire(job.id());
        verify(store).markExpired(job.id());
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_EXPIRE"), eq("EXPORT"), eq("TENANT_EXPORT"),
                eq(job.id()), eq("SUCCESS"));
    }

    @Test
    void expireRefusesWhenTheJobWasNotMarked() {
        ExportJob job = startAndCaptureJob();
        when(store.find(job.id())).thenReturn(Optional.of(job));
        when(store.markExpired(job.id())).thenReturn(false);
        ApiException failure = assertThrows(ApiException.class, () -> service.expire(job.id()));
        assertEquals(409, failure.status());
        assertEquals("EXPORT_STATE_CONFLICT", failure.code());
        // A refused state transition must leave the object in place so a later
        // retry can still complete the expiry instead of hitting a missing object.
        assertTrue(objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).isPresent());
    }

    @Test
    void retryAfterAnInterruptedTransitionRemovesTheOrphanedObject() {
        ExportJob job = startAndCaptureJob();
        ExportJob expiredJob = new ExportJob(job.id(), "EXPIRED", job.objectId(),
                job.objectVersion(), job.checksum(), job.createdAt(), job.expiresAt());
        when(store.find(job.id())).thenReturn(Optional.of(expiredJob));
        when(store.markExpired(job.id())).thenReturn(false);
        // The durable row was already EXPIRED but an earlier expire() was interrupted
        // before removing the object. markExpired() reports false on a retry because
        // the row is no longer COMPLETE; that is the retry case, not a conflict. The
        // manifest still holds provenance and audit history, so it must be removed.
        assertTrue(objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).isPresent());
        service.expire(job.id());
        assertTrue(objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).isEmpty(),
                "an already-EXPIRED job must still have its orphaned object removed on retry");
        verify(store).markExpired(job.id());
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_EXPIRE"), eq("EXPORT"), eq("TENANT_EXPORT"),
                eq(job.id()), eq("SUCCESS"));
    }
}
