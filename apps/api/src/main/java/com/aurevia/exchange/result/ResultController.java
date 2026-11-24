package com.aurevia.exchange.result;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.exchange.result.ResultCommands.RegisterOrderRequest;
import com.aurevia.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.exchange.result.ResultModel.ReconciliationEntry;
import com.aurevia.exchange.result.ResultModel.ResultDetail;
import com.aurevia.exchange.result.ResultModel.Review;

/**
 * Thin HTTP adapter for XCH-04 (ARC-03, ARC-04).
 *
 * <p>No domain decision lives here. The version precondition is parsed before
 * the service compares it, and an imported report returns 201 while a report
 * diverted to reconciliation returns 202.</p>
 */
@RestController
@RequestMapping("/api/v1/xch04")
public class ResultController {

    private final ResultRoutingService service;

    /**
     * Create the controller.
     *
     * @param service result routing domain service
     */
    public ResultController(ResultRoutingService service) {
        this.service = service;
    }

    /**
     * Register the synthetic expectation used for identifier matching.
     *
     * @param request expectation values
     * @return HTTP 201 with the registered expectation
     */
    @PostMapping("/orders")
    public ResponseEntity<ExpectedOrder> registerOrder(@RequestBody RegisterOrderRequest request) {
        ExpectedOrder order = service.registerOrder(request);
        return ResponseEntity.created(URI.create("/api/v1/xch04/orders/" + order.orderId())).body(order);
    }

    /**
     * Import one inbound result.
     *
     * @param request inbound result values
     * @return 201 with the revision, or 202 with a reconciliation entry
     */
    @PostMapping("/results")
    public ResponseEntity<?> importResult(@RequestBody ImportResultRequest request) {
        ImportOutcome outcome = service.importResult(request);
        if (outcome.reconciliation() != null) {
            return ResponseEntity.accepted().body(outcome.reconciliation());
        }
        return ResponseEntity.created(URI.create("/api/v1/xch04/results/" + outcome.report().id()))
                .body(outcome.report());
    }

    /**
     * Read one revision, its review, lineage and correction notices.
     *
     * @param id revision identifier
     * @return the result detail
     */
    @GetMapping("/results/{id}")
    public ResultDetail getReport(@PathVariable String id) {
        return service.getReport(id);
    }

    /**
     * Acknowledge one exact revision.
     *
     * @param id      revision identifier
     * @param ifMatch expected review task version
     * @return the acknowledged review task
     */
    @PostMapping("/results/{id}/acknowledge")
    public Review acknowledge(@PathVariable String id,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.acknowledge(id, IfMatch.parse(ifMatch));
    }

    /**
     * List a bounded page of reconciliation entries.
     *
     * @param status {@code open} or {@code resolved}
     * @return queue entries newest first
     */
    @GetMapping("/reconciliations")
    public List<ReconciliationEntry> listReconciliation(
            @RequestParam(value = "status", defaultValue = "open") String status) {
        return service.listReconciliation(status);
    }
}
