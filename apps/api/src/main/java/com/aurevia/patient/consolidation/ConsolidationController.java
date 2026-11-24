package com.aurevia.patient.consolidation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.patient.consolidation.DuplicateRepository.DuplicatePair;

/**
 * Thin HTTP adapter for same-tenant duplicate review and consolidation (IAM-04).
 *
 * <p>Authorization, locking and history invariants live in
 * {@link com.aurevia.patient.consolidation.ConsolidationService}; this adapter only binds inputs and maps outcomes.</p>
 */
@RestController
@RequestMapping("/api/v1/duplicate-reviews")
public class ConsolidationController {

    private final ConsolidationService service;

    /**
     * Create the controller.
     *
     * @param service consolidation domain service
     */
    public ConsolidationController(ConsolidationService service) {
        this.service = service;
    }

    /**
     * List the review worklist.
     *
     * @return same-tenant duplicate pairs
     */
    @GetMapping
    public List<DuplicatePair> worklist() {
        return service.worklist();
    }

    /**
     * Show both identities, conflicts, dependencies and a dry run.
     *
     * @param survivorId  surviving patient identifier
     * @param duplicateId duplicate patient identifier
     * @return non-mutating review detail
     */
    @GetMapping("/{survivorId}/{duplicateId}")
    public ReviewDetail review(@PathVariable String survivorId, @PathVariable String duplicateId) {
        return service.review(survivorId, duplicateId);
    }

    /**
     * Open a review ticket owned by the authenticated reviewer.
     *
     * @param request the two patient identifiers
     * @return HTTP 201 with the open ticket
     */
    @PostMapping
    public ResponseEntity<DuplicateReview> open(@RequestBody OpenRequest request) {
        DuplicateReview review = service.open(request.survivorId(), request.duplicateId());
        return ResponseEntity.status(HttpStatus.CREATED).body(review);
    }

    /**
     * Approve consolidation as a second reviewer using a version precondition.
     *
     * @param reviewId review identifier
     * @param ifMatch  version precondition header
     * @param request  recorded reason
     * @return the appended consolidation event
     */
    @PostMapping("/{reviewId}/consolidate")
    public Consolidation consolidate(@PathVariable String reviewId,
                                     @RequestHeader("If-Match") String ifMatch,
                                     @RequestBody ConsolidationApprovalRequest request) {
        return service.consolidate(reviewId, IfMatch.parse(ifMatch), request.reason(),
                request.confirmedIdentifiers(), request.alternateProcess());
    }

    /**
     * Correct an erroneous consolidation through the audited support workflow.
     *
     * @param reviewId review identifier
     * @param ifMatch  current consolidation version precondition
     * @param request  recorded correction reason
     * @return the appended correction event
     */
    @PostMapping("/{reviewId}/correct")
    public Consolidation correct(@PathVariable String reviewId,
                                 @RequestHeader("If-Match") String ifMatch,
                                 @RequestBody ReasonRequest request) {
        return service.correct(reviewId, IfMatch.parse(ifMatch), request.reason());
    }

    /**
     * Open-review request; organization and actor are never accepted from the client.
     *
     * @param survivorId  surviving patient identifier
     * @param duplicateId duplicate patient identifier
     * @return the immutable request
     */
    public record OpenRequest(String survivorId, String duplicateId) {
    }

    /**
     * Reason request shared by consolidation and correction commands.
     *
     * @param reason recorded business reason
     * @return the immutable request
     */
    public record ReasonRequest(String reason) {
    }

    /**
     * Consolidation approval carrying the IAM-04 identifier confirmation.
     *
     * <p>The two issuer-scoped identifiers must be confirmed to belong to one
     * person before charts are merged; when they cannot be confirmed, the
     * caller must name a documented approved alternate process. Organization
     * and actor are never accepted from the client.</p>
     *
     * @param reason               recorded business reason
     * @param confirmedIdentifiers issuer-scoped identifiers confirmed as one person
     * @param alternateProcess     documented approved process when identifiers are unavailable
     * @return the immutable request
     */
    public record ConsolidationApprovalRequest(String reason, List<String> confirmedIdentifiers,
                                               String alternateProcess) {
        /**
         * Freeze the confirmation list on construction; an absent list is empty.
         */
        public ConsolidationApprovalRequest {
            confirmedIdentifiers = confirmedIdentifiers == null
                    ? List.of() : List.copyOf(confirmedIdentifiers);
        }

        /**
         * Return the immutable confirmation list.
         *
         * @return confirmed issuer-scoped identifiers
         */
        @Override
        public List<String> confirmedIdentifiers() {
            return List.copyOf(confirmedIdentifiers);
        }
    }
}
