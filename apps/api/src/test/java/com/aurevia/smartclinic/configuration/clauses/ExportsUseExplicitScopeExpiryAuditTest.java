package com.aurevia.smartclinic.configuration.clauses;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.audit.AuditAppender;
import com.aurevia.smartclinic.documents.storage.FileSystemObjectStore;
import com.aurevia.smartclinic.documents.storage.ObjectKey;
import com.aurevia.smartclinic.documents.storage.ObjectStorePort;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.tenant.export.ExportController;
import com.aurevia.smartclinic.tenant.export.ExportDigests;
import com.aurevia.smartclinic.tenant.export.ExportModel.AuditEntry;
import com.aurevia.smartclinic.tenant.export.ExportModel.ExportJob;
import com.aurevia.smartclinic.tenant.export.ExportModel.ExportResult;
import com.aurevia.smartclinic.tenant.export.ExportModel.ExportSource;
import com.aurevia.smartclinic.tenant.export.ExportModel.Provenance;
import com.aurevia.smartclinic.tenant.export.ExportPermissionGuard;
import com.aurevia.smartclinic.tenant.export.ExportStore;
import com.aurevia.smartclinic.tenant.export.TenantExportService;

/**
 * ADM-02 clause 3 evidence: a tenant export binds an explicit tenant scope and
 * authorizing policy, carries a bounded retention expiry and appends a
 * TENANT_EXPORT-scoped audit fact for every lifecycle action. Negative cases show
 * the rule refusing an unauthorized actor, an expired job, a foreign tenant, a
 * missing artifact and a conflicting state retry. All fixtures are synthetic.
 */
class ExportsUseExplicitScopeExpiryAuditTest {

    private static final String ORG = "org-a";
    private static final String ORG_B = "org-b";
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
    private MockMvc mvc;
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
        service = new TenantExportService(store, objects, digests, authorization, audit, clock);
        TenantContext.set(new TenantContext(ORG, "admin", "TENANT_EXPORT", "corr-1"));
        when(authorization.mayExport(any())).thenReturn(true);
        when(store.save(any())).thenReturn(true);
        seedSource();
        mvc = MockMvcBuilders.standaloneSetup(new ExportController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
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

    private ExportJob startAndCaptureJob(ExportResult result) {
        ArgumentCaptor<ExportJob> captor = ArgumentCaptor.forClass(ExportJob.class);
        verify(store).save(captor.capture());
        ExportJob job = captor.getValue();
        assertEquals(result.jobId(), job.id());
        return job;
    }

    private String manifestJson(ExportJob job) {
        byte[] manifest = objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).orElseThrow();
        return new String(manifest, StandardCharsets.UTF_8);
    }

