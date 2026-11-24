package com.aurevia.orderentry.place;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.api.IfMatch;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for placing a clinical order from the chart (LAB-01, RAD-01, XCH-01).
 *
 * <p>The tenant, acting subject and active roles are derived from the request
 * context and freshly resolved membership; none is taken from the body. Draft
 * edits and placement require an {@code If-Match} version, and placement also
 * requires an {@code Idempotency-Key}. All business decisions stay in the
 * placement kernel. The facility on the request is bound to verified membership
 * in the integration story that persists the kernel.</p>
 */
@RestController
@RequestMapping("/api/v1/order-entry")
public final class PlaceOrderController {

    private final OrderPlacementService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the routes over the injected placement kernel.
     *
     * @param service      placement domain service
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public PlaceOrderController(OrderPlacementService service, StaffRepository staff,
                                TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Save an editable order draft in the active tenant.
     *
     * @param request validated order content
     * @return HTTP 201 with the saved draft
     */
    @PostMapping("/drafts")
    public ResponseEntity<OrderDraft> saveDraft(@RequestBody PlacedOrderRequest request) {
        TenantContext context = context();
        OrderDraft draft = service.saveDraft(context.organizationId(), context.subject(),
                activeRoles(context), request);
        return ResponseEntity.created(URI.create("/api/v1/order-entry/drafts/" + draft.id())).body(draft);
    }

    /**
     * Replace a draft's content under a version precondition.
     *
     * @param id      draft identifier
     * @param request replacement order content
     * @param ifMatch version the caller last read
     * @return the new draft revision
     */
    @PutMapping("/drafts/{id}")
    public OrderDraft editDraft(@PathVariable String id, @RequestBody PlacedOrderRequest request,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        TenantContext context = context();
        return service.editDraft(context.organizationId(), context.subject(), activeRoles(context), id,
                IfMatch.parse(ifMatch), request);
    }

    /**
     * Place a draft, making it immutable and idempotent for the supplied key.
     *
     * @param id             draft identifier
     * @param ifMatch        version the caller last read
     * @param idempotencyKey client key that deduplicates a retried placement
     * @return the immutable placed order with its stored delivery mode
     */
    @PostMapping("/drafts/{id}/place")
    public PlacedOrder place(@PathVariable String id,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        TenantContext context = context();
        return service.place(context.organizationId(), context.subject(), activeRoles(context), id,
                IfMatch.parse(ifMatch), IdempotencyKey.parse(idempotencyKey));
    }

    /**
     * Read one placed order in the active tenant.
     *
     * @param id placed-order identifier
     * @return the immutable placed order
     */
    @GetMapping("/orders/{id}")
    public PlacedOrder get(@PathVariable String id) {
        TenantContext context = context();
        return service.get(context.organizationId(), context.subject(), activeRoles(context), id);
    }

    private static TenantContext context() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private Set<String> activeRoles(TenantContext context) {
        return Set.copyOf(transactions.execute(status -> staff.activeRoles(context.subject())));
    }
}
