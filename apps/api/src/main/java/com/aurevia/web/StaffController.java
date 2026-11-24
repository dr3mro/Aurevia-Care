package com.aurevia.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.staff.StaffHistory;
import com.aurevia.staff.StaffMember;
import com.aurevia.staff.StaffPage;
import com.aurevia.staff.StaffService;

/**
 * Thin HTTP adapter for tenant staff lifecycle administration.
 *
 * <p>All authorization and history invariants live in the staff domain service;
 * this adapter only maps outcomes to status codes and headers.</p>
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

    private final StaffService service;

    /**
     * Create the controller.
     *
     * @param service staff lifecycle domain service
     */
    public StaffController(StaffService service) {
        this.service = service;
    }

    /**
     * Invite a staff member in the active organization.
     *
     * @param request validated invitation
     * @return HTTP 201 with a Location header and the created member
     */
    @PostMapping
    public ResponseEntity<StaffMember> invite(@RequestBody InviteRequest request) {
        StaffMember created = service.invite(request.subject(), request.displayName(), request.role());
        return ResponseEntity.created(URI.create("/api/v1/staff/" + created.subject())).body(created);
    }

    /**
     * List staff members in the active organization with cursor pagination.
     *
     * @param cursor opaque cursor of the previous page's last row
     * @param limit  page size, default 50 and at most 200
     * @param status optional {@code active} or {@code deactivated} filter
     * @return one page of staff members
     */
    @GetMapping
    public StaffPage list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String status) {
        return service.list(cursor, limit, status);
    }

    /**
     * Deactivate a staff member by appending a revoked membership event.
     *
     * @param subject staff subject
     * @return the deactivated member
     */
    @PostMapping("/{subject}/deactivate")
    public StaffMember deactivate(@PathVariable String subject) {
        return service.deactivate(subject);
    }

    /**
     * Read the effective projection of a staff member.
     *
     * @param subject staff subject
     * @return the visible member
     */
    @GetMapping("/{subject}")
    public StaffMember get(@PathVariable String subject) {
        return service.get(subject);
    }

    /**
     * Read the immutable staff history.
     *
     * @param subject staff subject
     * @return the visible history
     */
    @GetMapping("/{subject}/history")
    public StaffHistory history(@PathVariable String subject) {
        return service.history(subject);
    }

    /**
     * Invitation request body; organization, grantor and dates are never accepted from the client.
     *
     * @param subject     subject to invite
     * @param displayName display name
     * @param role        bounded role key
     * @return the immutable request
     */
    public record InviteRequest(String subject, String displayName, String role) {
    }
}
