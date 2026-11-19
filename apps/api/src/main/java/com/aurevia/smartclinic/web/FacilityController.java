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

/** Thin HTTP adapter; authorization lives in the domain service. */
@RestController
@RequestMapping("/api/v1/facilities")
public class FacilityController {

    private final FacilityService service;

    public FacilityController(FacilityService service) {
        this.service = service;
    }

    @GetMapping("/{code}")
    public Facility get(@PathVariable String code) {
        return service.get(code);
    }

    @PostMapping
    public ResponseEntity<Facility> create(@RequestBody CreateRequest request) {
        Facility created = service.create(request.code(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/facilities/" + created.code())).body(created);
    }

    /** Request body for facility creation; no organization field is accepted. */
    public record CreateRequest(String code, String name) {
    }
}
