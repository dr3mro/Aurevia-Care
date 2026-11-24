package com.aurevia.printing.web;

import java.net.URI;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.documents.DocumentContract.Actor;
import com.aurevia.printing.HandoutContract.Handout;
import com.aurevia.printing.HandoutContract.HandoutRequest;
import com.aurevia.printing.HandoutContract.HandoutSource;
import com.aurevia.printing.HandoutContract.RenderedHandout;
import com.aurevia.printing.HandoutService;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenancy.FacilityRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for patient handout printing and mediated download (CLN-07, RX-02, ARC-06).
 *
 * <p>Identity is rebuilt from the bound tenant context and the current active
 * membership on every command; no role, facility or patient identifier is read
 * from the request. The source revision is validated by the injected handout
 * service and the permission decision is re-derived by
 * {@link com.aurevia.printing.web.PrintPolicy}. Every print, reprint,
 * download and refusal writes one sanitized OPS-02 fact through
 * {@link com.aurevia.printing.web.PrintAuditTrail} inside the same
 * tenant-guarded transaction. Handout bytes never enter a log, URL or audit fact.</p>
 *
 * <p>The reprint marker is process-local and best-effort: a durable audit read
 * model is a separate delivery slice. It only chooses the audit action label and
 * never changes the handout version or bytes.</p>
 */
@RestController
@RequestMapping("/api/v1/printing")
public final class PrintController {

    /** Audit action for the first print of a handout version. */
    public static final String ACTION_PRINT = "HANDOUT_PRINT";
    /** Audit action for a reprint of an unchanged handout version. */
    public static final String ACTION_REPRINT = "HANDOUT_REPRINT";
    /** Audit action for a mediated download. */
    public static final String ACTION_DOWNLOAD = "HANDOUT_DOWNLOAD";

    private final HandoutService service;
    private final StaffRepository staff;
    private final FacilityRepository facilities;
    private final TransactionTemplate transactions;
    private final PrintAuditTrail auditTrail;
    private final Clock clock;
    private final ConcurrentMap<String, Boolean> printed = new ConcurrentHashMap<>();

