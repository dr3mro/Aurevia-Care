package com.aurevia.pharmacyflow.status;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.api.IfMatch;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.AcceptCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.ClarificationCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.DispenseCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusCommands.RejectCommand;
import com.aurevia.pharmacyflow.status.PharmacyStatusContract.StatusEvent;
import com.aurevia.pharmacyflow.status.PharmacyStatusContract.StatusView;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for pharmacy status facts (RX-02, ARC-03).
 *
 * <p>This controller only binds paths, headers and command bodies. Tenant and
 * actor come from the request context, active roles from freshly resolved
 * membership, and every fact is appended by the injected domain service. Both
 * {@code If-Match} and {@code Idempotency-Key} are required on every write.</p>
 */
@RestController
@RequestMapping("/api/v1/pharmacy/status")
public final class PharmacyStatusController {

    private final PharmacyStatusService service;
    private final StaffRepository staff;

    /**
     * Create the route over the injected status kernel.
     *
     * @param service status domain service
     * @param staff   fresh membership and role source
     */
    @Autowired
    public PharmacyStatusController(PharmacyStatusService service, StaffRepository staff) {
        this.service = service;
        this.staff = staff;
    }

    /**
     * Accept a prescription.
     *
     * @param prescriptionId prescription identifier from the path
     * @param ifMatch        expected status version
     * @param idempotencyKey retry key for this action
     * @return the appended acceptance fact
     */
    @PostMapping("/{prescriptionId}/accept")
    public StatusEvent accept(@PathVariable String prescriptionId,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        TenantContext context = context();
        return service.accept(new AcceptCommand(context.organizationId(), context.subject(),
                activeRoles(context), prescriptionId, IfMatch.parse(ifMatch), key(idempotencyKey)));
    }

    /**
     * Reject a prescription with a mandatory reason.
     *
     * @param prescriptionId prescription identifier from the path
     * @param ifMatch        expected status version
     * @param idempotencyKey retry key for this action
     * @param request        rejection command carrying the reason
     * @return the appended rejection fact
     */
    @PostMapping("/{prescriptionId}/reject")
    public StatusEvent reject(@PathVariable String prescriptionId,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                              @RequestBody RejectRequest request) {
        TenantContext context = context();
        return service.reject(new RejectCommand(context.organizationId(), context.subject(),
                activeRoles(context), prescriptionId, IfMatch.parse(ifMatch), key(idempotencyKey),
                request.reason()));
    }

    /**
     * Ask the prescriber for clarification.
     *
     * @param prescriptionId prescription identifier from the path
     * @param ifMatch        expected status version
     * @param idempotencyKey retry key for this action
     * @param request        clarification command carrying the question
     * @return the appended clarification fact
     */
    @PostMapping("/{prescriptionId}/clarification")
    public StatusEvent askClarification(@PathVariable String prescriptionId,
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @RequestBody ClarificationRequest request) {
        TenantContext context = context();
        return service.askClarification(new ClarificationCommand(context.organizationId(),
                context.subject(), activeRoles(context), prescriptionId, IfMatch.parse(ifMatch),
                key(idempotencyKey), request.question()));
    }

    /**
     * Record dispensing evidence, distinct from acceptance.
     *
     * @param prescriptionId prescription identifier from the path
     * @param ifMatch        expected status version
     * @param idempotencyKey retry key for this action
     * @param request        dispensing command carrying who, when and what
     * @return the appended dispensing fact
     */
    @PostMapping("/{prescriptionId}/dispensing-evidence")
    public StatusEvent recordDispensing(@PathVariable String prescriptionId,
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @RequestBody DispenseRequest request) {
        TenantContext context = context();
        return service.recordDispensing(new DispenseCommand(context.organizationId(),
                context.subject(), activeRoles(context), prescriptionId, IfMatch.parse(ifMatch),
                key(idempotencyKey), request.dispensedBy(), parseInstant(request.dispensedAt()),
                request.handedOver()));
    }

    /**
     * Read the current pharmacy status of one prescription.
     *
     * @param prescriptionId prescription identifier from the path
     * @return the current projection and fact trail
     */
    @GetMapping("/{prescriptionId}")
    public StatusView view(@PathVariable String prescriptionId) {
        TenantContext context = context();
        return service.view(context.organizationId(), prescriptionId);
    }

    /**
     * Read the append-only fact trail of one prescription.
     *
     * @param prescriptionId prescription identifier from the path
     * @return the immutable facts in append order
     */
    @GetMapping("/{prescriptionId}/events")
    public List<StatusEvent> history(@PathVariable String prescriptionId) {
        TenantContext context = context();
        return service.history(context.organizationId(), prescriptionId);
    }

    private static String key(String header) {
        return IdempotencyKey.parse(header).value();
    }

    private static Instant parseInstant(String value) {
        if (value == null) {
            throw invalidTime();
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalidTime();
        }
    }

    private static ApiException invalidTime() {
        return new ApiException(422, "INVALID_DISPENSED_AT", "dispensedAt must be an ISO instant");
    }

    private Set<String> activeRoles(TenantContext context) {
        return Set.copyOf(staff.activeRoles(context.subject()));
    }

    private static TenantContext context() {
        return TenantContext.current();
    }

    /**
     * Rejection request body.
     *
     * @return this immutable record
     */
    public record RejectRequest(String reason) {
    }

    /**
     * Clarification request body.
     *
     * @return this immutable record
     */
    public record ClarificationRequest(String question) {
    }

    /**
     * Dispensing evidence request body.
     *
     * @return this immutable record
     */
    public record DispenseRequest(String dispensedBy, String dispensedAt, String handedOver) {
    }
}
