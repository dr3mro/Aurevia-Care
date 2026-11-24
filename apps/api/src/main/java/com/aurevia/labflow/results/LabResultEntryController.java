package com.aurevia.labflow.results;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for LAB-02 result entry and verification.
 *
 * <p>Every invariant lives in the injected
 * {@link com.aurevia.labflow.results.ResultEntryService}; this adapter
 * only binds commands and returns the report projection. The authenticated
 * subject comes from the tenant context, never from a request field.</p>
 */
@RestController
@RequestMapping("/api/v1/lab/results")
public class LabResultEntryController {

    private final ResultEntryService service;

    /**
     * Create the controller.
     *
     * @param service result entry domain service
     */
    public LabResultEntryController(ResultEntryService service) {
        this.service = service;
    }

    /**
     * Capture observations for a matched specimen as a preliminary report.
     *
     * @param id      opaque specimen identifier
     * @param request bounded capture command
     * @return the preliminary report
     */
    @PostMapping("/{id}")
    public ReportView enter(@PathVariable String id, @RequestBody ResultEntryRequest request) {
        return service.enter(id, request);
    }

    /**
     * Verify a preliminary report as a different authenticated verifier.
     *
     * @param id      opaque report identifier
     * @param request bounded verification command
     * @return the issued final report
     */
    @PostMapping("/{id}/verify")
    public ReportView verify(@PathVariable String id, @RequestBody ResultDecisionRequest request) {
        return service.verify(id, request);
    }

    /**
     * Append a linked correction with a reason.
     *
     * @param id      opaque report identifier
     * @param request bounded correction command
     * @return the corrected report
     */
    @PostMapping("/{id}/correct")
    public ReportView correct(@PathVariable String id, @RequestBody ResultDecisionRequest request) {
        return service.correct(id, request);
    }

    /**
     * Withdraw a report with a reason, preserving its history.
     *
     * @param id      opaque report identifier
     * @param request bounded withdrawal command
     * @return the terminal report
     */
    @PostMapping("/{id}/error")
    public ReportView enterInError(@PathVariable String id, @RequestBody ResultDecisionRequest request) {
        return service.enterInError(id, request);
    }

    /**
     * Read the current revision of a report.
     *
     * @param id opaque report identifier
     * @return the current report projection
     */
    @GetMapping("/{id}")
    public ReportView read(@PathVariable String id) {
        return service.read(id);
    }
}
