package com.aurevia.clinical.notes;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.IfMatch;

/**
 * Thin HTTP adapter for the CLN-05 revision commands.
 *
 * <p>The controller binds inputs and maps outcomes; all clinical invariants and
 * authorization live in {@code ClinicalNoteService}. Version preconditions pass
 * through the shared {@code IfMatch} parser so every irreversible command has
 * identical 428/422/412 semantics.</p>
 */
@RestController
@RequestMapping("/api/v1/clinical-notes")
public class ClinicalNoteController {

    private final ClinicalNoteService service;

    /**
     * Create the controller.
     *
     * @param service clinical note domain service
     */
    public ClinicalNoteController(ClinicalNoteService service) {
        this.service = service;
    }

    /**
     * Create the first draft of a note.
     *
     * @param request draft fields
     * @return the persisted draft
     */
    @PostMapping
    public ClinicalNoteRevision create(@RequestBody CreateRequest request) {
        return service.createDraft(request.patientId(), request.encounterId(), request.title(),
                request.content(), request.language(), request.templateCode(),
                request.templateVersion(), request.clinicalAuthorSubject(), request.effectiveAt());
    }

    /**
     * Append a new editable draft version.
     *
     * @param id       note identifier
     * @param ifMatch  version precondition
     * @param request  replacement draft fields
     * @return the new draft revision
     */
    @PutMapping("/{id}")
    public ClinicalNoteRevision edit(@PathVariable String id,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                             @RequestBody EditRequest request) {
        return service.updateDraft(id, IfMatch.parse(ifMatch), request.title(), request.content());
    }

    /**
     * Sign an original draft.
     *
     * @param id       note identifier
     * @param ifMatch  version precondition
     * @param request  attestation
     * @return the immutable signed revision
     */
    @PostMapping("/{id}/sign")
    public ClinicalNoteRevision sign(@PathVariable String id,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch,
                             @RequestBody SignRequest request) {
        return service.sign(id, IfMatch.parse(ifMatch), request.attestation());
    }

    /**
     * Sign an amendment draft as an addendum.
     *
     * @param id       note identifier
     * @param ifMatch  version precondition
     * @param request  attestation
     * @return the immutable addendum revision
     */
    @PostMapping("/{id}/sign-addendum")
    public ClinicalNoteRevision signAddendum(@PathVariable String id,
                                     @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                     @RequestBody SignRequest request) {
        return service.signAddendum(id, IfMatch.parse(ifMatch), request.attestation());
    }

    /**
     * Open a separate amendment draft from a signed revision.
     *
     * @param id      note identifier
     * @param ifMatch version precondition
     * @return the amendment draft
     */
    @PostMapping("/{id}/amend")
    public ClinicalNoteRevision amend(@PathVariable String id,
                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return service.createAmendmentDraft(id, IfMatch.parse(ifMatch));
    }

    /**
     * Record entered-in-error against a signed revision.
     *
     * @param id       note identifier
     * @param ifMatch  version precondition
     * @param request  reason and affected recipients
     * @return the entered-in-error revision
     */
    @PostMapping("/{id}/entered-in-error")
    public ClinicalNoteRevision enteredInError(@PathVariable String id,
                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @RequestBody ErrorRequest request) {
        return service.markEnteredInError(id, IfMatch.parse(ifMatch), request.reason(),
                request.affectedRecipients());
    }

    /**
     * Read the current revision and its complete history.
     *
     * @param id note identifier
     * @return the note view
     */
    @GetMapping("/{id}")
    public NoteView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Draft creation request. Organization, author and version are never accepted from the client.
     *
     * @param patientId             patient whose chart owns the note
     * @param encounterId           optional encounter context
     * @param title                 note title
     * @param content               clinical content
     * @param language              content language
     * @param templateCode          template identifier
     * @param templateVersion       template version
     * @param clinicalAuthorSubject clinically responsible author
     * @param effectiveAt           optional clinical effective instant
     * @return the immutable request
     */
    public record CreateRequest(String patientId, String encounterId, String title, String content,
                                String language, String templateCode, Integer templateVersion,
                                String clinicalAuthorSubject, String effectiveAt) {
    }

    /**
     * Draft edit request.
     *
     * @param title   replacement title
     * @param content replacement content
     * @return the immutable request
     */
    public record EditRequest(String title, String content) {
    }

    /**
     * Signature request.
     *
     * @param attestation explicit clinical attestation
     * @return the immutable request
     */
    public record SignRequest(String attestation) {
    }

    /**
     * Entered-in-error request.
     *
     * @param reason              recorded reason
     * @param affectedRecipients  JSON array of affected recipient identifiers
     * @return the immutable request
     */
    public record ErrorRequest(String reason, String affectedRecipients) {
    }
}
