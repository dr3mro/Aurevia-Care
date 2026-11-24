package com.aurevia.pharmacyflow.clarify;

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

import com.aurevia.api.IfMatch;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.pharmacyflow.clarify.ClarificationCommands.AnswerCommand;
import com.aurevia.pharmacyflow.clarify.ClarificationCommands.OpenCommand;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.ThreadView;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for prescription clarification threads (RX-02, MSG-04).
 *
 * <p>This controller only binds paths, headers and command bodies. Tenant and
 * actor come from the request context and active roles from freshly resolved
 * membership. Every command is appended by the injected domain service; an
 * {@code Idempotency-Key} is required on both writes and an {@code If-Match}
 * version on the answer.</p>
 */
@RestController
@RequestMapping("/api/v1/pharmacy/clarifications")
public final class ClarificationController {

    private final ClarificationService service;
    private final StaffRepository staff;

    /**
     * Create the route over the injected clarification kernel.
     *
     * @param service clarification domain service
     * @param staff   fresh membership and role source
     */
    @Autowired
    public ClarificationController(ClarificationService service, StaffRepository staff) {
        this.service = service;
        this.staff = staff;
    }

    /**
     * Open a clarification request on a routed prescription.
     *
     * @param prescriptionId prescription identifier from the path
     * @param idem           retry key for this action
     * @param request        request body carrying the question
     * @return the opened thread addressed to the prescription's prescriber
     */
    @PostMapping("/{prescriptionId}/request")
    public ThreadView request(@PathVariable String prescriptionId,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                              @RequestBody OpenRequest request) {
        TenantContext context = context();
        return service.open(new OpenCommand(context.organizationId(), context.subject(), activeRoles(context),
                prescriptionId, request.question(), key(idem)));
    }

    /**
     * Answer an open request, closing it and reaching the pharmacy requester.
     *
     * @param threadId thread identifier from the path
     * @param ifMatch  expected thread version
     * @param idem     retry key for this action
     * @param request  answer body carrying text or an amended revision
     * @return the closed thread with the answer entry
     */
    @PostMapping("/{threadId}/answer")
    public ThreadView answer(@PathVariable String threadId,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                             @RequestBody AnswerRequest request) {
        TenantContext context = context();
        return service.answer(new AnswerCommand(context.organizationId(), context.subject(),
                activeRoles(context), threadId, IfMatch.parse(ifMatch), request.answerBody(),
                request.amendedRevisionId(), key(idem)));
    }

    /**
     * List overdue open requests visible to the caller.
     *
     * @return overdue thread views in the active tenant
     */
    @GetMapping("/overdue")
    public List<ThreadView> overdue() {
        TenantContext context = context();
        return service.overdue(context.organizationId(), context.subject(), activeRoles(context));
    }

    /**
     * Read one thread visible to one of its two parties.
     *
     * @param threadId thread identifier from the path
     * @return the thread with its trail and overdue flag
     */
    @GetMapping("/{threadId}")
    public ThreadView view(@PathVariable String threadId) {
        TenantContext context = context();
        return service.view(context.organizationId(), threadId, context.subject(), activeRoles(context));
    }

    private static String key(String header) {
        return IdempotencyKey.parse(header).value();
    }

    private Set<String> activeRoles(TenantContext context) {
        return Set.copyOf(staff.activeRoles(context.subject()));
    }

    private static TenantContext context() {
        return TenantContext.current();
    }

    /**
     * Request body for opening a thread.
     *
     * @return this immutable body
     */
    public record OpenRequest(String question) {
    }

    /**
     * Request body for answering a thread with text or an amended revision.
     *
     * @return this immutable body
     */
    public record AnswerRequest(String answerBody, String amendedRevisionId) {
    }
}
