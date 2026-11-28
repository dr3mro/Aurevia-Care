package com.aurevia.tenant.importing;

import java.util.Objects;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for controlled onboarding commits, sign-off and reversal.
 *
 * <p>All reconciliation, resumability and role rules live in
 * {@link com.aurevia.tenant.importing.ControlledImportService}; this
 * adapter carries the manifest, the resumable checkpoint and the sign-off
 * to that service and returns its result. It additionally binds the named
 * approver to the authenticated subject so one user cannot sign off as another.
 * It never mutates a store itself.</p>
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
     * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
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
     * <p>The named approver must be the authenticated subject; an absent
     * identity or a different subject is refused before the service is called.
     * This boundary binds the named approver to the authenticated subject; the
     * claimed role is checked against an allow-list, not against verified membership.</p>
     *
     * @param request manifest, checkpoint and named sign-off
     * @return acceptance or refusal with a reason
     * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
     */
    @PostMapping("/sign-off")
    public SignOffResult signOff(@RequestBody SignOffRequest request) {
        TenantContext context = TenantContext.current();
        if (request.signOff() != null
                && (context == null
                        || !Objects.equals(context.subject(), request.signOff().approver()))) {
            return new SignOffResult(false, "approver", request.progress());
        }
        return service.signOff(request.manifest(), request.progress(), request.signOff());
    }

    /**
     * Reverse a staging-only import to an empty checkpoint.
     *
     * @param request staging checkpoint to reverse
     * @return empty checkpoint for the same manifest version
     * @throws com.aurevia.api.ApiException when the route rejects the request for authorization, validation or state reasons
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
