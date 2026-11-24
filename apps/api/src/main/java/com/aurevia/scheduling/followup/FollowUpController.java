package com.aurevia.scheduling.followup;

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
 * Thin HTTP adapter for CLN-06 follow-up commands.
 *
 * <p>Every invariant, authorization decision and audit event lives in
 * {@link com.aurevia.scheduling.followup.FollowUpService} behind the
 * transactional command boundary. Organization, actor and rule are never accepted
 * from the request; the shared {@code IfMatch} parser gives confirmation the same
 * version-precondition semantics as every other irreversible command.</p>
 */
@RestController
@RequestMapping("/api/v1/follow-ups")
public class FollowUpController {

    private final FollowUpCommandService commands;

    /**
     * Create the controller.
     *
     * @param commands transactional follow-up command boundary
     */
    public FollowUpController(FollowUpCommandService commands) {
        this.commands = commands;
    }

    /**
     * Suggest a follow-up date and explain the rule used.
     *
     * @param request bounded suggestion fields
     * @return the stored suggestion
     */
    @PostMapping("/suggest")
    public FollowUpSuggestion suggest(@RequestBody SuggestRequest request) {
        return commands.suggest(request.patientId(), request.facilityCode(), request.visitType(),
                request.encounterAt());
    }

    /**
     * Override a suggestion with a recorded reason and actor.
     *
     * @param id      suggestion identifier
     * @param request replacement date and reason
     * @return the new suggestion version
     */
    @PostMapping("/{id}/override")
    public FollowUpSuggestion override(@PathVariable String id, @RequestBody OverrideRequest request) {
        return commands.override(id, request.requestedDate(), request.reason());
    }

    /**
     * Confirm a suggestion and create exactly one booking.
     *
     * @param id      suggestion identifier
     * @param ifMatch version read by the caller
     * @return the confirmed suggestion with its booking identifier
     */
    @PostMapping("/{id}/confirm")
    public FollowUpSuggestion confirm(@PathVariable String id,
                                      @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return commands.confirm(id, IfMatch.parse(ifMatch));
    }

    /**
     * Open a care-team action task for a suggestion.
     *
     * @param id      suggestion identifier
     * @param request owner, due date and instruction
     * @return the open task
     */
    @PostMapping("/{id}/tasks")
    public CareTeamTask createTask(@PathVariable String id, @RequestBody TaskRequest request) {
        return commands.createTask(id, request.owner(), request.dueDate(), request.instruction());
    }

    /**
     * Record the outcome of an open care-team task.
     *
     * @param taskId  task identifier
     * @param request recorded outcome
     * @return the completed task
     */
    @PostMapping("/tasks/{taskId}/complete")
    public CareTeamTask completeTask(@PathVariable String taskId, @RequestBody CompleteRequest request) {
        return commands.completeTask(taskId, request.outcome());
    }

    /**
     * List the active tenant's tasks for a suggestion.
     *
     * @param id suggestion identifier
     * @return the visible tasks
     */
    @GetMapping("/{id}/tasks")
    public List<CareTeamTask> tasks(@PathVariable String id) {
        return commands.tasks(id);
    }

    /**
     * Suggestion request. The organization, actor and rule are server-owned.
     *
     * @param patientId    tenant-local patient identifier
     * @param facilityCode tenant-local facility code
     * @param visitType    visit type code
     * @param encounterAt  UTC instant of the encounter
     * @return the immutable request
     */
    public record SuggestRequest(String patientId, String facilityCode, String visitType, String encounterAt) {
    }

    /**
     * Override request.
     *
     * @param requestedDate replacement facility-local civil date
     * @param reason        required override reason
     * @return the immutable request
     */
    public record OverrideRequest(String requestedDate, String reason) {
    }

    /**
     * Care-team task request.
     *
     * @param owner       accountable owner subject
     * @param dueDate     facility-local civil due date
     * @param instruction action for the owner
     * @return the immutable request
     */
    public record TaskRequest(String owner, String dueDate, String instruction) {
    }

    /**
     * Task outcome request.
     *
     * @param outcome recorded outcome
     * @return the immutable request
     */
    public record CompleteRequest(String outcome) {
    }
}
