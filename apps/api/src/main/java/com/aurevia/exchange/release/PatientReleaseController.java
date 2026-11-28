package com.aurevia.exchange.release;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;

/**
 * Thin HTTP adapter for patient release decisions.
 *
 * <p>Authorization, state transitions, visibility and audit live in
 * {@link com.aurevia.exchange.release.ReleaseService}. This adapter only parses the version precondition and
 * maps outcomes to status codes; it never accepts an actor or organization.</p>
 */
@RestController
@RequestMapping("/api/v1/releases")
public class PatientReleaseController {

    private final ReleaseService service;

    /**
     * Create the controller.
     *
     * @param service release domain service
     */
    public PatientReleaseController(ReleaseService service) {
        this.service = service;
    }

    /**
     * Authorize a release of an immutable report revision.
     *
     * @param command validated authorization command
     * @return HTTP 201 with the current visibility decision
     * @throws com.aurevia.api.ApiException on invalid input, denial or a duplicate authorization
     */
    @PostMapping
    public ResponseEntity<PatientRelease> authorize(@RequestBody AuthorizeRelease command) {
        PatientRelease created = service.authorize(command);
        return ResponseEntity.created(URI.create("/api/v1/releases/" + created.id())).body(created);
    }

    /**
     * Withdraw a release; future access is denied while history is retained.
     *
     * @param id      tenant-local release identifier
     * @param ifMatch version precondition
     * @param request withdrawal reason
     * @return the withdrawn release
     * @throws com.aurevia.api.ApiException on invalid input, denial, concealment or a stale version
     */
    @PostMapping("/{id}/withdraw")
    public PatientRelease withdraw(@PathVariable String id,
                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                   @RequestBody WithdrawRequest request) {
        return service.withdraw(id, IfMatch.parse(ifMatch), request.reason());
    }

    /**
     * Correct a release by superseding it with a new immutable revision.
     *
     * @param id      tenant-local release identifier being corrected
     * @param ifMatch version precondition
     * @param request replacement revision and reason
     * @return the new release linked to the corrected one
     * @throws com.aurevia.api.ApiException on invalid input, denial, concealment or a stale version
     */
    @PostMapping("/{id}/correct")
    public PatientRelease correct(@PathVariable String id,
                                  @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                  @RequestBody CorrectRequest request) {
        return service.correct(id, IfMatch.parse(ifMatch), request.correctedRevisionId(),
                request.correctedRevisionVersion(), request.reason());
    }

    /**
     * Lift a hold after an authorized review.
     *
     * @param id      tenant-local release identifier
     * @param ifMatch version precondition
     * @param request review note
     * @return the now-visible release
     * @throws com.aurevia.api.ApiException on invalid input, denial, concealment or a stale version
     */
    @PostMapping("/{id}/lift-hold")
    public PatientRelease liftHold(@PathVariable String id,
                                   @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                   @RequestBody ReviewRequest request) {
        return service.liftHold(id, IfMatch.parse(ifMatch), request.note());
    }

    /**
     * Read one release with its current visibility decision.
     *
     * @param id tenant-local release identifier
     * @return the release projection
     * @throws com.aurevia.api.ApiException on invalid input, denial or concealment
     */
    @GetMapping("/{id}")
    public PatientRelease get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Read the append-only decision history.
     *
     * @param id tenant-local release identifier
     * @return decision events in commit order
     * @throws com.aurevia.api.ApiException on invalid input, denial or concealment
     */
    @GetMapping("/{id}/history")
    public List<ReleaseEvent> history(@PathVariable String id) {
        return service.history(id);
    }

    /**
     * Withdrawal command body.
     *
     * @param reason non-sensitive reason for retraction
     * @return the immutable request
     */
    public record WithdrawRequest(String reason) {
    }

    /**
     * Correction command body.
     *
     * @param correctedRevisionId      replacement immutable revision identifier
     * @param correctedRevisionVersion version of the replacement revision
     * @param reason                   non-sensitive correction reason
     * @return the immutable request
     */
    public record CorrectRequest(String correctedRevisionId, int correctedRevisionVersion, String reason) {
    }

    /**
     * Hold-review command body.
     *
     * @param note non-sensitive review note
     * @return the immutable request
     */
    public record ReviewRequest(String note) {
    }
}
