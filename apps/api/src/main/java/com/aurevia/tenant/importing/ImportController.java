package com.aurevia.tenant.importing;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for the onboarding dry run.
 *
 * <p>All validation, mapping and provenance rules live in
 * {@link com.aurevia.tenant.importing.OnboardingImportService}; this
 * adapter only receives the versioned manifest and returns its deterministic
 * report. No commit or chart mutation is exposed here.</p>
 */
@RestController
@RequestMapping("/api/v1/tenant/imports")
public class ImportController {

    private final OnboardingImportService service;

    /**
     * Create the controller.
     *
     * @param service onboarding import domain service
     */
    public ImportController(OnboardingImportService service) {
        this.service = service;
    }

    /**
     * Evaluate a manifest and return its dry-run report without writing anything.
     *
     * @param manifest versioned onboarding manifest
     * @return deterministic dry-run report
          *  * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/dry-run")
    public DryRunReport dryRun(@RequestBody ImportManifest manifest) {
        return service.dryRun(manifest);
    }
}
