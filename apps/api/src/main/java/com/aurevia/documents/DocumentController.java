package com.aurevia.documents;

import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.documents.DocumentModel.DocumentAssocView;
import com.aurevia.documents.DocumentModel.RenderedDocument;
import com.aurevia.documents.DocumentModel.LinkRequest;
import com.aurevia.documents.DocumentModel.RenderRequest;

import java.net.URI;
import java.util.List;

import org.springframework.http.MediaType;
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

/**
 * Thin HTTP adapter for document associations and renders (CLN-07, ARC-03).
 *
 * <p>The commands exposed here are {@code POST /api/v1/documents/associations}
 * to attach an original, {@code GET /api/v1/documents/associations/{id}} for its
 * history, {@code GET /api/v1/documents/associations/{id}/content} for a
 * checksum-verified original download, {@code POST /api/v1/documents/associations/{id}/void}
 * for a reasoned void guarded by {@code If-Match}, {@code POST /api/v1/documents/rendered} for
 * a versioned PDF and {@code GET /api/v1/documents/rendered/{id}[/content]} for
 * its metadata or immutable bytes. Tenant and author are never bound from the
 * request. Authorization, checksums, history and audit live in
 * {@code com.aurevia.documents.DocumentService}.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService service;

    /**
     * Create the controller.
     *
     * @param service document domain service
     */
    public DocumentController(DocumentService service) {
        this.service = service;
    }

    /**
     * Attach an original.
     *
     * @param request link command
     * @return HTTP 201 with the association
     */
    @PostMapping("/associations")
    public ResponseEntity<DocumentAssoc> link(@RequestBody LinkRequest request) {
        DocumentAssoc created = service.link(request);
        return ResponseEntity.created(
                URI.create("/api/v1/documents/associations/" + created.id())).body(created);
    }

    /**
     * Read an association and its history.
     *
     * @param id opaque association identifier
     * @return the association view
     */
    @GetMapping("/associations/{id}")
    public DocumentAssocView association(@PathVariable String id) {
        return service.association(id);
    }

    /**
     * Download the retained original bytes after checksum re-verification.
     *
     * @param id opaque association identifier
     * @return the verified original bytes
     */
    @GetMapping(value = "/associations/{id}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> associationContent(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(service.associationContent(id));
    }

    /**
     * Void an association with a reason and version precondition.
     *
     * @param id      opaque association identifier
     * @param request recorded reason
     * @param ifMatch expected version
     * @return the voided association
     */
    @PostMapping("/associations/{id}/void")
    public DocumentAssoc voidAssociation(@PathVariable String id, @RequestBody VoidRequest request,
                                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.voidAssociation(id, IfMatch.parse(ifMatch), request.reason());
    }

    /**
     * Render a localized, versioned PDF.
     *
     * @param request render command
     * @return HTTP 201 with the render metadata
     */
    @PostMapping("/rendered")
    public ResponseEntity<RenderedDocument> render(@RequestBody RenderRequest request) {
        RenderedDocument rendered = service.render(request);
        return ResponseEntity.created(
                URI.create("/api/v1/documents/rendered/" + rendered.id())).body(rendered);
    }

    /**
     * Read one rendered revision's metadata.
     *
     * @param id opaque render identifier
     * @return the render metadata
     */
    @GetMapping("/rendered/{id}")
    public RenderedDocument rendered(@PathVariable String id) {
        return service.rendered(id);
    }

    /**
     * Download one rendered revision's immutable bytes.
     *
     * @param id opaque render identifier
     * @return the PDF bytes
     */
    @GetMapping(value = "/rendered/{id}/content", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> renderedContent(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .body(service.renderedContent(id));
    }

    /**
     * List every rendered version of a subject and locale.
     *
     * @param subjectType chart, order or result
     * @param subjectId   opaque target identifier
     * @param locale      en or ar
     * @return render metadata in version order
     */
    @GetMapping("/rendered")
    public List<RenderedDocument> renderedVersions(@RequestParam String subjectType,
                                                   @RequestParam String subjectId,
                                                   @RequestParam String locale) {
        return service.renderedVersions(subjectType, subjectId, locale);
    }

    /**
     * Recorded reason for a void command.
     *
     * @param reason free-text reason required by CLN-07
     * @return the immutable request
     */
    public record VoidRequest(String reason) {
    }
}
