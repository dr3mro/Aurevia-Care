package com.aurevia.smartclinic.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.smartclinic.tenancy.Facility;
import com.aurevia.smartclinic.tenancy.FacilityService;

/**
 * Thin HTTP adapter for facility administration.
 *
 * <p>All authorization decisions live in {@link FacilityService}; this class
 * maps outcomes to status codes and headers only.</p>
 */
@RestController
@RequestMapping("/api/v1/facilities")
public class FacilityController {

    private final FacilityService service;

    /**
     * Create the controller.
     *
     * @param service facility domain service
     */
    public FacilityController(FacilityService service) {
        this.service = service;
    }

    /**
     * Read a facility by tenant-local code.
     *
     * @param code tenant-local facility code
     * @return the visible facility
     */
    @GetMapping("/{code}")
    public Facility get(@PathVariable String code) {
        return service.get(code);
    }

    /**
     * Create a facility in the active organization.
     *
     * @param request validated create request
     * @return HTTP 201 with a Location header and the created facility
     */
    @PostMapping
    public ResponseEntity<Facility> create(@RequestBody CreateRequest request) {
        Facility created = service.create(request.code(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/facilities/" + created.code())).body(created);
    }

    /**
     * Request body for facility creation; no organization field is accepted.
     *
     * @param code tenant-local facility code
     * @param name display name
     * @return the immutable create request
     */
    public record CreateRequest(String code, String name) {
    }
}
