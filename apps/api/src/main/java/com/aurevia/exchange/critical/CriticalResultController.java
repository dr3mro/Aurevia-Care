package com.aurevia.exchange.critical;

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
import com.aurevia.exchange.critical.CriticalContract.AcknowledgeRequest;
import com.aurevia.exchange.critical.CriticalContract.AssignRequest;
import com.aurevia.exchange.critical.CriticalContract.EscalateRequest;
import com.aurevia.exchange.critical.CriticalContract.FollowUpRequest;
import com.aurevia.exchange.critical.CriticalContract.ReadBackRequest;
import com.aurevia.exchange.critical.CriticalContract.RegisterRequest;
import com.aurevia.exchange.critical.CriticalContract.ReleaseRequest;
import com.aurevia.exchange.critical.CriticalContract.Report;
import com.aurevia.exchange.critical.CriticalContract.Result;
import com.aurevia.exchange.critical.CriticalContract.VerifyRequest;
import com.aurevia.exchange.critical.CriticalContract.View;

/**
 * Thin HTTP adapter for XCH-05 critical results (ARC-03, ARC-04).
 *
 * <p>Every irreversible command requires an {@code If-Match} version, parsed at
 * this boundary. Authorization, verification, escalation and acknowledgment
 * invariants live in the domain service.</p>
 */
@RestController
@RequestMapping("/api/v1/critical-results")
public class CriticalResultController {

    private final CriticalResultService service;

    /**
     * Create the controller.
     *
     * @param service critical-result domain service
     */
    public CriticalResultController(CriticalResultService service) {
        this.service = service;
    }

    /**
     * Register a critical finding.
     *
     * @param request registration command
     * @return HTTP 201 with the created result
     */
    @PostMapping
    public ResponseEntity<Result> register(@RequestBody RegisterRequest request) {
        Result created = service.register(request);
        return ResponseEntity.created(URI.create("/api/v1/critical-results/" + created.id())).body(created);
    }

    /**
     * Read the operational report before the single-result route can match it.
     *
     * @return overdue alerts, roster gaps and failed notifications
     */
    @GetMapping("/report")
    public Report report() {
        return service.report();
    }

    /**
     * Read one result with its event trail.
     *
     * @param id result identifier
     * @return the result and its facts
     */
    @GetMapping("/{id}")
    public View get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Record verifier confirmation.
     *
     * @param id      result identifier
     * @param request verification command
     * @param ifMatch expected version
     * @return the activated result
     */
    @PostMapping("/{id}/verify")
    public Result verify(@PathVariable String id, @RequestBody VerifyRequest request,
                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.verify(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Assign the responsible owner and deadline.
     *
     * @param id      result identifier
     * @param request assignment command
     * @param ifMatch expected version
     * @return the assigned result
     */
    @PostMapping("/{id}/assign")
    public Result assign(@PathVariable String id, @RequestBody AssignRequest request,
                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.assign(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record one escalation notification attempt.
     *
     * @param id      result identifier
     * @param request escalation command
     * @param ifMatch expected version
     * @return the unchanged result
     */
    @PostMapping("/{id}/escalate")
    public Result escalate(@PathVariable String id, @RequestBody EscalateRequest request,
                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.escalate(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record a verbatim read-back.
     *
     * @param id      result identifier
     * @param request read-back command
     * @param ifMatch expected version
     * @return the unchanged result
     */
    @PostMapping("/{id}/read-back")
    public Result readBack(@PathVariable String id, @RequestBody ReadBackRequest request,
                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.readBack(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Acknowledge the exact revision as a clinician.
     *
     * @param id      result identifier
     * @param request acknowledgment command
     * @param ifMatch expected version
     * @return the acknowledged result
     */
    @PostMapping("/{id}/acknowledge")
    public Result acknowledge(@PathVariable String id, @RequestBody AcknowledgeRequest request,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.acknowledge(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record clinical follow-up and close the alert.
     *
     * @param id      result identifier
     * @param request follow-up command
     * @param ifMatch expected version
     * @return the closed result
     */
    @PostMapping("/{id}/follow-up")
    public Result followUp(@PathVariable String id, @RequestBody FollowUpRequest request,
                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.followUp(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record a patient release, which is not a clinical acknowledgment.
     *
     * @param id      result identifier
     * @param request release command
     * @param ifMatch expected version
     * @return the unchanged result
     */
    @PostMapping("/{id}/release")
    public Result release(@PathVariable String id, @RequestBody ReleaseRequest request,
                          @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.release(id, request, IfMatch.parse(ifMatch));
    }
}
