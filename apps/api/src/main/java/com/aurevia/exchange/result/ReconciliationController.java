package com.aurevia.exchange.result;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.exchange.result.ReconciliationModel.ResolveRequest;
import com.aurevia.exchange.result.ReconciliationModel.Resolution;

/**
 * Thin HTTP adapter for resolving unmatched XCH-04 results (ARC-03, ARC-04).
 *
 * <p>No domain decision lives here: the action, reason and optional new owner
 * are validated by the injected service inside one tenant-guarded transaction.
 * The reading endpoint of the same queue lives in
 * {@link com.aurevia.exchange.result.ResultController}.</p>
 */
@RestController
@RequestMapping("/api/v1/xch04/reconciliations")
public class ReconciliationController {

    private final ReconciliationService service;

    /**
     * Create the controller.
     *
     * @param service reconciliation domain service
     */
    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /**
     * Resolve one queue entry.
     *
     * @param id      queue entry identifier
     * @param request action, reason and optional new owner
     * @return the recorded resolution
     */
    @PostMapping("/{id}/resolve")
    public Resolution resolve(@PathVariable String id, @RequestBody ResolveRequest request) {
        return service.resolve(id, request);
    }

    /**
     * Read back the resolution recorded for one queue entry.
     *
     * @param id queue entry identifier
     * @return the resolution, or 404 when this entry has not been resolved
     */
    @GetMapping("/{id}/resolution")
    public ResponseEntity<Resolution> resolution(@PathVariable String id) {
        return service.resolution(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
