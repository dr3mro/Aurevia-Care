package com.aurevia.authz.access;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.authz.access.AccessContracts.AccessDecision;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantRequest;
import com.aurevia.authz.access.AccessContracts.EmergencyGrantView;
import com.aurevia.authz.access.AccessContracts.ReviewRequest;
import com.aurevia.authz.access.AccessContracts.RevokeRequest;
import com.aurevia.authz.access.AccessContracts.SupportCheckRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentRequest;
import com.aurevia.authz.access.AccessContracts.SupportIncidentView;

/**
 * Thin HTTP adapter for the IAM-06 access controls.
 *
 * <p>All authorization, timing and audit invariants live in the two domain services;
 * this adapter only maps commands and outcomes to HTTP responses. No organization,
 * actor, role or facility is taken from the request body.</p>
 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessController {

    private final EmergencyAccessService emergency;
    private final SupportAccessService support;

    /**
     * Create the controller.
     *
     * @param emergency emergency access domain service
     * @param support   platform support access domain service
     */
    public AccessController(EmergencyAccessService emergency, SupportAccessService support) {
        this.emergency = emergency;
        this.support = support;
    }

    /**
     * Grant time-limited emergency access to the authenticated clinician.
     *
     * @param request validated grant command
     * @return HTTP 201 with a Location header and the created grant
     */
    @PostMapping("/emergency")
    public ResponseEntity<EmergencyGrantView> grant(@RequestBody EmergencyGrantRequest request) {
        EmergencyGrantView created = emergency.grant(request);
        return ResponseEntity.created(URI.create("/api/v1/access/emergency/" + created.id())).body(created);
    }

    /**
     * Return the current emergency access decision for the authenticated clinician.
     *
     * @return explicit allow/deny decision with a stable reason code
     */
    @GetMapping("/emergency/active")
    public AccessDecision active() {
        return emergency.active();
    }

    /**
     * End an emergency grant early with a recorded reason.
     *
     * @param id      grant identifier from the path
     * @param request recorded revocation reason
     * @return the updated grant projection
     */
    @PostMapping("/emergency/{id}/revoke")
    public EmergencyGrantView revoke(@PathVariable String id, @RequestBody RevokeRequest request) {
        return emergency.revoke(id, request);
    }

    /**
     * Record an independent review of an emergency grant.
     *
     * @param id      grant identifier from the path
     * @param request reviewer finding
     * @return the reviewed grant projection
     */
    @PostMapping("/emergency/{id}/review")
    public EmergencyGrantView review(@PathVariable String id, @RequestBody ReviewRequest request) {
        return emergency.review(id, request);
    }

    /**
     * List visible grants with unreviewed ones first.
     *
     * @return the review worklist
     */
    @GetMapping("/emergency/review-queue")
    public List<EmergencyGrantView> reviewQueue() {
        return emergency.reviewQueue();
    }

    /**
     * Open a pending platform support incident.
     *
     * @param request validated incident command
     * @return HTTP 201 with the pending incident
     */
    @PostMapping("/support/incidents")
    public ResponseEntity<SupportIncidentView> createIncident(@RequestBody SupportIncidentRequest request) {
        SupportIncidentView created = support.createIncident(request);
        return ResponseEntity.created(
                URI.create("/api/v1/access/support/incidents/" + created.reference())).body(created);
    }

    /**
     * Approve a pending incident from the separate privacy-auditor role.
     *
     * @param reference tenant-local incident reference
     * @return the approved incident projection
     */
    @PostMapping("/support/incidents/{reference}/approve")
    public SupportIncidentView approveIncident(@PathVariable String reference) {
        return support.approveIncident(reference);
    }

    /**
     * Test whether platform support access is currently authorized.
     *
     * @param request incident reference to evaluate
     * @return explicit default-deny decision with a stable reason code
     */
    @PostMapping("/support/check")
    public AccessDecision checkAccess(@RequestBody SupportCheckRequest request) {
        return support.checkAccess(request);
    }
}
