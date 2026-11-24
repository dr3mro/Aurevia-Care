package com.aurevia.documents;

import java.net.URI;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.api.IfMatch;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.documents.DocumentContract.Artifact;
import com.aurevia.documents.DocumentContract.BindRequest;
import com.aurevia.documents.DocumentContract.CompleteRequest;
import com.aurevia.documents.DocumentContract.MediatedDownload;
import com.aurevia.documents.DocumentContract.PdfBinding;
import com.aurevia.documents.DocumentContract.PdfJob;
import com.aurevia.documents.DocumentContract.ReleaseDecision;
import com.aurevia.documents.DocumentContract.ReleaseRequest;
import com.aurevia.documents.DocumentContract.RemoveRequest;
import com.aurevia.documents.DocumentContract.RenderFinishRequest;
import com.aurevia.documents.DocumentContract.RenderRequest;
import com.aurevia.documents.DocumentContract.ScanRequest;
import com.aurevia.documents.DocumentContract.UploadRequest;
import com.aurevia.documents.DocumentContract.View;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin tenant-owned HTTP entry point for document artifact commands (ARC-06).
 *
 * <p>Every command derives the organization from the bound tenant context and
 * the caller's roles from current active membership; no tenant, role or patient
 * binding is accepted from a request body. Payload validation, quarantine
 * transitions, object verification, PDF binding and release invariants stay in
 * {@link com.aurevia.documents.QuarantineService} and
 * {@link com.aurevia.documents.PdfBindingService} over the shared
 * tenant-scoped {@link com.aurevia.documents.DocumentStore}.</p>
 */
@RestController
@RequestMapping("/api/v1/documents/artifacts")
public final class DocumentArtifactController {

