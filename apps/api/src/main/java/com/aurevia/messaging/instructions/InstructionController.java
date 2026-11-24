package com.aurevia.messaging.instructions;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;
import com.aurevia.messaging.instructions.InstructionContract.DefineTemplateRequest;
import com.aurevia.messaging.instructions.InstructionContract.ReplaceRequest;
import com.aurevia.messaging.instructions.InstructionContract.Revision;
import com.aurevia.messaging.instructions.InstructionContract.SendRequest;
import com.aurevia.messaging.instructions.InstructionContract.Template;
import com.aurevia.messaging.instructions.InstructionContract.View;
import com.aurevia.messaging.instructions.InstructionContract.WithdrawRequest;

/**
 * Thin HTTP adapter for MSG-04 typed patient instructions (ARC-03, ARC-04).
 *
 * <p>Irreversible revision changes require an {@code If-Match} version parsed
 * here; authorization, connection validation and the revision state machine
 * live in the domain service.</p>
 */
@RestController
@RequestMapping("/api/v1/patient-instructions")
public class InstructionController {

    private final InstructionService service;

    /**
     * Create the controller.
     *
     * @param service instruction domain service
     */
    public InstructionController(InstructionService service) {
        this.service = service;
    }

    /**
     * Approve a versioned unit template.
     *
     * @param request definition command
     * @return HTTP 201 with the approved template
     */
    @PostMapping("/templates")
    public ResponseEntity<Template> defineTemplate(@RequestBody DefineTemplateRequest request) {
        Template template = service.defineTemplate(request);
        return ResponseEntity.created(URI.create("/api/v1/patient-instructions/templates/"
                + template.id())).body(template);
    }

    /**
     * Publish a new instruction thread.
     *
     * @param request send command
     * @return HTTP 201 with the published revision
     */
    @PostMapping
    public ResponseEntity<Revision> send(@RequestBody SendRequest request) {
        Revision revision = service.send(request);
        return ResponseEntity.created(URI.create("/api/v1/patient-instructions/"
                + revision.threadId())).body(revision);
    }

    /**
     * Publish a linked replacement revision.
     *
     * @param threadId    owning thread
     * @param request     replacement command
     * @param ifMatch     expected version
     * @return the new current revision
     */
    @PostMapping("/{threadId}/replace")
    public Revision replace(@PathVariable String threadId, @RequestBody ReplaceRequest request,
                            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.replace(threadId, request, IfMatch.parse(ifMatch));
    }

    /**
     * Withdraw the current revision.
     *
     * @param threadId owning thread
     * @param request  withdrawal command
     * @param ifMatch  expected version
     * @return the withdrawn revision
     */
    @PostMapping("/{threadId}/withdraw")
    public Revision withdraw(@PathVariable String threadId, @RequestBody WithdrawRequest request,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.withdraw(threadId, request, IfMatch.parse(ifMatch));
    }

    /**
     * Read one thread with its history and facts.
     *
     * @param threadId owning thread
     * @return the read model
     */
    @GetMapping("/{threadId}")
    public View get(@PathVariable String threadId) {
        return service.get(threadId);
    }

    /**
     * List current instructions visible to a patient.
     *
     * @param patientId verified patient identifier
     * @return current revisions
     */
    @GetMapping
    public List<Revision> listForPatient(@RequestParam String patientId) {
        return service.listForPatient(patientId);
    }
}