    @Test
    void exportCarriesExplicitScopePolicyExpiryAndAudit() {
        ExportResult result = service.start();
        ExportJob job = startAndCaptureJob(result);

        String json = manifestJson(job);
        // The scope is explicit in the artifact: the active organization and the
        // versioned policy are bound at start, not supplied by the caller.
        assertTrue(json.contains("\"organization\":\"" + ORG + "\""),
                "manifest must bind the explicit active-tenant scope");
        assertTrue(json.contains("\"policy_version\":\"" + TenantExportService.POLICY_VERSION + "\""),
                "manifest must name the policy that authorized the export");
        assertTrue(json.contains("\"schema_version\":\"" + TenantExportService.ENVELOPE_SCHEMA_VERSION + "\""));
        assertEquals(NOW.plus(TenantExportService.RETENTION).toString(), result.expiresAt(),
                "explicit expiry must be the fixed retention window from the export instant");
        assertEquals(job.expiresAt(), result.expiresAt(), "job and result must agree on the expiry instant");
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_START"), eq("EXPORT"),
                eq("TENANT_EXPORT"), eq(job.id()), eq("SUCCESS"));
    }

    @Test
    void startWithoutExportPermissionIsDeniedAndAudited() {
        when(authorization.mayExport(any())).thenReturn(false);

        ApiException failure = assertThrows(ApiException.class, () -> service.start());
        assertEquals(403, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_DENIED"), eq("EXPORT"),
                eq("TENANT_EXPORT"), eq("start"), eq("DENIED"));
    }

    @Test
    void downloadAfterExpiryIsRefused() {
        ExportResult result = service.start();
        ExportJob job = startAndCaptureJob(result);
        when(store.find(job.id())).thenReturn(Optional.of(job));
        TenantExportService later = new TenantExportService(store, objects, digests, authorization,
                audit, Clock.fixed(NOW.plus(TenantExportService.RETENTION).plusSeconds(1), ZoneOffset.UTC));

        ApiException failure = assertThrows(ApiException.class, () -> later.download(job.id()));
        assertEquals(410, failure.status());
        assertEquals("EXPORT_EXPIRED", failure.code());
        verify(audit, never()).append(any(), any(), eq("EXPORT_DOWNLOAD"), any(), any(), any(), any());
    }

    @Test
    void downloadOfAnotherTenantsJobIsNotFound() {
        ExportResult result = service.start();
        ExportJob job = startAndCaptureJob(result);
        // Forced RLS in a second tenant cannot see the first tenant's job row, so
        // the store reports no job and disclosure is refused before any object read.
        TenantContext.set(new TenantContext(ORG_B, "admin-b", "TENANT_EXPORT", "corr-2"));
        when(store.find(job.id())).thenReturn(Optional.empty());

        ApiException failure = assertThrows(ApiException.class, () -> service.download(job.id()));
        assertEquals(404, failure.status());
        assertEquals("EXPORT_NOT_FOUND", failure.code());
    }

    @Test
    void downloadOfMissingManifestIsRefused() {
        ExportResult result = service.start();
        ExportJob job = startAndCaptureJob(result);
        when(store.find(job.id())).thenReturn(Optional.of(job));
        objects.expire(new ObjectKey(ORG, job.objectId(), job.objectVersion()));

        ApiException failure = assertThrows(ApiException.class, () -> service.download(job.id()));
        assertEquals(409, failure.status());
        assertEquals("EXPORT_OBJECT_MISSING", failure.code());
        verify(audit, never()).append(any(), any(), eq("EXPORT_DOWNLOAD"), any(), any(), any(), any());
    }

    @Test
    void interruptedExpiryRetryDoesNotConflict() {
        ExportResult result = service.start();
        ExportJob job = startAndCaptureJob(result);
        ExportJob alreadyExpired = new ExportJob(job.id(), "EXPIRED", job.objectId(),
                job.objectVersion(), job.checksum(), job.createdAt(), job.expiresAt());
        when(store.find(job.id())).thenReturn(Optional.of(alreadyExpired));
        when(store.markExpired(job.id())).thenReturn(false);

        // The durable row is already EXPIRED and markExpired reports false; the
        // already-expired check must short-circuit so a retry cleans up instead
        // of failing with a spurious state conflict.
        assertDoesNotThrow(() -> service.expire(job.id()));
        verify(store).markExpired(job.id());
        assertTrue(objects.getVerified(
                new ObjectKey(ORG, job.objectId(), job.objectVersion()), job.checksum()).isEmpty(),
                "the retry must still remove the orphaned manifest object");
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_EXPIRE"), eq("EXPORT"),
                eq("TENANT_EXPORT"), eq(job.id()), eq("SUCCESS"));
    }

    @Test
    void httpStartPublishesExplicitExpiryAtTheEntryPoint() throws Exception {
        mvc.perform(post("/api/v1/tenant/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job_id").exists())
                .andExpect(jsonPath("$.expires_at")
                        .value(NOW.plus(TenantExportService.RETENTION).toString()));
        verify(audit).append(any(), eq("TENANT_EXPORT"), eq("EXPORT_START"), eq("EXPORT"),
                eq("TENANT_EXPORT"), any(), eq("SUCCESS"));
    }

    @Test
    void httpExpiredDownloadIsRenderedAsGone() throws Exception {
        ExportResult result = service.start();
        ExportJob job = startAndCaptureJob(result);
        when(store.find(job.id())).thenReturn(Optional.of(job));
        TenantExportService later = new TenantExportService(store, objects, digests, authorization,
                audit, Clock.fixed(NOW.plus(TenantExportService.RETENTION).plusSeconds(1), ZoneOffset.UTC));
        MockMvc laterMvc = MockMvcBuilders.standaloneSetup(new ExportController(later))
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        laterMvc.perform(get("/api/v1/tenant/exports/" + job.id()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EXPORT_EXPIRED"));
    }
}
