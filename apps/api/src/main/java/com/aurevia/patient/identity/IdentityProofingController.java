package com.aurevia.patient.identity;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for patient identity proofing (IAM-05).
 *
 * <p>Invariants and authorization live in {@code IdentityProofingService}; this
 * adapter only binds inputs and maps outcomes. The authenticated subject is
 * derived by the tenant interceptor and is never accepted from the body for
 * patient self-service commands.</p>
 */
@RestController
@RequestMapping("/api/v1/patient-identity")
public class IdentityProofingController {

    private final IdentityProofingService service;

    /**
     * Create the controller.
     *
     * @param service identity proofing domain service
     */
    public IdentityProofingController(IdentityProofingService service) {
        this.service = service;
    }

    /**
     * Enroll a site-verified patient account.
     *
     * @param request enrollment fields
     * @return the verified account with HTTP 201
     */
    @PostMapping("/accounts")
    public ResponseEntity<PatientAccount> enroll(@RequestBody EnrollRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.enroll(request.subject(), request.displayName()));
    }

    /**
     * Issue a single-use, expiring invitation.
     *
     * @param request invitation fields
     * @return the one-time token response with HTTP 201
     */
    @PostMapping("/invitations")
    public ResponseEntity<InvitationIssue> issue(@RequestBody InvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issueInvitation(
                request.subject(), request.chartId(), request.purpose(), request.ttlSeconds()));
    }

    /**
     * Redeem an invitation for the authenticated patient account.
     *
     * @param request the one-time token
     * @return the current link event
     */
    @PostMapping("/links/claim")
    public ChartLink claim(@RequestBody ClaimRequest request) {
        return service.linkWithInvitation(request.token());
    }

    /**
     * Unlink an account from a chart.
     *
     * @param request unlink fields
     * @return the current link event
     */
    @PostMapping("/links/unlink")
    public ChartLink unlink(@RequestBody UnlinkRequest request) {
        return service.unlink(request.subject(), request.chartId(), request.reason());
    }

    /**
     * Re-verify a relationship after lost access or a disputed identity.
     *
     * @param request re-verification fields
     * @return the fresh invitation with its one-time token
     */
    @PostMapping("/links/reverify")
    public InvitationIssue reverify(@RequestBody ReverifyRequest request) {
        return service.reverify(request.subject(), request.chartId(), request.reason());
    }

    /**
     * Check whether an account currently has verified access to a chart.
     *
     * @param subject account subject
     * @param chartId patient chart identifier
     * @return access decision
     */
    @GetMapping("/accounts/{subject}/access")
    public boolean hasAccess(@PathVariable String subject, @RequestParam String chartId) {
        return service.hasAccess(subject, chartId);
    }

    /**
     * List one current link per chart for an account.
     *
     * @param subject account subject
     * @return current link events
     */
    @GetMapping("/accounts/{subject}/links")
    public List<ChartLink> links(@PathVariable String subject) {
        return service.links(subject);
    }

    /**
     * Enrollment request; organization and acting staff are derived from context.
     *
     * @param subject     future login subject
     * @param displayName account label
     * @return the immutable request
     */
    public record EnrollRequest(String subject, String displayName) {
    }

    /**
     * Invitation request.
     *
     * @param subject    account the invitation is bound to
     * @param chartId    patient chart identifier
     * @param purpose    bounded purpose key
     * @param ttlSeconds lifetime in seconds
     * @return the immutable request
     */
    public record InvitationRequest(String subject, String chartId, String purpose, long ttlSeconds) {
    }

    /**
     * Redemption request carrying the one-time token.
     *
     * @param token one-time raw token
     * @return the immutable request
     */
    public record ClaimRequest(String token) {
    }

    /**
     * Unlink request.
     *
     * @param subject account subject owning the link
     * @param chartId patient chart identifier
     * @param reason  recorded reason
     * @return the immutable request
     */
    public record UnlinkRequest(String subject, String chartId, String reason) {
    }

    /**
     * Re-verification request.
     *
     * @param subject account subject owning the link
     * @param chartId patient chart identifier
     * @param reason  recorded reason
     * @return the immutable request
     */
    public record ReverifyRequest(String subject, String chartId, String reason) {
    }
}
