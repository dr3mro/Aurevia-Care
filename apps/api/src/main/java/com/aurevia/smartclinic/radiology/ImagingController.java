package com.aurevia.smartclinic.radiology;

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

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.radiology.RadCommands.IntakeCommand;
import com.aurevia.smartclinic.radiology.RadTypes.Modality;
import com.aurevia.smartclinic.radiology.RadTypes.Urgency;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;

/**
 * Thin HTTP adapter for the RAD-01 imaging workflow.
 *
 * <p>The organization, actor and active roles are derived from the request
 * tenant context and freshly resolved membership; none is taken from the
 * command body. State transitions, clinical verification and referrer
 * notification stay in {@link com.aurevia.smartclinic.radiology.ImagingService}.</p>
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
                request.responsibleClinician(), request.workQueue(), request.referrer()));
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
     * @return the intake request
     */
    public record IntakeRequest(String orderId, String patientId, String encounterId, String orderCode,
                                Urgency urgency, String clinicalQuestion, String sourceActor,
                                String destination, String responsibleClinician, String workQueue,
                                String referrer) {
    }
}
