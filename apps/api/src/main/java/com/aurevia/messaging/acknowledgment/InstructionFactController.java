package com.aurevia.messaging.acknowledgment;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.messaging.acknowledgment.InstructionFacts.AcknowledgeCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Acknowledgment;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Dispatch;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FirstView;
import com.aurevia.messaging.acknowledgment.InstructionFacts.FollowUp;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Publication;
import com.aurevia.messaging.acknowledgment.InstructionFacts.PublishCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ResolveCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.RetryCommand;
import com.aurevia.messaging.acknowledgment.InstructionFacts.Timeline;
import com.aurevia.messaging.acknowledgment.InstructionFacts.ViewCommand;

/**
 * Thin HTTP adapter for instruction facts. Every authorization, fact-separation and
 * revision-binding decision lives in
 * {@link com.aurevia.messaging.acknowledgment.InstructionFactService}; this
 * adapter only binds command fields and returns domain values.
 */
@RestController
@RequestMapping("/api/v1")
public class InstructionFactController {

    private final InstructionFactService service;

    /**
     * Create the controller.
     *
     * @param service instruction-fact domain service
     */
    public InstructionFactController(InstructionFactService service) {
        this.service = service;
    }

    /**
     * Publish an instruction revision.
     *
     * @param command publish command
     * @return the recorded publication fact
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @PostMapping("/instructions/publish")
    public Publication publish(@RequestBody PublishCommand command) {
        return service.publish(command);
    }

    /**
     * Retry a generic notification dispatch.
     *
     * @param command retry command
     * @return the recorded dispatch fact
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @PostMapping("/instructions/dispatch/retry")
    public Dispatch retry(@RequestBody RetryCommand command) {
        return service.retryDispatch(command);
    }

    /**
     * Record the first in-app view of a revision.
     *
     * @param command view command
     * @return the recorded first-view fact
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @PostMapping("/instructions/first-view")
    public FirstView firstView(@RequestBody ViewCommand command) {
        return service.markFirstView(command);
    }

    /**
     * Acknowledge receipt of one exact revision.
     *
     * @param command acknowledgment command
     * @return the recorded receipt-only acknowledgment
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @PostMapping("/instructions/acknowledge")
    public Acknowledgment acknowledge(@RequestBody AcknowledgeCommand command) {
        return service.acknowledge(command);
    }

    /**
     * Read the fact timeline of one instruction.
     *
     * @param instructionId instruction identifier
     * @return immutable ordered facts
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @GetMapping("/instructions/{id}/facts")
    public Timeline facts(@PathVariable("id") String instructionId) {
        return service.facts(instructionId);
    }

    /**
     * Read overdue follow-ups routed to a unit.
     *
     * @param unitId owning unit identifier
     * @return open, unacknowledged follow-ups
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @GetMapping("/instructions/overdue-follow-ups")
    public List<FollowUp> overdue(@RequestParam String unitId) {
        return service.overdueFollowUps(unitId);
    }

    /**
     * Resolve an overdue follow-up with a reason.
     *
     * @param command resolve command
     * @return the resolved follow-up
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @PostMapping("/instructions/follow-up/resolve")
    public FollowUp resolve(@RequestBody ResolveCommand command) {
        return service.resolveFollowUp(command);
    }
}
