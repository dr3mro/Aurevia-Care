package com.aurevia.exchange.trend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for XCH-07 trending.
 *
 * <p>All authorization and comparability decisions live in
 * com.aurevia.exchange.trend.TrendService; this class only maps HTTP to
 * commands and back.</p>
 */
@RestController
@RequestMapping("/api/v1/trends")
public class TrendController {

    private final TrendService service;

    /**
     * Create the controller.
     *
     * @param service trend command service
     */
    public TrendController(TrendService service) {
        this.service = service;
    }

    /**
     * Record one observation.
     *
     * @param request observation fields
     * @return HTTP 201 with the persisted observation
     */
    @PostMapping("/observations")
    public ResponseEntity<TrendObservation> record(@RequestBody ObservationRequest request) {
        return ResponseEntity.status(201).body(service.recordObservation(request));
    }

    /**
     * Define a new comparability policy version.
     *
     * @param request policy fields
     * @return HTTP 201 with the new rule
     */
    @PostMapping("/comparability")
    public ResponseEntity<ComparabilityRule> define(@RequestBody ComparabilityRequest request) {
        return ResponseEntity.status(201).body(service.defineComparability(request));
    }

    /**
     * Read the comparable trend and its visible exclusions.
     *
     * @param patientId tenant-local patient identifier
     * @param testCode  requested test identity
     * @return the trend view
     */
    @GetMapping("/{patientId}")
    public TrendView trend(@PathVariable String patientId, @RequestParam String testCode) {
        return service.readTrend(patientId, testCode);
    }
}
