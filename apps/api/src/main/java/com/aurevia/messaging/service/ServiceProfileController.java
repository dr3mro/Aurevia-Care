package com.aurevia.messaging.service;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.messaging.service.ServiceProfileContract.ProfileHistory;
import com.aurevia.messaging.service.ServiceProfileContract.PublishProfileRequest;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceAvailability;
import com.aurevia.messaging.service.ServiceProfileContract.ServiceProfile;
import com.aurevia.messaging.service.ServiceProfileContract.SupervisorSnapshot;

/**
 * Thin HTTP adapter for the MSG-06 service-profile slice.
 *
 * <p>All tenant derivation, authorization, approval and time decisions live in
 * {@link com.aurevia.messaging.service.ServiceProfileService}. This adapter binds path, query and header
 * fields and parses the version precondition; it never decides policy.</p>
 */
@RestController
@RequestMapping("/api/v1/care-units/{unitId}")
public class ServiceProfileController {

    private final ServiceProfileService service;

    /**
     * Create the controller.
     *
     * @param service service-profile domain service
     */
    public ServiceProfileController(ServiceProfileService service) {
        this.service = service;
    }

    /**
     * Show the effective published service information before a patient sends.
     *
     * @param unitId recipient unit identifier
     * @return the effective approved profile
     */
    @GetMapping("/service-profile")
    public ServiceProfile view(@PathVariable String unitId) {
        return service.viewProfile(unitId);
    }

    /**
     * Show whether the unit is open and when it next handles routine messages.
     *
     * @param unitId recipient unit identifier
     * @return the availability projection
     */
    @GetMapping("/availability")
    public ServiceAvailability availability(@PathVariable String unitId) {
        return service.availability(unitId);
    }

    /**
     * Show immutable version history for governance review.
     *
     * @param unitId recipient unit identifier
     * @return the profile versions
     */
    @GetMapping("/service-profile/versions")
    public ProfileHistory history(@PathVariable String unitId) {
        return service.history(unitId);
    }

    /**
     * Show derived operational counters to an authorized unit supervisor.
     *
     * @param unitId recipient unit identifier
     * @return the supervisor snapshot
     */
    @GetMapping("/supervisor-snapshot")
    public SupervisorSnapshot supervisor(@PathVariable String unitId) {
        return service.supervisorSnapshot(unitId);
    }

    /**
     * Publish a new, unapproved service-profile version.
     *
     * @param unitId  recipient unit identifier
     * @param request publish command fields
     * @return HTTP 201 with the stored draft
     */
    @PostMapping("/service-profiles")
    public ResponseEntity<ServiceProfile> publish(@PathVariable String unitId,
                                                   @RequestBody PublishProfileRequest request) {
        ServiceProfile created = service.publish(withUnit(request, unitId));
        return ResponseEntity.created(
                URI.create("/api/v1/service-profiles/" + created.id())).body(created);
    }

    /**
     * Name-approve a draft profile against its version precondition.
     *
     * @param profileId   profile identifier
     * @param ifMatch     version precondition header
     * @param approvalNote named approval rationale
     * @return the approved profile
     */
    @PostMapping("/service-profiles/{profileId}/approve")
    public ServiceProfile approve(@PathVariable String profileId,
                                  @RequestHeader("If-Match") String ifMatch,
                                  @RequestParam("note") String approvalNote) {
        return service.approve(profileId, IfMatch.parse(ifMatch), approvalNote);
    }

    private static PublishProfileRequest withUnit(PublishProfileRequest request, String unitId) {
        return new PublishProfileRequest(unitId, request.timeZone(), request.windows(),
                request.responseTargetMinutes(), request.queueOwner(), request.backupCoverage(),
                request.escalationChannel(), request.urgentChannel(), request.downtimeChannel(),
                request.topics(), request.urgentInstructions(), request.effectiveFrom(),
                request.expectedVersion());
    }
}
