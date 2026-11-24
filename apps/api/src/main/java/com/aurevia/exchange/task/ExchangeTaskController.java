package com.aurevia.exchange.task;

import java.net.URI;

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
 * Thin HTTP adapter for XCH-02 commands (ARC-03, ARC-04).
 *
 * <p>Commands are explicit POST routes under one resource, each guarded by an
 * {@code If-Match} version and an allowlisted command name. Authorization,
 * transition legality, the human-acknowledgment binding and audit all live in
 * {@link com.aurevia.exchange.task.ExchangeTaskService}; this class
 * parses only the wire contract.</p>
 */
@RestController
@RequestMapping("/api/v1/exchange-tasks")
public class ExchangeTaskController {

    private final ExchangeTaskService service;

    /**
     * Create the controller.
     *
     * @param service exchange state service
     */
    public ExchangeTaskController(ExchangeTaskService service) {
        this.service = service;
    }

    /**
     * Open a work item.
     *
     * @param request create command
     * @return HTTP 201 with the created state and its location
     */
    @PostMapping
    public ResponseEntity<ExchangeTask> create(@RequestBody CreateExchangeRequest request) {
        ExchangeTask created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/exchange-tasks/" + created.id()))
                .body(created);
    }

    /**
     * Read one task and its history.
     *
     * @param id task identifier
     * @return state and append-only history
     */
    @GetMapping("/{id}")
    public ExchangeTaskView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Apply one explicit command to a single state channel.
     *
     * @param id      task identifier
     * @param command allowlisted command name
     * @param ifMatch version precondition, required for every transition
     * @param request optional body carrying a reason for reject and cancel
     * @return the next state
     */
    @PostMapping("/{id}/{command}")
    public ExchangeTask execute(@PathVariable String id, @PathVariable String command,
                                @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                @RequestBody(required = false) TransitionRequest request) {
        return service.execute(id, IfMatch.parse(ifMatch), ExchangeCommand.from(command),
                request == null ? null : request.reason());
    }
}
