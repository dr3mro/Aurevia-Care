package com.aurevia.prescription.rx;

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
import com.aurevia.prescription.rx.RxContract.AttestRequest;
import com.aurevia.prescription.rx.RxContract.CancelRequest;
import com.aurevia.prescription.rx.RxContract.ConfirmRequest;
import com.aurevia.prescription.rx.RxContract.CreateRequest;
import com.aurevia.prescription.rx.RxContract.DispensingRequest;
import com.aurevia.prescription.rx.RxContract.PharmacyResponseRequest;
import com.aurevia.prescription.rx.RxContract.Prescription;
import com.aurevia.prescription.rx.RxContract.RenewRequest;
import com.aurevia.prescription.rx.RxContract.RetryRequest;
import com.aurevia.prescription.rx.RxContract.TransmitRequest;
import com.aurevia.prescription.rx.RxContract.VerifyRequest;
import com.aurevia.prescription.rx.RxContract.View;

/**
 * Thin HTTP adapter for prescription transmission.
 *
 * <p>Every irreversible command requires an {@code If-Match} version, parsed at
 * this boundary. Authorization, attestation, entitlement, channel approval and
 * ambiguity-reconciliation invariants live in the domain service.</p>
 */
@RestController
@RequestMapping("/api/v1/prescriptions")
public class RxController {

    private final RxService service;

    /**
     * Create the controller.
     *
     * @param service prescription transmission domain service
     */
    public RxController(RxService service) {
        this.service = service;
    }

    /**
     * Register a transmission record for a prescription draft.
     *
     * @param request registration command
     * @return HTTP 201 with the created draft
     */
    @PostMapping
    public ResponseEntity<Prescription> create(@RequestBody CreateRequest request) {
        Prescription created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/prescriptions/" + created.id())).body(created);
    }

    /**
     * Read one prescription with its event trail.
     *
     * @param id prescription identifier
     * @return the prescription and its immutable facts
     */
    @GetMapping("/{id}")
    public View get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Produce the printable local prescription with the prescriber attestation.
     *
     * @param id prescription identifier
     * @return the immutable print-ready prescription
     */
    @GetMapping("/{id}/printable")
    public PrintablePrescription printable(@PathVariable String id) {
        return service.printable(id);
    }

    /**
     * Record prescriber attestation.
     *
     * @param id      prescription identifier
     * @param request attestation command
     * @param ifMatch expected version
     * @return the attested prescription
     */
    @PostMapping("/{id}/attest")
    public Prescription attest(@PathVariable String id, @RequestBody AttestRequest request,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.attest(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Route an attested prescription through an approved channel.
     *
     * @param id      prescription identifier
     * @param request transmission command
     * @param ifMatch expected version
     * @return the transmitted prescription
     */
    @PostMapping("/{id}/transmit")
    public Prescription transmit(@PathVariable String id, @RequestBody TransmitRequest request,
                                 @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.transmit(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Confirm delivery of the stable submission id.
     *
     * @param id      prescription identifier
     * @param request confirmation command
     * @param ifMatch expected version
     * @return the prescription awaiting a pharmacy response
     */
    @PostMapping("/{id}/confirm-delivery")
    public Prescription confirmDelivery(@PathVariable String id, @RequestBody ConfirmRequest request,
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.confirmDelivery(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Verify partner status for an ambiguous submission.
     *
     * @param id      prescription identifier
     * @param request verification command
     * @param ifMatch expected version
     * @return the reconciled prescription
     */
    @PostMapping("/{id}/verify-partner-status")
    public Prescription verifyPartnerStatus(@PathVariable String id, @RequestBody VerifyRequest request,
                                            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.verifyPartnerStatus(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Retry a verified-absent submission with the same submission id.
     *
     * @param id      prescription identifier
     * @param request retry command
     * @param ifMatch expected version
     * @return the resubmitted prescription
     */
    @PostMapping("/{id}/retry")
    public Prescription retry(@PathVariable String id, @RequestBody RetryRequest request,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.retry(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record a pharmacy response.
     *
     * @param id      prescription identifier
     * @param request pharmacy response command
     * @param ifMatch expected version
     * @return the prescription in the response state
     */
    @PostMapping("/{id}/pharmacy-response")
    public Prescription pharmacyResponse(@PathVariable String id, @RequestBody PharmacyResponseRequest request,
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.pharmacyResponse(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record partner-supplied dispensing evidence.
     *
     * @param id      prescription identifier
     * @param request dispensing command
     * @param ifMatch expected version
     * @return the dispensed prescription
     */
    @PostMapping("/{id}/dispensing")
    public Prescription recordDispensing(@PathVariable String id, @RequestBody DispensingRequest request,
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.recordDispensing(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record a renewal linked to the original submission.
     *
     * @param id      prescription identifier
     * @param request renewal command
     * @param ifMatch expected version
     * @return the prescription with a renewal fact
     */
    @PostMapping("/{id}/renew")
    public Prescription renew(@PathVariable String id, @RequestBody RenewRequest request,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.renew(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Cancel a live prescription with a reason.
     *
     * @param id      prescription identifier
     * @param request cancellation command
     * @param ifMatch expected version
     * @return the cancelled prescription
     */
    @PostMapping("/{id}/cancel")
    public Prescription cancel(@PathVariable String id, @RequestBody CancelRequest request,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.cancel(id, request, IfMatch.parse(ifMatch));
    }
}
