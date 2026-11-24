package com.aurevia.messaging.reports;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.messaging.reports.ReleasedReportService.ReleasedReportView;
import com.aurevia.messaging.reports.ReleasedReportService.ReportDownload;

/**
 * Thin HTTP adapter for the patient released-report slice (PAT-02, ARC-02).
 *
 * <p>All tenant derivation, verified-link checks, release-state rules and audit
 * decisions live in {@link com.aurevia.messaging.reports.ReleasedReportService}; this
 * adapter only binds the path and query parameters and returns the domain view. The
 * service is injected and backed by the tenant-guarded JDBC release store in production.</p>
 */
@RestController
@RequestMapping("/api/v1/patient/reports")
public final class ReleasedReportController {

    private final ReleasedReportService service;

    /**
     * Create the route over the injected released-report domain service.
     *
     * @param service released-report domain service
     */
    public ReleasedReportController(ReleasedReportService service) {
        this.service = service;
    }

    /**
     * List the revisions currently released to the authenticated patient.
     *
     * @return the visible report projections
     */
    @GetMapping
    public List<ReleasedReportView> list() {
        return service.listFor();
    }

    /**
     * Authorize and return the document reference of one exact revision.
     *
     * @param reportId report identifier
     * @param revision released revision number
     * @return the mediated document reference
     */
    @GetMapping("/{reportId}/download")
    public ReportDownload download(@PathVariable String reportId, @RequestParam int revision) {
        return service.download(reportId, revision);
    }
}
