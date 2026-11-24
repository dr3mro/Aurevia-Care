package com.aurevia.radiology;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadCommands.AssociateStudyCommand;
import com.aurevia.radiology.RadCommands.IntakeCommand;
import com.aurevia.radiology.RadCommands.StudyReviewCommand;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for the RAD-01 imaging workflow.
 *
 * <p>The organization, actor and active roles are derived from the request
 * tenant context and freshly resolved membership; none is taken from the
 * command body. State transitions, clinical verification and referrer
 * notification stay in {@link com.aurevia.radiology.ImagingService}.</p>
 */
@RestController
@RequestMapping("/api/v1/imaging/orders")
public final class ImagingController {

    private final ImagingService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route with a synthetic approved modality catalog.
     *
     * @param clock        shared application clock
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public ImagingController(Clock clock, StaffRepository staff, TransactionTemplate transactions) {
        this(new ImagingService(new RadPolicyCatalog(List.of(Modality.values()), List.of()), clock),
                staff, transactions);
    }

    /**
     * Create the route over a supplied service, used by HTTP tests with fixtures.
     *
     * @param service      imaging domain service
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    ImagingController(ImagingService service, StaffRepository staff, TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Receive an imaging order from an authenticated, authorized staff member.
     *
     * @param request intake fields; identity is supplied by the server
     * @return HTTP 201 with the initial order revision
     */
    @PostMapping
    public ResponseEntity<ImagingOrder> intake(@RequestBody IntakeRequest request) {
        TenantContext context = context();
        ImagingOrder created = service.intake(new IntakeCommand(context.organizationId(),
                context.subject(), activeRoles(context), request.orderId(), request.patientId(),
                request.encounterId(), request.orderCode(), request.urgency(),
                request.clinicalQuestion(), request.sourceActor(), request.destination(),
                request.responsibleClinician(), request.workQueue(), request.referrer(),
                request.modality()));
        return ResponseEntity.created(URI.create("/api/v1/imaging/orders/" + created.orderId()))
                .body(created);
    }

    /**
     * Read the current order revision in the active tenant.
     *
     * @param orderId tenant-local order identifier
     * @return the current order revision
     */
    @GetMapping("/{orderId}")
    public ImagingOrder read(@PathVariable String orderId) {
        return service.read(context().organizationId(), orderId);
    }

    /**
     * Link an external study after the supplied patient and accession labels match the order.
     *
     * <p>A mismatch raises a review item and changes no order revision. Identity
     * and the active roles always come from the request context, never the body.</p>
     *
     * @param orderId tenant-local order identifier
     * @param request link fields plus the version the caller last read
     * @return the new study-associated order revision
     */
    @PostMapping("/{orderId}/studies")
    public ImagingOrder linkStudy(@PathVariable String orderId, @RequestBody StudyLinkRequest request) {
        TenantContext context = context();
        return service.associateStudy(new AssociateStudyCommand(context.organizationId(),
                context.subject(), activeRoles(context), orderId, request.expectedVersion(),
                request.studyInstanceUid(), request.patientId(), request.accession(),
                request.workstationRef()));
    }

    /**
     * Read the active tenant's study-link review queue (RAD-01).
     *
     * @return immutable review revisions in the order they were raised
     */
    @GetMapping("/study-link-reviews")
    public List<StudyLinkReview> studyLinkReviews() {
        return service.studyLinkReviews(context().organizationId());
    }

    /**
     * Resolve a study-link review after a reviewer checked the mismatching labels.
     *
     * @param request review decision fields; tenant and actor come from the context
     * @return the new resolved review revision
     */
    @PostMapping("/study-link-reviews/resolve")
    public StudyLinkReview resolveStudyLinkReview(@RequestBody StudyReviewRequest request) {
        TenantContext context = context();
        return service.resolveStudyLinkReview(new StudyReviewCommand(context.organizationId(),
                context.subject(), activeRoles(context), request.orderId(),
                request.studyInstanceUid(), request.resolution()));
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
     * Resolve the caller's current roles inside the guarded transaction.
     *
     * @param context bound tenant context
     * @return fresh active role keys
     */
    private Set<String> activeRoles(TenantContext context) {
        return Set.copyOf(transactions.execute(status -> staff.activeRoles(context.subject())));
    }

    /**
     * Server-derived intake command fields. Organization, actor and roles are never client supplied.
     *
     * @param orderId              tenant-local order identifier
     * @param patientId            matched patient identifier
     * @param encounterId          source encounter identifier
     * @param orderCode            local order code
     * @param urgency              clinical urgency
     * @param clinicalQuestion     clinical question for the reader
     * @param sourceActor          ordering actor
     * @param destination          receiving destination
     * @param responsibleClinician named owner when urgency requires one
     * @param workQueue            responsible work queue
     * @param referrer             referring subject
     * @param modality             pre-selected approved modality, or {@code null} when set at scheduling
     * @return the intake request
     */
    public record IntakeRequest(String orderId, String patientId, String encounterId, String orderCode,
                                Urgency urgency, String clinicalQuestion, String sourceActor,
                                String destination, String responsibleClinician, String workQueue,
                                String referrer, Modality modality) {

        /**
         * Create an intake request whose modality is chosen when the order is scheduled.
         *
         * @param orderId              tenant-local order identifier
         * @param patientId            matched patient identifier
         * @param encounterId          source encounter identifier
         * @param orderCode            local order code
         * @param urgency              clinical urgency
         * @param clinicalQuestion     clinical question for the reader
         * @param sourceActor          ordering actor
         * @param destination          receiving destination
         * @param responsibleClinician named owner when urgency requires one
         * @param workQueue            responsible work queue
         * @param referrer             referring subject
         */
        public IntakeRequest(String orderId, String patientId, String encounterId, String orderCode,
                             Urgency urgency, String clinicalQuestion, String sourceActor,
                             String destination, String responsibleClinician, String workQueue,
                             String referrer) {
            this(orderId, patientId, encounterId, orderCode, urgency, clinicalQuestion, sourceActor,
                    destination, responsibleClinician, workQueue, referrer, null);
        }
    }

    /**
     * Client-supplied fields for linking an external study to an order.
     *
     * @param expectedVersion  version of the order the caller last read
     * @param studyInstanceUid external DICOM Study Instance UID
     * @param patientId        patient label from the partner system
     * @param accession        accession label from the partner system
     * @param workstationRef   partner diagnostic workstation reference
     * @return the study-link request
     */
    public record StudyLinkRequest(int expectedVersion, String studyInstanceUid, String patientId,
                                   String accession, String workstationRef) {
    }

    /**
     * Client-supplied fields for resolving a study-link review.
     *
     * @param orderId          tenant-local order identifier
     * @param studyInstanceUid external study that raised the review
     * @param resolution       bounded decision text recorded for the audit trail
     * @return the review-resolution request
     */
    public record StudyReviewRequest(String orderId, String studyInstanceUid, String resolution) {
    }
}
