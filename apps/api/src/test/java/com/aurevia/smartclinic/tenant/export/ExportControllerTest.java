package com.aurevia.smartclinic.tenant.export;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.tenant.export.ExportModel.ExportResult;

/** OPS-07 entry point: start, download and expiry reach the service over HTTP. */
class ExportControllerTest {

    private TenantExportService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TenantExportService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExportController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
        TenantContext.set(new TenantContext("org-a", "admin", "EXPORT", "corr-1"));
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void startReturnsJobMetadata() throws Exception {
        when(service.start()).thenReturn(new ExportResult("job-1", "a".repeat(64), 2,
                "2026-11-22T10:00:00Z"));
        mvc.perform(post("/api/v1/tenant/exports"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job_id").value("job-1"))
                .andExpect(jsonPath("$.checksum").value("a".repeat(64)))
                .andExpect(jsonPath("$.record_count").value(2))
                .andExpect(jsonPath("$.expires_at").value("2026-11-22T10:00:00Z"));
    }

    @Test
    void downloadReturnsTheManifestBytes() throws Exception {
        when(service.download("job-1")).thenReturn("{\"manifest\":{}}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(get("/api/v1/tenant/exports/job-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"manifest\":{}}"));
    }

    @Test
    void expireReturnsNoContent() throws Exception {
        mvc.perform(post("/api/v1/tenant/exports/job-1/expire"))
                .andExpect(status().isNoContent());
        verify(service).expire("job-1");
    }

    @Test
    void denialIsRenderedAsForbidden() throws Exception {
        when(service.start()).thenThrow(new ApiException(403, "FORBIDDEN", "no export permission"));
        mvc.perform(post("/api/v1/tenant/exports"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void expiredDownloadIsRenderedAsGone() throws Exception {
        when(service.download(any())).thenThrow(new ApiException(410, "EXPORT_EXPIRED", "expired"));
        mvc.perform(get("/api/v1/tenant/exports/job-1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EXPORT_EXPIRED"));
    }
}
