package com.aurevia.labflow.outage;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.labflow.outage.OutageModel.ManualResult;
import com.aurevia.labflow.outage.OutageModel.ManualResultRequest;
import com.aurevia.labflow.outage.OutageModel.OutageEvent;

/**
 * Thin HTTP adapter for the partner-outage manual result fallback (ARC-03, ARC-04).
 *
 * <p>No domain decision lives here: validation, matching, queueing, history and
 * audit are owned by the injected
 * {@link com.aurevia.labflow.outage.OutageService}. The authenticated
 * subject and organization come from the tenant context, never from the request.</p>
 */
@RestController
@RequestMapping("/api/v1/lab/outage")
public class OutageController {

    private final OutageService service;

    /**
     * Create the controller.
     *
     * @param service outage fallback domain service
     */
    public OutageController(OutageService service) {
        this.service = service;
    }

    /**
     * Attach the original report and key results in against an order.
     *
     * @param orderRef order or specimen reference from the route
     * @param request  bounded manual-entry command
     * @return the linked entry or the queued unmatched entry
     */
    @PostMapping("/orders/{orderRef}/manual-results")
    public ManualResult record(@PathVariable String orderRef, @RequestBody ManualResultRequest request) {
        return service.record(orderRef, request);
    }

    /**
     * Read the evidence attachment linked to an order during an outage.
     *
     * @param orderRef opaque order identifier
     * @return the linked entry, or 404 when this order has no outage entry
     */
    @GetMapping("/orders/{orderRef}/attachment")
    public ResponseEntity<ManualResult> attachment(@PathVariable String orderRef) {
        return service.forOrder(orderRef)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * List unmatched results waiting for an owner in the active tenant.
     *
     * @return the unmatched queue
     */
    @GetMapping("/unmatched")
    public List<ManualResult> unmatched() {
        return service.unmatchedQueue();
    }

    /**
     * Read the outage history for one order reference.
     *
     * @param orderRef order reference presented by the operator
     * @return the append-only outage events
     */
    @GetMapping("/orders/{orderRef}/history")
    public List<OutageEvent> history(@PathVariable String orderRef) {
        return service.history(orderRef);
    }
}
