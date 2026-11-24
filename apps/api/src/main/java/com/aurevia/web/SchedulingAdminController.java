package com.aurevia.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.scheduling.AvailabilityView;
import com.aurevia.scheduling.ResourceView;
import com.aurevia.scheduling.SchedulingAdminService;
import com.aurevia.scheduling.VisitTypeView;

/**
 * Thin HTTP adapter for scheduling administration (CLN-01, ADM-01).
 *
 * <p>Only tenant administrators may define visit durations, protected resources
 * and weekly windows; the decision and its audit event live in
 * {@link com.aurevia.scheduling.SchedulingAdminService}.</p>
 */
@RestController
@RequestMapping("/api/v1/scheduling")
public class SchedulingAdminController {

    private final SchedulingAdminService service;

    /**
     * Create the controller.
     *
     * @param service scheduling administration service
     */
    public SchedulingAdminController(SchedulingAdminService service) {
        this.service = service;
    }

    /**
     * Define a visit type.
     *
     * @param request visit type fields
     * @return HTTP 201 with the created visit type
     */
    @PostMapping("/visit-types")
    public ResponseEntity<VisitTypeView> createVisitType(@RequestBody VisitTypeRequest request) {
        return ResponseEntity.status(201)
                .body(service.createVisitType(request.code(), request.durationMinutes()));
    }

    /**
     * Define a protected resource.
     *
     * @param request resource fields
     * @return HTTP 201 with the created resource
     */
    @PostMapping("/resources")
    public ResponseEntity<ResourceView> createResource(@RequestBody ResourceRequest request) {
        return ResponseEntity.status(201).body(service.createResource(request.facilityCode(),
                request.kind(), request.code(), request.displayName()));
    }

    /**
     * List the resources of one facility.
     *
     * @param facilityCode tenant-local facility code
     * @return the visible resources
     */
    @GetMapping("/resources")
    public List<ResourceView> resources(@RequestParam String facilityCode) {
        return service.resources(facilityCode);
    }

    /**
     * Add one weekly availability window.
     *
     * @param code    tenant-local resource code
     * @param request window fields
     * @return HTTP 201 with the created window
     */
    @PostMapping("/resources/{code}/availability")
    public ResponseEntity<AvailabilityView> addAvailability(@PathVariable String code,
                                                            @RequestBody AvailabilityRequest request) {
        return ResponseEntity.status(201).body(service.addAvailability(code, request.weekday(),
                request.startLocal(), request.endLocal()));
    }

    /**
     * Visit type create request.
     *
     * @param code            tenant-local visit type code
     * @param durationMinutes appointment length in minutes
     * @return the visit type request
     */
    public record VisitTypeRequest(String code, Integer durationMinutes) {
    }

    /**
     * Resource create request.
     *
     * @param facilityCode tenant-local facility code
     * @param kind         {@code clinician} or {@code room}
     * @param code         tenant-local resource code
     * @param displayName  display name
     * @return the resource request
     */
    public record ResourceRequest(String facilityCode, String kind, String code, String displayName) {
    }

    /**
     * Weekly availability window request.
     *
     * @param weekday    ISO-8601 day of week, 1 = Monday
     * @param startLocal local window start
     * @param endLocal   local window end
     * @return the availability request
     */
    public record AvailabilityRequest(Integer weekday, String startLocal, String endLocal) {
    }
}
