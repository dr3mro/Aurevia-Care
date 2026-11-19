package com.aurevia.smartclinic.g2.s_g2_06;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Patient-scoped read of currently released report revisions (PAT-02, XCH-06).
 *
 * <p>This endpoint lives under the {@code patients} resource family rather than
 * {@code releases}, because a patient queries the revisions they may access. All
 * authorization, tenant scoping and visibility folding happen in
 * {@link com.aurevia.smartclinic.g2.s_g2_06.ReleaseService}; this adapter only maps the
 * path and delegates.</p>
 */
@RestController
public class PatientReleasedRevisionsController {

    private final ReleaseService service;

    /**
     * Create the adapter.
     *
     * @param service release domain service
     */
    public PatientReleasedRevisionsController(ReleaseService service) {
        this.service = service;
    }

    /**
     * List the immutable revision identifiers a patient may currently access.
     *
     * @param patientId tenant-local patient identifier
     * @return currently visible revision identifiers in no defined order
     */
    @GetMapping("/api/v1/patients/{patientId}/released-revisions")
    public List<String> visibleRevisions(@PathVariable String patientId) {
        return service.visibleRevisions(patientId);
    }
}
