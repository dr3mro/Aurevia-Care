package com.aurevia.pharmacyflow.inbox;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.pharmacyflow.inbox.PharmacyInboxContract.InboxItem;
import com.aurevia.pharmacyflow.inbox.PharmacyInboxContract.InboxPage;

/**
 * Thin HTTP adapter for the pharmacy prescription inbox.
 *
 * <p>This controller only binds query parameters and paths. Routing, paging,
 * authorization, audit and the chart-free projection belong to the injected
 * domain service.</p>
 */
@RestController
@RequestMapping("/api/v1/pharmacy/inbox")
public class PharmacyInboxController {

    private final PharmacyInboxService service;

    /**
     * Create the controller.
     *
     * @param service pharmacy inbox domain service
     */
    public PharmacyInboxController(PharmacyInboxService service) {
        this.service = service;
    }

    /**
     * List prescriptions routed to the reading pharmacy.
     *
     * @param status optional prescription state filter
     * @param cursor opaque cursor from the previous page
     * @param limit  requested page size
     * @return one page of routed prescriptions
     */
    @GetMapping
    public InboxPage list(@RequestParam(name = "status", required = false) String status,
                          @RequestParam(name = "cursor", required = false) String cursor,
                          @RequestParam(name = "limit", required = false) String limit) {
        return service.list(status, cursor, limit);
    }

    /**
     * Read one routed prescription.
     *
     * @param id prescription identifier
     * @return the chart-free routed projection
     */
    @GetMapping("/{id}")
    public InboxItem get(@PathVariable String id) {
        return service.get(id);
    }
}
