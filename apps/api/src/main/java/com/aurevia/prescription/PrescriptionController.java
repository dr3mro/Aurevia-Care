package com.aurevia.prescription;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.prescription.PrescriptionContract.AllergyCheck;
import com.aurevia.prescription.PrescriptionContract.AllergyCheckRequest;
import com.aurevia.prescription.PrescriptionContract.CreateDraftRequest;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.prescription.PrescriptionContract.DraftView;
import com.aurevia.prescription.DraftCatalogVersion;
import com.aurevia.prescription.PrescriptionContract.SelectMedicationRequest;

/**
 * Thin HTTP adapter for RX-01 prescription drafts (ARC-03, ARC-04).
 *
 * <p>The medication-selection command requires an {@code If-Match} version.
 * Authorization, allergy matching and override provenance live in the domain
 * service; this class only parses the boundary and returns results.</p>
 */
@RestController
@RequestMapping("/api/v1/prescription-drafts")
public class PrescriptionController {

    private final PrescriptionService service;

    /**
     * Create the controller.
     *
     * @param service prescription domain service
     */
    public PrescriptionController(PrescriptionService service) {
        this.service = service;
    }

    /**
     * Create a draft.
     *
     * @param request creation command
     * @return HTTP 201 with the created draft
     */
    @PostMapping
    public ResponseEntity<Draft> create(@RequestBody CreateDraftRequest request) {
        Draft created = service.createDraft(request);
        return ResponseEntity.created(URI.create("/api/v1/prescription-drafts/" + created.id()))
                .body(created);
    }

    /**
     * Read a draft with its immutable override.
     *
     * @param id draft identifier
     * @return the draft view
     */
    @GetMapping("/{id}")
    public DraftView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Read the catalog version this draft was built from.
     *
     * @param id draft identifier
     * @return the immutable draft-to-catalog binding
     */
    @GetMapping("/{id}/catalog-version")
    public DraftCatalogVersion catalogVersion(@PathVariable String id) {
        return service.catalogVersion(id);
    }

    /**
     * Run an allergy check for a candidate catalog entry.
     *
     * @param id      draft identifier
     * @param request check command
     * @return the versioned check result
     */
    @PostMapping("/{id}/allergy-check")
    public AllergyCheck check(@PathVariable String id, @RequestBody AllergyCheckRequest request) {
        return service.checkAllergies(id, request);
    }

    /**
     * Select a medication with a version precondition.
     *
     * @param id      draft identifier
     * @param request selection command
     * @param ifMatch expected version
     * @return the updated draft
     */
    @PostMapping("/{id}/select-medication")
    public Draft select(@PathVariable String id, @RequestBody SelectMedicationRequest request,
                        @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.selectMedication(id, request, IfMatch.parse(ifMatch));
    }
}
