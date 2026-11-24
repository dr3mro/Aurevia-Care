package com.aurevia.messaging.units;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.messaging.units.MessagingContract.ConnectRequest;
import com.aurevia.messaging.units.MessagingContract.ConnectionList;
import com.aurevia.messaging.units.MessagingContract.PatientUnitConnection;
import com.aurevia.messaging.units.MessagingContract.UnitDirectory;

/**
 * Thin HTTP adapter for the MSG-01 / ARC-07 care-unit directory.
 *
 * <p>All tenant derivation, verification and connection-scoping decisions live
 * in {@link com.aurevia.messaging.units.CareUnitDirectoryService}. This adapter only binds fields and maps
 * a created connection to its Location header.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class CareUnitController {

    private final CareUnitDirectoryService service;

    /**
     * Create the controller.
     *
     * @param service care-unit directory domain service
     */
    public CareUnitController(CareUnitDirectoryService service) {
        this.service = service;
    }

    /**
     * List the care units the authenticated patient is eligible to contact.
     *
     * @return verified, connection-eligible units
     */
    @GetMapping("/care-units")
    public UnitDirectory units() {
        return service.eligibleUnits();
    }

    /**
     * Connect a verified chart to an eligible recipient unit.
     *
     * @param request connection command
     * @return HTTP 201 with the created connection
     */
    @PostMapping("/patient-unit-connections")
    public ResponseEntity<PatientUnitConnection> connect(@RequestBody ConnectRequest request) {
        PatientUnitConnection created = service.connect(request);
        return ResponseEntity.created(
                URI.create("/api/v1/patient-unit-connections/" + created.id())).body(created);
    }

    /**
     * List the connections of one verified chart.
     *
     * @param patientId verified patient chart identifier
     * @return the chart's connections
     */
    @GetMapping("/patient-unit-connections")
    public ConnectionList connections(@RequestParam String patientId) {
        return service.listConnections(patientId);
    }

    /**
     * Revoke a connection so it grants no future access.
     *
     * @param id connection identifier
     * @return the revoked connection
     */
    @PostMapping("/patient-unit-connections/{id}/revoke")
    public PatientUnitConnection revoke(@PathVariable String id) {
        return service.revoke(id);
    }
}
