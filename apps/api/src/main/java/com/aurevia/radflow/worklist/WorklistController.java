package com.aurevia.radflow.worklist;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.Urgency;
import com.aurevia.radflow.worklist.WorklistCommands.LinkStudyCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ReceiveCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ReportCommand;
import com.aurevia.radflow.worklist.WorklistCommands.ScheduleCommand;
import com.aurevia.radflow.worklist.WorklistTypes.WorklistStatus;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for the RAD-01 performing-site worklist.
 *
 * <p>The organization, actor and active roles are derived from the request tenant
 * context and freshly resolved membership; none is taken from a command body.
 * Status transitions, patient and accession verification and hand-off facts stay
 * in {@link com.aurevia.radflow.worklist.WorklistService}. The
 * performing site is an explicit scope on every route; production wiring must
 * bind it to verified membership in the integration story that persists this
 * kernel.</p>
 */
@RestController
@RequestMapping("/api/v1/radiology/worklist")
public final class WorklistController {

    private final WorklistService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route over the injected worklist kernel.
     *
     * @param service      worklist domain service
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public WorklistController(WorklistService service, StaffRepository staff,
                              TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Receive an imaging order from an authenticated, authorized staff member.
     *
     * @param request receive fields; identity is supplied by the server
     * @return HTTP 201 with the initial order revision
     */
    @PostMapping("/orders")
    public ResponseEntity<WorklistOrder> receive(@RequestBody ReceiveRequest request) {
        TenantContext context = context();
        WorklistOrder created = service.receive(new ReceiveCommand(context.organizationId(),
                context.subject(), activeRoles(context), request.orderId(), request.performingSiteId(),
                request.patientId(), request.orderCode(), request.urgency(), request.clinicalQuestion(),
                request.referrer()));
        return ResponseEntity.created(URI.create("/api/v1/radiology/worklist/orders/" + created.orderId()))
                .body(created);
    }

    /**
     * Read the current order revision in the active tenant for one performing site.
     *
     * @param orderId tenant-local order identifier
     * @param site    performing site the caller is acting for
     * @return the current order revision
     */
    @GetMapping("/orders/{orderId}")
    public WorklistOrder read(@PathVariable String orderId, @RequestParam String site) {
        return service.readForSite(context().organizationId(), site, orderId);
    }

    /**
     * Read the worklist of one performing site in the active tenant.
     *
     * @param site   performing site whose worklist is requested
     * @param status optional exact status filter
     * @return immutable current worklist entries
     */
    @GetMapping
    public List<WorklistEntry> worklist(@RequestParam String site,
                                        @RequestParam(required = false) WorklistStatus status) {
        return service.worklist(context().organizationId(), site, status);
    }

    /**
     * Record the study time, an approved modality and the accession at scheduling.
     *
     * @param orderId tenant-local order identifier
     * @param request schedule fields plus the version the caller last read
     * @return the new scheduled order revision
     */
    @PostMapping("/orders/{orderId}/schedule")
    public WorklistOrder schedule(@PathVariable String orderId, @RequestBody ScheduleRequest request) {
        TenantContext context = context();
        return service.schedule(new ScheduleCommand(context.organizationId(), context.subject(),
                activeRoles(context), orderId, request.performingSiteId(), request.expectedVersion(),
                parseInstant(request.scheduledAt()), request.modality(), request.accession(),
                request.accessionIssuer()));
    }

    /**
     * Link an external study after the supplied patient and accession labels match.
     *
     * @param orderId tenant-local order identifier
     * @param request link fields plus the version the caller last read
     * @return the new performed order revision
     */
    @PostMapping("/orders/{orderId}/studies")
    public WorklistOrder linkStudy(@PathVariable String orderId, @RequestBody StudyLinkRequest request) {
        TenantContext context = context();
        return service.linkStudy(new LinkStudyCommand(context.organizationId(), context.subject(),
                activeRoles(context), orderId, request.performingSiteId(), request.expectedVersion(),
                request.studyInstanceUid(), request.patientId(), request.accession(),
                request.workstationRef()));
    }

    /**
     * Hand a performed study off as reported.
     *
     * @param orderId tenant-local order identifier
     * @param request report fields plus the version the caller last read
     * @return the new reported order revision
     */
    @PostMapping("/orders/{orderId}/report")
    public WorklistOrder report(@PathVariable String orderId, @RequestBody ReportRequest request) {
        TenantContext context = context();
        return service.report(new ReportCommand(context.organizationId(), context.subject(),
                activeRoles(context), orderId, request.performingSiteId(), request.expectedVersion()));
    }

    /**
     * Read one performing site's domain facts, including the study hand-off.
     *
     * @param site performing site whose facts are requested
     * @return immutable facts for that site in emission order
     */
    @GetMapping("/events")
    public List<WorklistEvent> events(@RequestParam String site) {
        return service.eventsForSite(context().organizationId(), site);
    }

    private static TenantContext context() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private Set<String> activeRoles(TenantContext context) {
        return Set.copyOf(transactions.execute(status -> staff.activeRoles(context.subject())));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_SCHEDULE", "a valid scheduled time is required");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_SCHEDULE", "a valid scheduled time is required");
        }
    }

    /**
     * Receive fields. Identity and roles come from the server context; the
     * performing site is an explicit scope that production wiring must bind to
     * verified membership.
     *
     * @param orderId          tenant-local order identifier
     * @param performingSiteId performing site receiving the order
     * @param patientId        matched patient identifier
     * @param orderCode        local order code
     * @param urgency          clinical urgency
     * @param clinicalQuestion question for the reader
     * @param referrer         referring subject
     * @return the receive request
     */
    public record ReceiveRequest(String orderId, String performingSiteId, String patientId,
                                 String orderCode, Urgency urgency, String clinicalQuestion,
                                 String referrer) {
    }

    /**
     * Client-supplied scheduling fields.
     *
     * @param expectedVersion  version of the order the caller last read
     * @param performingSiteId performing site acting on the order
     * @param scheduledAt      ISO-8601 scheduled instant
     * @param modality         approved imaging modality
     * @param accession        issuer-scoped accession
     * @param accessionIssuer  issuer of the accession
     * @return the schedule request
     */
    public record ScheduleRequest(int expectedVersion, String performingSiteId, String scheduledAt,
                                  Modality modality, String accession, String accessionIssuer) {
    }

    /**
     * Client-supplied fields for linking an external study.
     *
     * @param expectedVersion  version of the order the caller last read
     * @param performingSiteId performing site acting on the order
     * @param studyInstanceUid external DICOM Study Instance UID
     * @param patientId        patient label from the partner system
     * @param accession        accession label from the partner system
     * @param workstationRef   partner diagnostic workstation reference
     * @return the study-link request
     */
    public record StudyLinkRequest(int expectedVersion, String performingSiteId, String studyInstanceUid,
                                   String patientId, String accession, String workstationRef) {
    }

    /**
     * Client-supplied fields for reporting a performed study.
     *
     * @param expectedVersion  version of the order the caller last read
     * @param performingSiteId performing site acting on the order
     * @return the report request
     */
    public record ReportRequest(int expectedVersion, String performingSiteId) {
    }
}
