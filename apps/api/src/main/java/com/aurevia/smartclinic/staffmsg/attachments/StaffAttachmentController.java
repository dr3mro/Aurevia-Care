package com.aurevia.smartclinic.staffmsg.attachments;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.DownloadedFile;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.ScanRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.SendRequest;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffActor;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.StaffAttachment;
import com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentContract.UploadRequest;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Thin HTTP entry point for staff-message attachments (MSG-03, CLN-07).
 *
 * <p>Payload bytes are read only at this boundary and are never logged or
 * returned as metadata. The controller derives the checksum from the uploaded
 * bytes and lets {@link com.aurevia.smartclinic.staffmsg.attachments.StaffAttachmentService}
 * decide validation, quarantine, scan, explicit send and recipient-checked
 * download. Identity and roles come from the tenant context and fresh active
 * membership, never from the request body.</p>
 */
@RestController
@RequestMapping("/api/v1/staff/attachments")
public final class StaffAttachmentController {

    private final StaffAttachmentService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route over injected collaborators.
     *
     * @param service      attachment domain service
     * @param staff        fresh active-membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public StaffAttachmentController(StaffAttachmentService service, StaffRepository staff,
                                     TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Accept a PDF, JPEG or PNG into private quarantine bound to a draft.
     *
     * @param draftId conversation or mail-draft identifier
     * @param file    uploaded payload
     * @return HTTP 201 with the quarantined attachment
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StaffAttachment> upload(@RequestParam("draftId") String draftId,
                                                  @RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(422, "INVALID_UPLOAD", "the uploaded payload could not be read");
        }
        UploadRequest request = new UploadRequest(draftId, file.getContentType(),
                file.getOriginalFilename(), content, StaffAttachmentInputs.checksum(content));
        StaffAttachment created = service.upload(actor(), request);
        return ResponseEntity.created(URI.create("/api/v1/staff/attachments/" + created.id()))
                .body(created);
    }

    /**
     * Record an independent quarantine scan verdict.
     *
     * @param id      attachment identifier
     * @param request verdict command
     * @return the attachment with its recorded scan state
     */
    @PostMapping("/{id}/scan")
    public StaffAttachment scan(@PathVariable String id, @RequestBody ScanRequest request) {
        return service.scan(actor(), id, request);
    }

    /**
     * List the pending, unsent files of one draft for the sender to review.
     *
     * @param draftId conversation or mail-draft identifier
     * @return the pending files
     */
    @GetMapping("/pending")
    public java.util.List<StaffAttachment> pending(@RequestParam("draftId") String draftId) {
        return service.pending(actor(), draftId);
    }

    /**
     * Explicitly send named clean files after the sender has confirmed the recipients.
     *
     * @param request send command
     * @return the sent attachments
     */
    @PostMapping("/send")
    public java.util.List<StaffAttachment> send(@RequestBody SendRequest request) {
        return service.send(actor(), request);
    }

    /**
     * Return the verified original bytes to an authorized recipient.
     *
     * @param id attachment identifier
     * @return the original bytes with integrity headers
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        DownloadedFile file = service.download(actor(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header("X-Checksum", file.checksum())
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(file.sizeBytes()))
                .body(file.bytes());
    }

    private StaffActor actor() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        Set<String> roles = Set.copyOf(transactions.execute(
                status -> staff.activeRoles(context.subject())));
        return StaffActor.staff(context.organizationId(), context.subject(),
                roles.toArray(String[]::new));
    }
}
