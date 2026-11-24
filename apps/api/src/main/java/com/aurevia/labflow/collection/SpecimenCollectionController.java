package com.aurevia.labflow.collection;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.lab.accession.LabAccession;

/**
 * Thin HTTP adapter for specimen collection and label printing (LAB-01, ARC-03).
 *
 * <p>Every invariant lives in the injected
 * {@link com.aurevia.labflow.collection.SpecimenCollectionService}; this
 * adapter only binds the command and maps the outcome to a response body.</p>
 */
@RestController
@RequestMapping("/api/v1/lab/specimens")
public class SpecimenCollectionController {

    private final SpecimenCollectionService service;

    /**
     * Create the controller.
     *
     * @param service specimen collection domain service
     */
    public SpecimenCollectionController(SpecimenCollectionService service) {
        this.service = service;
    }

    /**
     * Record a collection after matching the patient and accession.
     *
     * @param id      opaque specimen identifier
     * @param request bounded collection command
     * @return the collected specimen
     */
    @PostMapping("/{id}/collect")
    public LabAccession collect(@PathVariable String id, @RequestBody CollectRequest request) {
        return service.collect(id, request);
    }

    /**
     * Return print-ready label data for a collected specimen.
     *
     * @param id opaque specimen identifier
     * @return the accession, patient and collection label fields
     */
    @GetMapping("/{id}/label")
    public SpecimenLabelData label(@PathVariable String id) {
        return service.labelData(id);
    }
}
