package com.aurevia.prescription;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.catalog.VersionedCatalogSearch;
import com.aurevia.prescription.PrescriptionContract.CatalogHit;

/**
 * Thin HTTP adapter for the approved, effective drug catalog.
 *
 * <p>Reads are authorized in the domain service like every other protected
 * command; the search result carries suggestion-only alternatives and never
 * changes a clinician's selection.</p>
 */
@RestController
@RequestMapping("/api/v1/drug-catalog")
public class DrugCatalogController {

    private final PrescriptionService service;

    /**
     * Create the controller.
     *
     * @param service prescription domain service
     */
    public DrugCatalogController(PrescriptionService service) {
        this.service = service;
    }

    /**
     * Search approved effective catalog entries by name and formulation.
     *
     * @param query       generic or trade name fragment
     * @param formulation exact formulation filter
     * @return effective entries with tagged alternatives
     */
    @GetMapping("/search")
    public List<CatalogHit> search(@RequestParam(required = false) String query,
                                   @RequestParam(required = false) String formulation) {
        return service.searchCatalog(query, formulation);
    }

    /**
     * Search approved effective catalog entries and report the version used.
     *
     * @param query       generic or trade name fragment
     * @param formulation exact formulation filter
     * @return effective route-bearing items tagged with the catalog version
     */
    @GetMapping("/search-with-version")
    public VersionedCatalogSearch searchWithVersion(@RequestParam(required = false) String query,
                                                    @RequestParam(required = false) String formulation) {
        return service.searchCatalogWithVersion(query, formulation);
    }
}
