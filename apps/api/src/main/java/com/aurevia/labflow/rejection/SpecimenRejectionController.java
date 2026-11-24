package com.aurevia.labflow.rejection;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.lab.accession.AccessionEvent;

/**
 * Thin HTTP adapter for specimen rejection, recollection and history (LAB-01, ARC-03).
 *
 * <p>Every invariant lives in the injected
 * {@link com.aurevia.labflow.rejection.SpecimenRejectionService}; this
 * adapter only binds commands and maps outcomes to response bodies. It never
 * builds its own collaborators.</p>
 */
@RestController
@RequestMapping("/api/v1/lab/specimens")
public class SpecimenRejectionController {

    private final SpecimenRejectionService service;

    /**
     * Create the controller.
     *
     * @param service specimen rejection domain service
     */
    public SpecimenRejectionController(SpecimenRejectionService service) {
        this.service = service;
    }

    /**
     * Reject a specimen, retaining the reason and notifying the clinician.
     *
     * @param id      opaque specimen identifier
     * @param request bounded rejection command
     * @return the rejection outcome
     */
    @PostMapping("/{id}/reject")
    public RejectionOutcome reject(@PathVariable String id, @RequestBody RejectSpecimenRequest request) {
        return service.reject(id, request);
    }

    /**
     * Create a linked recollection of a rejected specimen on the same order.
     *
     * @param id      rejected specimen identifier
     * @param request bounded recollection command
     * @return the new recollection specimen
     */
    @PostMapping("/{id}/recollect")
    public RecollectionOutcome recollect(@PathVariable String id, @RequestBody RecollectSpecimenRequest request) {
        return service.recollect(id, request);
    }

    /**
     * Return the immutable history of a specimen.
     *
     * @param id opaque specimen identifier
     * @return the events in occurrence order
     */
    @GetMapping("/{id}/history")
    public List<AccessionEvent> history(@PathVariable String id) {
        return service.history(id);
    }
}
