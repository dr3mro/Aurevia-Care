package com.aurevia.staff;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for staff invitation administration (IAM-02).
 *
 * <p>All authorization, validation and atomicity live in the invitation domain
 * service and its database boundary. This adapter only maps outcomes to status
 * codes and headers. The accept command still runs inside an authenticated
 * tenant context: the token replaces a role check, not tenant selection.</p>
 */
@RestController
@RequestMapping("/api/v1/staff-invitations")
public class StaffInvitationController {

    private final StaffInvitationService service;

    /**
     * Create the controller.
     *
     * @param service staff invitation domain service
     */
    public StaffInvitationController(StaffInvitationService service) {
        this.service = service;
    }

    /**
     * Issue a single-use invitation.
     *
     * @param request bounded invitation input
     * @return HTTP 201 with a Location header and the one-time raw token
     */
    @PostMapping
    public ResponseEntity<StaffInvitationIssue> create(@RequestBody CreateInvitationRequest request) {
        StaffInvitationIssue issue = service.create(request.subject(), request.displayName(),
                request.facilityId(), request.roles(), request.ttlSeconds());
        return ResponseEntity.created(URI.create("/api/v1/staff-invitations/" + issue.id())).body(issue);
    }

    /**
     * List pending invitations in the active organization.
     *
     * @return pending invitation projections, never containing a raw token
     */
    @GetMapping("/pending")
    public List<StaffInvitation> pending() {
        return service.listPending();
    }

    /**
     * Revoke an unused invitation.
     *
     * @param id opaque invitation identifier
     * @return the revoked projection
     */
    @PostMapping("/{id}/revoke")
    public StaffInvitation revoke(@PathVariable String id) {
        return service.revoke(id);
    }

    /**
     * Accept an invitation with its one-time token.
     *
     * @param request token supplied by the invitee
     * @return the created membership projection
     */
    @PostMapping("/accept")
    public StaffInvitationAcceptance accept(@RequestBody AcceptInvitationRequest request) {
        return service.accept(request.token());
    }

    /**
     * Invitation issue body; organization, grantor and token are never accepted here.
     *
     * @param subject     subject to invite
     * @param displayName display name
     * @param facilityId  facility identifier
     * @param roles       bounded role keys
     * @param ttlSeconds  lifetime in seconds
     * @return the immutable request
     */
    public record CreateInvitationRequest(String subject, String displayName, String facilityId,
                                          List<String> roles, long ttlSeconds) {

        /**
         * Freeze the role list so a deserialized body cannot be mutated in memory.
         * A missing list becomes empty so the service can answer 422 instead of leaking a null.
         *
         * @throws NullPointerException never; null is normalized to an empty list
         */
        public CreateInvitationRequest {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        /**
         * Return the immutable role keys.
         *
         * @return role keys
         */
        @Override
        public List<String> roles() {
            return List.copyOf(roles);
        }
    }

    /**
     * Invitation acceptance body.
     *
     * @param token one-time raw token
     * @return the immutable request
     */
    public record AcceptInvitationRequest(String token) {
    }
}
