package com.aurevia.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.catalog.CatalogEntry;
import com.aurevia.catalog.CatalogService;
import com.aurevia.configuration.ConfigEntry;

/**
 * Thin HTTP adapter for tenant catalog administration.
 *
 * <p>Drafts and approvals are appended by the configuration domain through the
 * catalog service; this adapter only maps outcomes to status codes.</p>
 */
@RestController
@RequestMapping("/api/v1/catalogs")
public class CatalogController {

    private final CatalogService service;

    /**
     * Create the controller.
     *
     * @param service catalog domain service
     */
    public CatalogController(CatalogService service) {
        this.service = service;
    }

    /**
     * Define a pending catalog entry version.
     *
     * @param family  catalog family
     * @param request entry value and optional effective date
     * @return HTTP 201 with a Location header and the created draft
     */
    @PostMapping("/{family}/entries")
    public ResponseEntity<ConfigEntry> define(@PathVariable String family, @RequestBody DefineRequest request) {
        ConfigEntry created = service.define(family, request.code(), request.value(), request.effectiveFrom());
        return ResponseEntity.created(
                URI.create("/api/v1/catalogs/" + family + "/entries/" + request.code())).body(created);
    }

    /**
     * Append a named approval for a catalog entry version.
     *
     * @param family  catalog family
     * @param code    entry code
     * @param version version to approve
     * @param request optional approval note
     * @return the approved entry
     */
    @PostMapping("/{family}/entries/{code}/versions/{version}/approve")
    public ConfigEntry approve(@PathVariable String family, @PathVariable String code,
                               @PathVariable int version, @RequestBody ApproveRequest request) {
        return service.approve(family, code, version, request.note());
    }

    /**
     * Read the effective version of one entry.
     *
     * @param family catalog family
     * @param code   entry code
     * @return the effective entry
     */
    @GetMapping("/{family}/entries/{code}")
    public CatalogEntry effective(@PathVariable String family, @PathVariable String code) {
        return service.effective(family, code);
    }

    /**
     * List all effective entries in a family.
     *
     * @param family catalog family
     * @return the effective family
     */
    @GetMapping("/{family}")
    public List<CatalogEntry> family(@PathVariable String family) {
        return service.family(family);
    }

    /**
     * Catalog draft request; the key is derived from the path, never from the body.
     *
     * @param code          tenant-local entry code
     * @param value         entry value
     * @param effectiveFrom UTC instant, or {@code null} for immediate effect on approval
     * @return the immutable request
     */
    public record DefineRequest(String code, String value, String effectiveFrom) {
    }

    /**
     * Approval request; the approver identity is derived from the actor.
     *
     * @param note optional approval note
     * @return the immutable request
     */
    public record ApproveRequest(String note) {
    }
}
