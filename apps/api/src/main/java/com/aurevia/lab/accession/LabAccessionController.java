package com.aurevia.lab.accession;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.lab.accession.AccessionCommands.AccessionRequest;
import com.aurevia.lab.accession.AccessionCommands.CollectRequest;
import com.aurevia.lab.accession.AccessionCommands.ReceiveRequest;
import com.aurevia.lab.accession.AccessionCommands.RejectRequest;
import com.aurevia.lab.accession.AccessionCommands.RecollectRequest;

/**
 * Thin HTTP adapter for laboratory accession commands (LAB-01, ARC-03).
 *
 * <p>Every invariant lives in {@code LabAccessionService}; this adapter only
 * binds the command and maps the outcome to a status code.</p>
 */
@RestController
@RequestMapping("/api/v1/lab/accessions")
public class LabAccessionController {

    private final LabAccessionService service;

    /**
     * Create the controller.
     *
     * @param service laboratory accession domain service
     */
    public LabAccessionController(LabAccessionService service) {
        this.service = service;
    }

    /**
     * Accession a requisition.
     *
     * @param request accession command
     * @return HTTP 201 with the created accession
     */
    @PostMapping
    public ResponseEntity<LabAccession> accession(@RequestBody AccessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.accession(request));
    }

    /**
     * List one cursor-paginated page of the active tenant's accessions.
     *
     * @param cursor opaque {@code <createdAt>:<id>} of the previous page's last row
     * @param limit  page size, or {@code null} for the default of 50
     * @param state  optional exact accession lifecycle state filter
     * @return HTTP 200 with one page of accessions
     */
    @GetMapping
    public AccessionPage list(@RequestParam(required = false) String cursor,
                              @RequestParam(required = false) String limit,
                              @RequestParam(required = false) String state) {
        return service.list(cursor, limit, state);
    }

    /**
     * Read one accession with its history.
     *
     * @param id specimen identifier
     * @return the accession view
     */
    @GetMapping("/{id}")
    public AccessionView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Record collection.
     *
     * @param id      specimen identifier
     * @param request collection command
     * @return the collected specimen
     */
    @PostMapping("/{id}/collect")
    public ResponseEntity<LabAccession> collect(@PathVariable String id,
                                                @RequestBody CollectRequest request) {
        return ResponseEntity.ok(service.collect(id, request));
    }

    /**
     * Record receipt and quality check.
     *
     * @param id      specimen identifier
     * @param request receipt command
     * @return the received specimen
     */
    @PostMapping("/{id}/receive")
    public ResponseEntity<LabAccession> receive(@PathVariable String id,
                                                @RequestBody ReceiveRequest request) {
        return ResponseEntity.ok(service.receive(id, request));
    }

    /**
     * Reject a specimen.
     *
     * @param id      specimen identifier
     * @param request rejection command
     * @return the rejected specimen
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<LabAccession> reject(@PathVariable String id,
                                               @RequestBody RejectRequest request) {
        return ResponseEntity.ok(service.reject(id, request));
    }

    /**
     * Create a linked recollection.
     *
     * @param id      rejected specimen identifier
     * @param request recollection command
     * @return HTTP 201 with the new linked accession
     */
    @PostMapping("/{id}/recollect")
    public ResponseEntity<LabAccession> recollect(@PathVariable String id,
                                                  @RequestBody RecollectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.recollect(id, request));
    }
}
