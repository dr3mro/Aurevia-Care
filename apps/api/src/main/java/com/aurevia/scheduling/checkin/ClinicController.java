package com.aurevia.scheduling.checkin;

import java.net.URI;
import java.util.List;

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
import com.aurevia.scheduling.checkin.CheckInCommands.Assign;
import com.aurevia.scheduling.checkin.CheckInCommands.Handoff;
import com.aurevia.scheduling.checkin.CheckInCommands.Intake;
import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;
import com.aurevia.scheduling.Appointment;

/**
 * Thin HTTP adapter for the reception workflow.
 *
 * <p>The controller parses the {@code If-Match} precondition and delegates all
 * authorization, state transitions, evidence and audit to
 * {@link com.aurevia.scheduling.checkin.CheckInService}. It lives with the slice so no shared controller or
 * package has to change when this workflow is added.</p>
 */
@RestController
@RequestMapping("/api/v1/clinic")
public class ClinicController {

    private final CheckInService service;

    /**
     * Create the controller.
     *
     * @param service check-in and worklist command service
     */
    public ClinicController(CheckInService service) {
        this.service = service;
    }

    /**
     * Record the identity evidence presented at check-in.
     *
     * @param request verification command
     * @return HTTP 201 with the immutable verification
     * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/verifications")
    public ResponseEntity<Verification> verify(@RequestBody VerifyIdentity request) {
        Verification created = service.verify(request);
        return ResponseEntity.created(URI.create("/api/v1/clinic/verifications/" + created.id()))
                .body(created);
    }

    /**
     * Check a verified patient in.
     *
     * @param id      appointment identifier
     * @param ifMatch expected appointment version
     * @return the worklist entry for the arrival
     * @throws com.aurevia.api.ApiException when authorization or validation fails or the supplied version is stale
     */
    @PostMapping("/appointments/{id}/check-in")
    public WorklistItem checkIn(@PathVariable String id,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.checkIn(id, IfMatch.parse(ifMatch));
    }

    /**
     * Open the clinical encounter for a verified appointment.
     *
     * @param id      appointment identifier
     * @param ifMatch expected appointment version
     * @return the in-progress appointment
     * @throws com.aurevia.api.ApiException when authorization or validation fails or the supplied version is stale
     */
    @PostMapping("/appointments/{id}/open-encounter")
    public Appointment openEncounter(@PathVariable String id,
                                     @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.openEncounter(id, IfMatch.parse(ifMatch));
    }

    /**
     * List the ordered worklist of one facility.
     *
     * @param facilityCode facility code
     * @param limit        optional bounded page size
     * @return the ordered items
     * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @GetMapping("/worklist")
    public List<WorklistItem> worklist(@RequestParam String facilityCode,
                                       @RequestParam(required = false) Integer limit) {
        return service.worklist(facilityCode, limit);
    }

    /**
     * Assign an unassigned worklist item.
     *
     * @param id      worklist entry identifier
     * @param request assignee subject
     * @param ifMatch expected entry version
     * @return the assigned entry
     * @throws com.aurevia.api.ApiException when authorization or validation fails or the supplied version is stale
     */
    @PostMapping("/worklist/{id}/assign")
    public WorklistItem assign(@PathVariable String id, @RequestBody Assign request,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.assign(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Transfer an assigned worklist item.
     *
     * @param id      worklist entry identifier
     * @param request receiving subject and reason
     * @param ifMatch expected entry version
     * @return the transferred entry
     * @throws com.aurevia.api.ApiException when authorization or validation fails or the supplied version is stale
     */
    @PostMapping("/worklist/{id}/handoff")
    public WorklistItem handoff(@PathVariable String id, @RequestBody Handoff request,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.handoff(id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Place an unscheduled arrival on the worklist.
     *
     * @param request intake command
     * @return HTTP 201 with the new worklist entry
     * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/worklist/intake")
    public ResponseEntity<WorklistItem> intake(@RequestBody Intake request) {
        WorklistItem created = service.linkIntake(request);
        return ResponseEntity.created(URI.create("/api/v1/clinic/worklist/" + created.id()))
                .body(created);
    }
}
