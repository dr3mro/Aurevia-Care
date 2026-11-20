package com.aurevia.smartclinic.messaging.reports;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.smartclinic.messaging.reports.ReleasedReportService.ReleasedReportView;
import com.aurevia.smartclinic.messaging.reports.ReleasedReportService.ReportDownload;

/**
 * Thin HTTP adapter for the patient released-report slice (PAT-02, ARC-02).
 *
 * <p>All tenant derivation, verified-link checks, release-state rules and audit
 * decisions live in {@link com.aurevia.smartclinic.messaging.reports.ReleasedReportService}; this
 * adapter only binds the path and query parameters and returns the domain view.
 * The synthetic in-memory collaborators keep a locally runnable entry point; a
 * production persistence adapter replaces them without changing this route.</p>
 */
@RestController
@RequestMapping("/api/v1/patient/reports")
public final class ReleasedReportController {

    private final ReleasedReportService service;

    /**
     * Create the route with the synthetic in-memory collaborators.
     *
     * @param clock shared application clock
     */
    @Autowired
    public ReleasedReportController(Clock clock) {
        this(new ReleasedReportService(new InMemoryReportReleaseStore(),
                new ReleasedReportService.InMemoryAuditSink(), clock));
    }

    /**
     * Create the route over a supplied service, used by HTTP tests with seeded fixtures.
     *
     * @param service released-report domain service
     */
    ReleasedReportController(ReleasedReportService service) {
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
