package com.aurevia.labflow.release;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;

/**
 * Thin HTTP adapter for laboratory report release, correction and withdrawal (LAB-02, PAT-02).
 *
 * <p>All decisions, visibility rules, notifications and audit live in the injected
 * {@link com.aurevia.labflow.release.LabReportReleaseService}. This adapter only
 * parses the version precondition and maps commands; it never accepts an actor, tenant or
 * clinical content from the request path beyond the addressed identifiers.</p>
 */
@RestController
@RequestMapping("/api/v1/lab/releases")
public class LabReportReleaseController {

    private final LabReportReleaseService service;

    /**
     * Create the adapter.
     *
     * @param service release orchestration service
     */
    public LabReportReleaseController(LabReportReleaseService service) {
        this.service = service;
    }

    /**
     * Release an issued report revision to the clinician and, by policy, the patient.
     *
     * @param command release command
     * @return the current projection
     */
    @PostMapping
    public ReleasedReportView release(@RequestBody ReleaseReportCommand command) {
        return service.release(command);
    }

    /**
     * Supersede a released revision with a corrected one.
     *
     * @param id              release identifier
     * @param ifMatch         version precondition
     * @param request         replacement revision and reason
     * @return the replacement projection
     */
    @PostMapping("/{id}/correct")
    public ReleasedReportView correct(@PathVariable String id,
                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                      @RequestBody CorrectRequest request) {
        return service.correct(new CorrectReleaseCommand(id, IfMatch.parse(ifMatch),
                request.correctedRevisionId(), request.correctedRevisionVersion(), request.reason()));
    }

    /**
     * Withdraw a report as entered in error.
     *
     * @param id              release identifier
     * @param ifMatch         version precondition
     * @param request         withdrawal reason
     * @return the withdrawn projection
     */
    @PostMapping("/{id}/withdraw")
    public ReleasedReportView withdraw(@PathVariable String id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @RequestBody WithdrawRequest request) {
        return service.withdraw(new WithdrawReleaseCommand(id, IfMatch.parse(ifMatch), request.reason()));
    }

    /**
     * Read the current release with its notice and correction context.
     *
     * @param id release identifier
     * @return the projection
     */
    @GetMapping("/{id}")
    public ReleasedReportView read(@PathVariable String id) {
        return service.read(id);
    }

    /**
     * Read the complete history of the release.
     *
     * @param id release identifier
     * @return release decisions and viewer notifications
     */
    @GetMapping("/{id}/history")
    public List<ReleaseHistoryEntry> history(@PathVariable String id) {
        return service.history(id);
    }

    /**
     * Correction command body.
     *
     * @param correctedRevisionId       replacement immutable revision identifier
     * @param correctedRevisionVersion  version of the replacement revision
     * @param reason                    non-sensitive description of what changed and why
     * @return the immutable request
     */
    public record CorrectRequest(String correctedRevisionId, int correctedRevisionVersion, String reason) {
    }

    /**
     * Withdrawal command body.
     *
     * @param reason non-sensitive withdrawal reason
     * @return the immutable request
     */
    public record WithdrawRequest(String reason) {
    }
}
