package com.aurevia.smartclinic.tenant.importing;

import java.util.Objects;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP adapter for controlled onboarding commits, sign-off and reversal (OPS-08).
 *
 * <p>All reconciliation, resumability and authorization rules live in
 * {@link com.aurevia.smartclinic.tenant.importing.ControlledImportService}; this
 * adapter only carries the manifest, the resumable checkpoint and the sign-off
 * to that service and returns its result. It never mutates a store itself.</p>
 */
@RestController
@RequestMapping("/api/v1/tenant/imports")
public class ControlledImportController {

    private final ControlledImportService service;

    /**
     * Create the controller.
     *
     * @param service controlled onboarding import service
     */
    public ControlledImportController(ControlledImportService service) {
        this.service = service;
    }

    /**
     * Apply batches from the submitted checkpoint and return the updated checkpoint.
     *
     * @param request manifest and optional resumable checkpoint
     * @return per-batch outcomes and the new checkpoint
     */
    @PostMapping("/commit")
    public CommitReport commit(@RequestBody CommitRequest request) {
        ImportProgress progress = request.progress() == null
                ? ImportProgress.empty(
                        Objects.requireNonNull(request.manifest().manifestVersion(), "manifest version"))
                : request.progress();
        return service.commitBatches(request.manifest(), progress);
    }

    /**
     * Attempt an authorized sign-off over the submitted checkpoint.
     *
     * @param request manifest, checkpoint and named sign-off
     * @return acceptance or refusal with a reason
     */
    @PostMapping("/sign-off")
    public SignOffResult signOff(@RequestBody SignOffRequest request) {
        return service.signOff(request.manifest(), request.progress(), request.signOff());
    }

    /**
     * Reverse a staging-only import to an empty checkpoint.
     *
     * @param request staging checkpoint to reverse
     * @return empty checkpoint for the same manifest version
     */
    @PostMapping("/reverse")
    public ImportProgress reverse(@RequestBody ReverseRequest request) {
        return service.reverse(request.progress());
    }
}

/** Commit request carrying the manifest and an optional resumable checkpoint. */
record CommitRequest(ImportManifest manifest, ImportProgress progress) {
}

/** Sign-off request carrying the manifest, checkpoint and named authorization. */
record SignOffRequest(ImportManifest manifest, ImportProgress progress, ImportSignOff signOff) {
}

/** Reversal request carrying the staging checkpoint to clear. */
record ReverseRequest(ImportProgress progress) {
}