    /**
     * Create the adapter over injected collaborators.
     *
     * @param service      tenant-scoped handout rendering service
     * @param staff        fresh membership and role source
     * @param facilities   record-facility check in the active tenant
     * @param transactions tenant-guarded transaction template
     * @param auditTrail   sanitized print audit sink
     * @param clock        server clock for audit timestamps
     */
    @Autowired
    public PrintController(HandoutService service, StaffRepository staff, FacilityRepository facilities,
                           TransactionTemplate transactions, PrintAuditTrail auditTrail, Clock clock) {
        this.service = service;
        this.staff = staff;
        this.facilities = facilities;
        this.transactions = transactions;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    /**
     * Render a patient handout for one exact source revision.
     *
     * @param request validated source, language and reason
     * @return HTTP 201 with the immutable handout metadata
     */
    @PostMapping("/handouts")
    public ResponseEntity<Handout> print(@RequestBody PrintRequest request) {
        PrintReason reason = PrintReason.parse(request.reason());
        TenantContext context = context();
        HandoutSource source = request.source();
        AtomicReference<ApiException> denial = new AtomicReference<>();
        RenderedHandout rendered = transactions.execute(status -> {
            RenderedHandout result = render(context, source, request.language(), reason, ACTION_PRINT, denial);
            if (result == null) {
                return null;
            }
            String key = context.organizationId() + ":" + source.kind().wire() + ":" + source.sourceId()
                    + ":" + source.revisionVersion() + ":" + source.contentChecksum() + ":"
                    + request.language();
            String action = printed.putIfAbsent(key, Boolean.TRUE) == null ? ACTION_PRINT : ACTION_REPRINT;
            auditTrail.record(success(context, source, result.metadata(), reason, action));
            return result;
        });
        if (denial.get() != null) {
            throw denial.get();
        }
        Handout handout = rendered.metadata();
        return ResponseEntity.created(
                URI.create("/api/v1/printing/handouts/" + handout.handoutId() + "/content")).body(handout);
    }

    /**
     * Download one immutable handout version after re-checking permission.
     *
     * @param request source selectors, expected version and reason
     * @return the deterministic PDF bytes with a PDF content type
     */
    @PostMapping("/handouts/download")
    public ResponseEntity<byte[]> download(@RequestBody DownloadRequest request) {
        PrintReason reason = PrintReason.parse(request.reason());
        TenantContext context = context();
        HandoutSource source = request.source();
        AtomicReference<ApiException> denial = new AtomicReference<>();
        RenderedHandout rendered = transactions.execute(status -> {
            RenderedHandout result = render(context, source, request.language(), reason, ACTION_DOWNLOAD, denial);
            if (result == null) {
                return null;
            }
            Handout handout = result.metadata();
            if (handout.version() != request.version()) {
                denial.set(new ApiException(409, "STALE_VERSION", "the requested handout version is not current"));
                auditTrail.record(new PrintAuditEntry(context.organizationId(), context.subject(),
                        handout.kind(), handout.handoutId(), request.version(), handout.sourceRevision(),
                        source.facility(), reason, ACTION_DOWNLOAD, "DENIED", clock.instant()));
                return null;
            }
            auditTrail.record(success(context, source, handout, reason, ACTION_DOWNLOAD));
            return result;
        });
        if (denial.get() != null) {
            throw denial.get();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(rendered.content());
    }

    /**
     * Re-derive identity and permission inside the caller's transaction, then render.
     *
     * <p>A denial is written to the audit trail before the failure is carried out
     * of the transaction, so it survives the rollback of the protected command.</p>
     *
     * @param context  bound tenant context
     * @param source   selected source revision
     * @param language en or ar
     * @param reason   approved reason
     * @param action   audit action used when the command is denied
     * @param denial   carries a durable failure out of the transaction
     * @return the immutable rendered handout, or {@code null} on denial
     */
    private RenderedHandout render(TenantContext context, HandoutSource source, String language,
                                   PrintReason reason, String action, AtomicReference<ApiException> denial) {
        if (!staff.hasActiveMembership(context.subject())) {
            denial.set(new ApiException(403, "FORBIDDEN", "no active membership"));
            auditTrail.record(denied(context, source, reason, action));
            return null;
        }
        Set<String> roles = Set.copyOf(staff.activeRoles(context.subject()));
        Actor actor = new Actor(context.organizationId(), context.subject(), roles, source.patientId());
        boolean facilityInScope = facilities.findByCode(source.facility()).isPresent();
        try {
            PrintPolicy.authorize(actor, source, facilityInScope);
        } catch (ApiException error) {
            denial.set(error);
            auditTrail.record(denied(context, source, reason, action));
            return null;
        }
        return service.handout(new HandoutRequest(actor, language, source));
    }

    private PrintAuditEntry success(TenantContext context, HandoutSource source, Handout handout,
                                    PrintReason reason, String action) {
        return new PrintAuditEntry(context.organizationId(), context.subject(), handout.kind(),
                handout.handoutId(), handout.version(), handout.sourceRevision(), source.facility(),
                reason, action, "SUCCESS", clock.instant());
    }

    private PrintAuditEntry denied(TenantContext context, HandoutSource source, PrintReason reason,
                                   String action) {
        return new PrintAuditEntry(context.organizationId(), context.subject(), source.kind(),
                source.sourceId(), 0, source.revisionVersion(), source.facility(), reason, action,
                "DENIED", clock.instant());
    }

    private static TenantContext context() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    /**
     * Print command body: the caller supplies selectors and a reason, never an authority claim.
     *
     * @param source   exact source revision to render
     * @param language en or ar
     * @param reason   approved reason wire key
     * @return the immutable request
     */
    public record PrintRequest(HandoutSource source, String language, String reason) {
    }

    /**
     * Download command body for one immutable version.
     *
     * @param source   exact source revision that produced the version
     * @param language en or ar
     * @param reason   approved reason wire key
     * @param version  expected immutable handout version
     * @return the immutable request
     */
    public record DownloadRequest(HandoutSource source, String language, String reason, int version) {
    }
}
