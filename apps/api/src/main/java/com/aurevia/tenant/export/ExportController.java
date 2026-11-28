package com.aurevia.tenant.export;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.tenant.export.ExportModel.ExportResult;

/**
 * Thin HTTP adapter for tenant exports.
 *
 * <p>All authorization, checksum, policy and expiry rules live in
 * {@link com.aurevia.tenant.export.TenantExportService}; this
 * adapter only binds the job identifier and maps outcomes to HTTP responses.</p>
 */
@RestController
@RequestMapping("/api/v1/tenant/exports")
public class ExportController {

    private final TenantExportService service;

    /**
     * Create the controller with the injected domain service.
     *
     * @param service tenant export orchestration
     */
    public ExportController(TenantExportService service) {
        this.service = service;
    }

    /**
     * Start an export for the active organization.
     *
     * @return opaque job metadata including the manifest checksum and expiry
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping
    public ExportResult start() {
        return service.start();
    }

    /**
     * Download a verified, unexpired manifest.
     *
     * @param jobId opaque job identifier
     * @return manifest bytes with a JSON content type
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<byte[]> download(@PathVariable String jobId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.download(jobId));
    }

    /**
     * Expire an export and remove its object.
     *
     * @param jobId opaque job identifier
     * @return no content
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/{jobId}/expire")
    public ResponseEntity<Void> expire(@PathVariable String jobId) {
        service.expire(jobId);
        return ResponseEntity.noContent().build();
    }
}