    private final QuarantineService quarantine;
    private final PdfBindingService pdf;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route over the injected domain services and tenant sources.
     *
     * @param quarantine     upload and quarantine command service
     * @param pdf            render, binding and release command service
     * @param staff          fresh active-membership role source
     * @param transactions   tenant-guarded transaction template
     */
    @Autowired
    public DocumentArtifactController(QuarantineService quarantine, PdfBindingService pdf,
                                      StaffRepository staff, TransactionTemplate transactions) {
        this.quarantine = quarantine;
        this.pdf = pdf;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Accept a validated payload into private quarantine.
     *
     * @param request content metadata for the upload
     * @return HTTP 201 with the quarantined artifact
     */
    @PostMapping
    public ResponseEntity<Artifact> upload(@RequestBody UploadRequest request) {
        Artifact created = quarantine.upload(actor(context()), request);
        return ResponseEntity.created(
                URI.create("/api/v1/documents/artifacts/" + created.id())).body(created);
    }

    /**
     * Record an independent quarantine scan verdict.
     *
     * @param id       artifact identifier
     * @param request  verdict command
     * @param ifMatch  expected artifact version
     * @return the artifact with its recorded verdict
     */
    @PostMapping("/{id}/scan")
    public Artifact scan(@PathVariable String id, @RequestBody ScanRequest request,
                         @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return quarantine.scan(actor(context()), id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Complete a clean, object-verified artifact.
     *
     * @param id       artifact identifier
     * @param request  completion command
     * @param ifMatch  expected artifact version
     * @return the completed artifact
     */
    @PostMapping("/{id}/complete")
    public Artifact complete(@PathVariable String id, @RequestBody CompleteRequest request,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return quarantine.complete(actor(context()), id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Remove a draft or failed artifact.
     *
     * @param id       artifact identifier
     * @param request  removal command
     * @param ifMatch  expected artifact version
     * @return the removed artifact
     */
    @PostMapping("/{id}/remove")
    public Artifact remove(@PathVariable String id, @RequestBody RemoveRequest request,
                           @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return quarantine.remove(actor(context()), id, request, IfMatch.parse(ifMatch));
    }

    /**
     * Obtain a mediated, metadata-only download handle.
     *
     * @param id       artifact identifier
     * @param ifMatch  expected artifact version
     * @return the handle, with no payload, name or URL
     */
    @GetMapping("/{id}/download")
    public MediatedDownload download(@PathVariable String id,
                                     @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return quarantine.download(actor(context()), id, IfMatch.parse(ifMatch));
    }

    /**
     * Read one artifact and its append-only fact trail.
     *
     * @param id artifact identifier
     * @return the view when authorized and visible
     */
    @GetMapping("/{id}")
    public View view(@PathVariable String id) {
        return quarantine.view(actor(context()), id);
    }

    /**
     * Request an asynchronous render for one exact immutable revision.
     *
     * @param request revision, template and language
     * @return HTTP 201 with the requested or existing job
     */
    @PostMapping("/renders")
    public ResponseEntity<PdfJob> requestRender(@RequestBody RenderRequest request) {
        PdfJob job = pdf.requestRender(actor(context()), request);
        return ResponseEntity.created(
                URI.create("/api/v1/documents/artifacts/renders/" + job.jobId())).body(job);
    }

    /**
     * Pick up a requested render job.
     *
     * @param jobId   job identifier
     * @param ifMatch expected job version
     * @return the rendering job
     */
    @PostMapping("/renders/{jobId}/pickup")
    public PdfJob pickupRender(@PathVariable String jobId,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return pdf.pickupRender(actor(context()), jobId, IfMatch.parse(ifMatch));
    }

    /**
     * Finish a render with a verified private object version.
     *
     * @param jobId   job identifier
     * @param request verified object metadata
     * @param ifMatch expected job version
     * @return the completed job
     */
    @PostMapping("/renders/{jobId}/finish")
    public PdfJob finishRender(@PathVariable String jobId, @RequestBody RenderFinishRequest request,
                               @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return pdf.finishRender(actor(context()), jobId, request, IfMatch.parse(ifMatch));
    }

    /**
     * Record a render failure so no PDF can be bound from the job.
     *
     * @param jobId   job identifier
     * @param request bounded non-sensitive failure detail
     * @param ifMatch expected job version
     * @return the failed job
     */
    @PostMapping("/renders/{jobId}/fail")
    public PdfJob failRender(@PathVariable String jobId, @RequestBody FailRequest request,
                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return pdf.failRender(actor(context()), jobId, request.detail(), IfMatch.parse(ifMatch));
    }

    /**
     * Bind a completed PDF to one exact immutable revision.
     *
     * @param request revision selector and job identifier
     * @return HTTP 201 with the immutable binding
     */
    @PostMapping("/bindings")
    public ResponseEntity<PdfBinding> bind(@RequestBody BindRequest request) {
        PdfBinding binding = pdf.bind(actor(context()), request);
        return ResponseEntity.created(
                URI.create("/api/v1/documents/artifacts/bindings/" + binding.revisionId())).body(binding);
    }

    /**
     * Record a release decision for one exact revision and policy version.
     *
     * @param request revision, policy version and PDF requirement
     * @return HTTP 201 with the decision
     */
    @PostMapping("/releases")
    public ResponseEntity<ReleaseDecision> release(@RequestBody ReleaseRequest request) {
        ReleaseDecision decision = pdf.release(actor(context()), request);
        return ResponseEntity.created(
                URI.create("/api/v1/documents/artifacts/releases/" + decision.revisionId())).body(decision);
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
     * @param context bound tenant context
     * @return the actor for this request
     */
    private Actor actor(TenantContext context) {
        Set<String> roles = Set.copyOf(
                transactions.execute(status -> staff.activeRoles(context.subject())));
        // A patient actor carries no independently verified patient link here, and
        // the domain treats a null patientId as "no ownership restriction". Fail
        // closed instead of letting a patient-role principal bypass that check.
        if (roles.contains("patient")) {
            throw new ApiException(403, "FORBIDDEN", "patient commands require a verified patient link");
        }
        return Actor.staff(context.organizationId(), context.subject(), roles.toArray(String[]::new));
    }

    /**
     * Bounded non-sensitive failure detail for a render.
     *
     * @param detail failure detail recorded on the fact ledger
     * @return the render failure command body
     */
    public record FailRequest(String detail) {
    }
}
