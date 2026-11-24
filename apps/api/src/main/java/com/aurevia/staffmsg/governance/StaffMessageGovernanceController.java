package com.aurevia.staffmsg.governance;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.messaging.governance.MessagingAuditContract.LegalHold;
import com.aurevia.messaging.governance.MessagingAuditContract.PlaceHoldRequest;
import com.aurevia.messaging.governance.MessagingAuditContract.RetentionDecision;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.AuditEvent;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.ConversationExport;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.ExportRequest;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.HideRequest;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.RecordActionRequest;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.SearchPage;
import com.aurevia.staffmsg.governance.StaffMessageGovernanceContract.SearchQuery;

/**
 * Thin HTTP adapter for staff-message governance (MSG-07, ARC-03).
 *
 * <p>All tenant derivation, authorization, retention and hold decisions live in
 * {@link com.aurevia.staffmsg.governance.StaffMessageGovernanceService};
 * this adapter binds fields, validates query bounds through the service and maps
 * created resources to their Location headers. No identity or role is accepted
 * from a request body.</p>
 */
@RestController
@RequestMapping("/api/v1/staff-messages/governance")
public final class StaffMessageGovernanceController {

    private static final String BASE_PATH = "/api/v1/staff-messages/governance";

    private final StaffMessageGovernanceService service;

    /**
     * Create the route with the injected governance service.
     *
     * <p>The service is a Spring bean, so it is injected rather than built in
     * the adapter; HTTP tests construct the same controller with a service wired
     * over the synthetic store.</p>
     *
     * @param service governance domain service
     */
    @Autowired
    public StaffMessageGovernanceController(StaffMessageGovernanceService service) {
        this.service = service;
    }

    /**
     * Record one audited staff action.
     *
     * @param request action command
     * @return the appended audit fact
     */
    @PostMapping("/actions")
    public AuditEvent recordAction(@RequestBody RecordActionRequest request) {
        return service.recordAction(request);
    }

    /**
     * Hide a message in the actor's folder without purging the durable row.
     *
     * @param messageId      message to hide
     * @param conversationId owning conversation
     * @return the appended audit fact
     */
    @PostMapping("/messages/{messageId}/hide")
    public AuditEvent hide(@PathVariable String messageId, @RequestParam String conversationId) {
        return service.hideMessage(new HideRequest(messageId, conversationId));
    }

    /**
     * Read the retention decision for one conversation.
     *
     * @param conversationId conversation identifier
     * @return the explicit decision
     */
    @GetMapping("/retention/{conversationId}")
    public RetentionDecision retention(@PathVariable String conversationId) {
        return service.retentionDecision(conversationId);
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
        return ResponseEntity.created(URI.create(BASE_PATH + "/legal-holds/" + hold.id())).body(hold);
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
     * Search the actor's own conversations and folders.
     *
     * @param senderSubject optional exact sender filter
     * @param subject       optional subject fragment
     * @param from          optional inclusive lower sent-at bound
     * @param to            optional inclusive upper sent-at bound
     * @param text          optional body fragment
     * @param cursor        optional opaque continuation cursor
     * @param limit         requested page size
     * @return a cursor-paged result with nextCursor on a further page
     */
    @GetMapping("/search")
    public SearchPage search(@RequestParam(required = false) String senderSubject,
                             @RequestParam(required = false) String subject,
                             @RequestParam(required = false) Instant from,
                             @RequestParam(required = false) Instant to,
                             @RequestParam(required = false) String text,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(required = false) Integer limit) {
        return service.search(new SearchQuery(senderSubject, subject, from, to, text, cursor, limit));
    }

    /**
     * Produce a records-officer conversation export carrying attachment checksums.
     *
     * @param request export command
     * @return HTTP 201 with the export and its checksum snapshot
     */
    @PostMapping("/exports")
    public ResponseEntity<ConversationExport> exportConversation(@RequestBody ExportRequest request) {
        ConversationExport export = service.exportConversation(request);
        return ResponseEntity.created(URI.create(BASE_PATH + "/exports/" + export.id())).body(export);
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
}
