package com.aurevia.messaging.governance;

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

import com.aurevia.messaging.governance.MessagingAuditContract.AuditEvent;
import com.aurevia.messaging.governance.MessagingAuditContract.CopyRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.CreateExportRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.ExportRecord;
import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.LinkToChartRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.ProvenanceLink;
import com.aurevia.messaging.governance.MessagingAuditContract.RecordActionRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.RequestCopyRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.RevocationEffect;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDecision;

/**
 * Thin HTTP adapter for MSG-07 governance.
 *
 * <p>All tenant derivation, authorization, retention and revocation decisions
 * live in {@link com.aurevia.messaging.governance.MessagingGovernanceService}.
 * This adapter only binds fields and maps created resources to their Location
 * headers.</p>
 */
@RestController
@RequestMapping("/api/v1/messaging-governance")
public class MessagingGovernanceController {

    private final MessagingGovernanceService service;

    /**
     * Create the controller.
     *
     * @param service MSG-07 governance domain service
     */
    public MessagingGovernanceController(MessagingGovernanceService service) {
        this.service = service;
    }

    /**
     * Read the tenant's audit trail, optionally scoped to one resource.
     *
     * @param resourceId optional exact resource filter
     * @return audit facts in insertion order
     */
    @GetMapping("/audit")
    public List<AuditEvent> audit(@RequestParam(required = false) String resourceId) {
        return service.auditTrail(resourceId);
    }

    /**
     * Record one audited messaging action.
     *
     * @param request action command
     * @return the appended audit fact
     */
    @PostMapping("/actions")
    public AuditEvent recordAction(@RequestBody RecordActionRequest request) {
        return service.recordAction(request);
    }

    /**
     * Place a legal hold.
     *
     * @param request hold command
     * @return HTTP 201 with the created hold
     */
    @PostMapping("/legal-holds")
    public ResponseEntity<LegalHold> placeHold(@RequestBody PlaceHoldRequest request) {
        LegalHold hold = service.placeHold(request);
        return ResponseEntity.created(
                URI.create("/api/v1/messaging-governance/legal-holds/" + hold.id())).body(hold);
    }

    /**
     * Release a legal hold.
     *
     * @param id     hold identifier
     * @param reason non-sensitive release reason
     * @return the released hold
     */
    @PostMapping("/legal-holds/{id}/release")
    public LegalHold releaseHold(@PathVariable String id, @RequestParam String reason) {
        return service.releaseHold(id, reason);
    }

    /**
     * Read the retention decision for one resource.
     *
     * @param resourceId resource identifier
     * @return the explicit decision
     */
    @GetMapping("/retention/{resourceId}")
    public RetentionDecision retention(@PathVariable String resourceId) {
        return service.retentionDecision(resourceId);
    }

    /**
     * Create a scoped, expiring export.
     *
     * @param request export command
     * @return HTTP 201 with the export record
     */
    @PostMapping("/exports")
    public ResponseEntity<ExportRecord> createExport(@RequestBody CreateExportRequest request) {
        ExportRecord export = service.createExport(request);
        return ResponseEntity.created(
                URI.create("/api/v1/messaging-governance/exports/" + export.id())).body(export);
    }

    /**
     * Link a source message or instruction to the chart with provenance.
     *
     * @param request provenance command
     * @return HTTP 201 with the provenance link
     */
    @PostMapping("/chart-links")
    public ResponseEntity<ProvenanceLink> linkToChart(@RequestBody LinkToChartRequest request) {
        ProvenanceLink link = service.linkToChart(request);
        return ResponseEntity.created(
                URI.create("/api/v1/messaging-governance/chart-links/" + link.id())).body(link);
    }

    /**
     * Record the effects of a revoked connection.
     *
     * @param id revoked connection identifier
     * @return the recorded effect
     */
    @PostMapping("/connections/{id}/revocation-effects")
    public RevocationEffect recordRevocation(@PathVariable String id) {
        return service.recordRevocation(id);
    }

    /**
     * Route a copy request to the site record-access procedure.
     *
     * @param request copy command
     * @return the routed copy request
     */
    @PostMapping("/copy-requests")
    public CopyRequest requestCopy(@RequestBody RequestCopyRequest request) {
        return service.requestCopy(request);
    }
}
