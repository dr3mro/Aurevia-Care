package com.aurevia.chart.record;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;

/**
 * Thin HTTP adapter for the CLN-03 longitudinal chart (ARC-03).
 *
 * <p>All validation, authorization, mapping and verification live in
 * {@link com.aurevia.chart.record.ChartService}; this adapter only
 * parses the {@code If-Match} precondition and shapes responses.</p>
 */
@RestController
@RequestMapping("/api/v1/chart/records")
public class ChartController {

    private final ChartService service;

    /**
     * Create the controller.
     *
     * @param service chart domain service
     */
    public ChartController(ChartService service) {
        this.service = service;
    }

    /**
     * Record a chart entry.
     *
     * @param command chart entry command
     * @return HTTP 201 with the persisted entry
     */
    @PostMapping("/entries")
    public ResponseEntity<ChartEntry> record(@RequestBody RecordEntryCommand command) {
        ChartEntry created = service.record(command);
        return ResponseEntity.created(URI.create("/api/v1/chart/records/entries/" + created.id())).body(created);
    }

    /**
     * Verify a chart entry.
     *
     * @param id      entry identifier
     * @param ifMatch expected version
     * @return the verified entry
     */
    @PostMapping("/entries/{id}/verify")
    public ChartEntry verify(@PathVariable String id,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.verify(id, IfMatch.parse(ifMatch));
    }

    /**
     * Read a patient's chart.
     *
     * @param patientId patient identifier
     * @return the immutable chart view
     */
    @GetMapping("/patients/{patientId}/entries")
    public ChartView chart(@PathVariable String patientId) {
        return service.listForPatient(patientId);
    }

    /**
     * Read one chart entry.
     *
     * @param id entry identifier
     * @return the entry
     */
    @GetMapping("/entries/{id}")
    public ChartEntry get(@PathVariable String id) {
        return service.get(id);
    }
}
