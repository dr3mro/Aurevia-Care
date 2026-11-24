package com.aurevia.finance;

import java.net.URI;

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
import com.aurevia.finance.FinanceContract.CreateInvoiceRequest;
import com.aurevia.finance.FinanceContract.EntryRequest;
import com.aurevia.finance.FinanceContract.EntryType;
import com.aurevia.finance.FinanceContract.Reconciliation;
import com.aurevia.finance.FinanceContract.VoidRequest;
import com.aurevia.finance.FinanceContract.View;

/**
 * Thin HTTP adapter for FIN-01 self-pay finance (ARC-03, ARC-04).
 *
 * <p>Every irreversible command requires an {@code If-Match} version, parsed at
 * this boundary. Authorization, replay deduplication, balance arithmetic and
 * approval invariants all live in the domain service. Finance responses carry
 * no clinical narrative.</p>
 */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {

    private final FinanceService service;

    /**
     * Create the controller.
     *
     * @param service finance domain service
     */
    public FinanceController(FinanceService service) {
        this.service = service;
    }

    /**
     * Create an itemized draft invoice.
     *
     * @param request creation command
     * @return HTTP 201 with the created invoice
     */
    @PostMapping("/invoices")
    public ResponseEntity<View> create(@RequestBody CreateInvoiceRequest request) {
        View created = service.create(request);
        return ResponseEntity.created(
                URI.create("/api/v1/finance/invoices/" + created.invoice().id())).body(created);
    }

    /**
     * List one cursor-paginated page of the active tenant's invoices.
     *
     * @param cursor    opaque {@code <createdAt>:<id>} of the previous page's last row
     * @param limit     page size, or {@code null} for the default of 50
     * @param status    optional exact invoice state filter
     * @param patientId optional exact owning patient identifier filter
     * @return HTTP 200 with one page of invoices
     */
    @GetMapping("/invoices")
    public InvoicePage list(@RequestParam(required = false) String cursor,
                            @RequestParam(required = false) String limit,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String patientId) {
        return service.list(cursor, limit, status, patientId);
    }

    /**
     * Issue a draft invoice.
     *
     * @param id      invoice identifier
     * @param ifMatch expected version
     * @return the issued invoice
     */
    @PostMapping("/invoices/{id}/issue")
    public View issue(@PathVariable String id,
                      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.issue(id, IfMatch.parse(ifMatch));
    }

    /**
     * Record a payment.
     *
     * @param id      invoice identifier
     * @param request payment command
     * @param ifMatch expected version
     * @return the invoice and its ledger trail
     */
    @PostMapping("/invoices/{id}/payments")
    public View payment(@PathVariable String id, @RequestBody EntryRequest request,
                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.postEntry(id, EntryType.PAYMENT, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record an approved credit.
     *
     * @param id      invoice identifier
     * @param request credit command
     * @param ifMatch expected version
     * @return the invoice and its ledger trail
     */
    @PostMapping("/invoices/{id}/credits")
    public View credit(@PathVariable String id, @RequestBody EntryRequest request,
                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.postEntry(id, EntryType.CREDIT, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record an approved refund.
     *
     * @param id      invoice identifier
     * @param request refund command
     * @param ifMatch expected version
     * @return the invoice and its ledger trail
     */
    @PostMapping("/invoices/{id}/refunds")
    public View refund(@PathVariable String id, @RequestBody EntryRequest request,
                       @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.postEntry(id, EntryType.REFUND, request, IfMatch.parse(ifMatch));
    }

    /**
     * Void an unposted invoice.
     *
     * @param id      invoice identifier
     * @param request void command
     * @param ifMatch expected version
     * @return the void invoice
     */
    @PostMapping("/invoices/{id}/void")
    public View voidInvoice(@PathVariable String id, @RequestBody VoidRequest request,
                            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.voidInvoice(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Read one invoice with its lines and ledger trail.
     *
     * @param id invoice identifier
     * @return the invoice view
     */
    @GetMapping("/invoices/{id}")
    public View get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Read the finance-only daily reconciliation report.
     *
     * @return daily totals by currency
     */
    @GetMapping("/reconciliation")
    public Reconciliation reconcile() {
        return service.reconcile();
    }
}
