package com.aurevia.exchange.order;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for the clinical order exchange.
 *
 * <p>The controller contains no business rules: tenant derivation happens in the
 * request interceptor, and authorization, snapshotting, disclosure and the work
 * item projection live in the domain service.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class OrderController {

    private final OrderService service;

    /**
     * Create the controller.
     *
     * @param service exchange domain service
     */
    public OrderController(OrderService service) {
        this.service = service;
    }

    /**
     * Release an order to an approved destination.
     *
     * @param request release command
     * @return HTTP 201 with the stamped released order
     * @throws com.aurevia.api.ApiException on invalid input, denial or concealment
     */
    @PostMapping("/orders")
    public ResponseEntity<OrderView> create(@RequestBody OrderRequest request) {
        OrderView created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/orders/" + created.id())).body(created);
    }

    /**
     * List one cursor-paginated page of released orders of the active tenant.
     *
     * @param cursor opaque {@code <createdAt>:<id>} of the previous page's last row
     * @param limit  page size, or {@code null} for the default of 50
     * @param status optional exact work-item state filter
     * @return HTTP 200 with one page of orders
     * @throws com.aurevia.api.ApiException on invalid input or denial
     */
    @GetMapping("/orders")
    public OrderPage list(@RequestParam(required = false) String cursor,
                          @RequestParam(required = false) String limit,
                          @RequestParam(required = false) String status) {
        return service.list(cursor, limit, status);
    }

    /**
     * Read one released order of the active tenant.
     *
     * @param id order identifier
     * @return HTTP 200 with the order
     * @throws com.aurevia.api.ApiException on invalid input, denial or concealment
     */
    @GetMapping("/orders/{id}")
    public OrderView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Record an approved exchange authorization.
     *
     * @param request authorization command
     * @return HTTP 201 with the appended authorization
     * @throws com.aurevia.api.ApiException on invalid input or denial
     */
    @PostMapping("/exchange/authorizations")
    public ResponseEntity<AuthorizationView> authorize(@RequestBody AuthorizationRequest request) {
        AuthorizationView created = service.authorize(request);
        return ResponseEntity.created(
                URI.create("/api/v1/exchange/authorizations/" + created.id())).body(created);
    }

    /**
     * List bounded work items of the active receiving organization.
     *
     * @return HTTP 200 with the destination's work items
     * @throws com.aurevia.api.ApiException on denial
     */
    @GetMapping("/exchange/work-items")
    public List<WorkItemView> workItems() {
        return service.workItems();
    }

    /**
     * Bind a locally verified receiver patient to a work item.
     *
     * @param id      work item identifier
     * @param request receiver-side patient identifier
     * @return HTTP 200 with the verified work item
     * @throws com.aurevia.api.ApiException on invalid input, denial, concealment, identity mismatch or a repeat verification
     */
    @PostMapping("/exchange/work-items/{id}/verify-patient")
    public WorkItemView verifyPatient(@PathVariable String id,
                                      @RequestBody VerifyPatientRequest request) {
        return service.verifyPatient(id, request);
    }
}
