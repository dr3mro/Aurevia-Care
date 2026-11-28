package com.aurevia.messaging.attachments;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aurevia.api.ApiException;
import com.aurevia.api.IfMatch;
import com.aurevia.messaging.attachments.AttachmentContract.Actor;
import com.aurevia.messaging.attachments.AttachmentContract.Attachment;
import com.aurevia.messaging.attachments.AttachmentContract.ConnectionGrant;
import com.aurevia.messaging.attachments.AttachmentContract.MediatedDownload;
import com.aurevia.messaging.attachments.AttachmentContract.ScanRequest;
import com.aurevia.messaging.attachments.AttachmentContract.UploadRequest;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for the attachment workflow.
 *
 * <p>This is the protected multipart entry point for patient document upload and
 * mediated retrieval. Payload bytes are never logged or returned; the adapter
 * derives the magic prefix, size and SHA-256 checksum from the uploaded bytes
 * and lets {@link com.aurevia.messaging.attachments.AttachmentService}
 * decide quarantine, scan, publication and access. Identity, unit and patient
 * binding are resolved from the server-side connection grant and membership,
 * never from the request.</p>
 */
@RestController
@RequestMapping("/api/v1/patient/files")
public final class AttachmentController {

    private static final int MAGIC_BYTES = 4;

    private final AttachmentService service;
    private final AttachmentStore store;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route over the injected domain service and tenant-scoped store.
     *
     * @param service      attachment domain service
     * @param store        tenant-scoped attachment store
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public AttachmentController(AttachmentService service, AttachmentStore store,
                                 StaffRepository staff, TransactionTemplate transactions) {
        this.service = service;
        this.store = store;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Accept a PDF, JPEG or PNG into private quarantine through a verified connection.
     *
     * @param connectionId verified care-unit connection identifier
     * @param file         uploaded payload
     * @return HTTP 201 with the quarantined attachment
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Attachment> upload(@RequestParam("connectionId") String connectionId,
                                             @RequestPart("file") MultipartFile file)
            throws NoSuchAlgorithmException {
        TenantContext context = context();
        ConnectionGrant grant = store.grant(context.organizationId(), connectionId)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "connection not found"));
        Actor actor = actor(context, grant.patientId(), null);
        Attachment created = service.upload(actor, uploadRequest(connectionId, file));
        return ResponseEntity.created(URI.create("/api/v1/patient/files/" + created.id()))
                .body(created);
    }

    /**
     * Record an independent quarantine scan verdict with a version precondition.
     *
     * @param id      attachment identifier
     * @param request verdict command
     * @param ifMatch expected version
     * @return the attachment with its recorded verdict
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @PostMapping("/{id}/scan")
    public Attachment scan(@PathVariable String id, @RequestBody ScanRequest request,
                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Attachment current = store.attachment(context().organizationId(), id)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "attachment not found"));
        return service.scan(actor(context(), null, current.unitId()), id, request,
                IfMatch.parse(ifMatch));
    }

    /**
     * Obtain a mediated handle for a published attachment.
     *
     * @param id      attachment identifier
     * @param ifMatch expected version
     * @return the object handle, without bytes or a public URL
     * @throws com.aurevia.api.ApiException when the delegated command is refused
     */
    @GetMapping("/{id}/download")
    public MediatedDownload download(@PathVariable String id,
                                     @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        TenantContext context = context();
        Attachment attachment = store.attachment(context.organizationId(), id)
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "attachment not found"));
        return service.download(actor(context, attachment.patientId(), null), id,
                IfMatch.parse(ifMatch));
    }

    /**
     * Build the upload command from verified payload bytes.
     *
     * @param connectionId verified connection identifier
     * @param file         uploaded payload
     * @return content metadata for the quarantine boundary
     */
    private static UploadRequest uploadRequest(String connectionId, MultipartFile file)
            throws NoSuchAlgorithmException {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(422, "INVALID_UPLOAD", "the uploaded payload could not be read");
        }
        if (bytes.length < 1) {
            throw new ApiException(422, "INVALID_UPLOAD", "the uploaded payload is empty");
        }
        byte[] prefix = java.util.Arrays.copyOf(bytes, Math.min(MAGIC_BYTES, bytes.length));
        String magic = HexFormat.of().formatHex(prefix);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new UploadRequest(connectionId, file.getOriginalFilename(), file.getContentType(),
                magic, bytes.length, checksum.toLowerCase(Locale.ROOT));
    }

    /**
     * Read the active tenant context, failing closed when none is bound.
     *
     * @return the bound tenant context
     */
    private static TenantContext context() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    /**
     * Build a fresh actor from the bound context and current membership roles.
     *
     * @param context   bound tenant context
     * @param patientId linked patient identifier, or {@code null} for staff
     * @param unitId    assigned unit identifier, or {@code null} for a patient
     * @return the actor for this request
     */
    private Actor actor(TenantContext context, String patientId, String unitId) {
        Set<String> roles = Set.copyOf(
                transactions.execute(status -> staff.activeRoles(context.subject())));
        return new Actor(context.organizationId(), context.subject(), roles, patientId, unitId);
    }
}
