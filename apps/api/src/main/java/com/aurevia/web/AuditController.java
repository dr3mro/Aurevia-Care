package com.aurevia.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.audit.AuditPage;
import com.aurevia.audit.AuditService;

/**
 * Thin HTTP adapter for tenant audit review.
 *
 * <p>Filters and paging are validated in the domain service; this adapter only
 * binds query parameters.</p>
 */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {

    private final AuditService service;

    /**
     * Create the controller.
     *
     * @param service audit domain service
     */
    public AuditController(AuditService service) {
        this.service = service;
    }

    /**
     * Query one page of the active tenant's audit trail.
     *
     * @param cursor       opaque seq cursor
     * @param limit        page size
     * @param action       exact action filter
     * @param outcome      exact outcome filter
     * @param resourceType exact resource-type filter
     * @param from         inclusive UTC instant
     * @param to           inclusive UTC instant
     * @return one page of audit events
     */
    @GetMapping
    public AuditPage query(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.query(cursor, limit, action, outcome, resourceType, from, to);
    }
}
