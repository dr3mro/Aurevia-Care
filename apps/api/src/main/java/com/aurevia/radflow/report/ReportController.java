package com.aurevia.radflow.report;

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
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.Interpretation;
import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radflow.report.ReportCommands.AcknowledgeCommand;
import com.aurevia.radflow.report.ReportCommands.AddendumCommand;
import com.aurevia.radflow.report.ReportCommands.PublishCommand;
import com.aurevia.staff.StaffRepository;
import com.aurevia.tenant.TenantContext;

/**
 * Thin HTTP adapter for report delivery and addendum acknowledgment.
 *
 * <p>The organization, actor and active roles are derived from the request tenant
 * context and freshly resolved membership; none is taken from a request body.
 * Signature validation, revision binding, referrer identity and delivery facts
 * stay in {@link com.aurevia.radflow.report.ReportService}. Production
 * wiring must bind the referrer to verified membership in the integration story
 * that persists this kernel.</p>
 */
@RestController
@RequestMapping("/api/v1/radiology/reports")
public final class ReportController {

    private final ReportService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route over the injected report delivery kernel.
     *
     * @param service      report delivery domain service
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public ReportController(ReportService service, StaffRepository staff,
                            TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Deliver a signed interpretation to the referring clinician.
     *
     * @param request signed interpretation fields; identity is supplied by the server
     * @return HTTP 201 with the delivered report
     */
    @PostMapping
    public ResponseEntity<ReportDelivery> publish(@RequestBody PublishRequest request) {
        TenantContext context = context();
        ReportDelivery created = service.publish(new PublishCommand(context.organizationId(),
                context.subject(), activeRoles(context), request.orderId(), request.referrer(),
                interpretation(context.organizationId(), request.orderId(), 1, null,
                        InterpretationKind.INITIAL, context.subject(), request, null)));
        return ResponseEntity.created(URI.create("/api/v1/radiology/reports/" + created.orderId()))
                .body(created);
    }

    /**
     * Read the current report only for its verified referring clinician.
     *
     * @param orderId tenant-local order identifier
     * @return the current delivered report
     */
    @GetMapping("/{orderId}")
    public ReportDelivery read(@PathVariable String orderId) {
        TenantContext context = context();
        return service.readForReferrer(context.organizationId(), context.subject(), orderId);
    }

    /**
     * Link and deliver a signed addendum revision.
     *
     * @param orderId tenant-local order identifier
     * @param request addendum fields and the revision the caller last read
     * @return the updated delivered report with a new open receipt
     */
    @PostMapping("/{orderId}/addenda")
    public ReportDelivery addendum(@PathVariable String orderId, @RequestBody AddendumRequest request) {
        TenantContext context = context();
        return service.addendum(new AddendumCommand(context.organizationId(), context.subject(),
                activeRoles(context), orderId, interpretation(context.organizationId(), orderId,
                        request.expectedVersion() + 1, request.expectedVersion(),
                        InterpretationKind.ADDENDUM, context.subject(), request, request.reason())));
    }

    /**
     * Acknowledge one exact addendum revision as the verified referring clinician.
     *
     * @param orderId tenant-local order identifier
     * @param version addendum revision to acknowledge
     * @return the updated delivered report with the receipt closed
     */
    @PostMapping("/{orderId}/addenda/{version}/acknowledge")
    public ReportDelivery acknowledge(@PathVariable String orderId, @PathVariable int version) {
        TenantContext context = context();
        return service.acknowledge(new AcknowledgeCommand(context.organizationId(), context.subject(),
                orderId, version));
    }

    /**
     * Read the append-only interpretation revisions of one report.
     *
     * @param orderId tenant-local order identifier
     * @return revisions oldest first
     */
    @GetMapping("/{orderId}/revisions")
    public List<Interpretation> revisions(@PathVariable String orderId) {
        TenantContext context = context();
        return service.revisionsForReferrer(context.organizationId(), context.subject(), orderId);
    }

    /**
     * List the referring clinician's addenda that await acknowledgment.
     *
     * @return immutable open receipts for the authenticated subject
     */
    @GetMapping("/addenda/open")
    public List<AddendumReceipt> openAddenda() {
        TenantContext context = context();
        return service.openAddenda(context.organizationId(), context.subject());
    }

    /**
     * List the referring clinician's addenda that aged past the acknowledgment window.
     *
     * @return immutable overdue receipts for the authenticated subject
     */
    @GetMapping("/addenda/overdue")
    public List<AddendumReceipt> overdueAddenda() {
        TenantContext context = context();
        return service.overdueAddenda(context.organizationId(), context.subject());
    }

    /**
     * Read the delivery facts addressed to the referring clinician.
     *
     * @return immutable facts for the authenticated subject in emission order
     */
    @GetMapping("/events")
    public List<ReportEvent> events() {
        TenantContext context = context();
        return service.eventsForReferrer(context.organizationId(), context.subject());
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

    private static Interpretation interpretation(String organizationId, String orderId, int version,
                                                 Integer supersedesVersion, InterpretationKind kind,
                                                 String signer, ReportFields fields, String reason) {
        // The signer is the authenticated subject, never a caller-supplied name:
        // a report's signature must bind to the clinician who actually signed it.
        return new Interpretation(organizationId, orderId, version, supersedesVersion, kind,
                fields.patientId(), fields.studyInstanceUid(), fields.technique(), fields.comparison(),
                fields.findings(), fields.impression(), fields.followUp(), signer,
                fields.attestation(), parseInstant(fields.signedAt()), fields.templateVersion(), reason);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new ApiException(422, "INVALID_SIGNED_AT", "a valid signing time is required");
        }
    }

    /** Shared signed-narrative fields supplied by the reporting client. */
    private interface ReportFields {
        /** @return patient identifier on the report */
        String patientId();

        /** @return external Study Instance UID */
        String studyInstanceUid();

        /** @return technique section */
        String technique();

        /** @return comparison section */
        String comparison();

        /** @return findings section */
        String findings();

        /** @return impression section */
        String impression();

        /** @return follow-up section */
        String followUp();

        /** @return signer attestation */
        String attestation();

        /** @return ISO-8601 signing instant */
        String signedAt();

        /** @return template version used for the narrative */
        String templateVersion();
    }

    /**
     * Signed interpretation fields. Identity and roles come from the server context.
     *
     * @param orderId          tenant-local order identifier
     * @param referrer         referring clinician receiving the report
     * @param patientId        patient identifier on the report
     * @param studyInstanceUid external DICOM Study Instance UID
     * @param technique        technique section
     * @param comparison       comparison section
     * @param findings         findings section
     * @param impression       impression section
     * @param followUp         follow-up section
     * @param attestation      signer attestation
     * @param signedAt         ISO-8601 signing instant
     * @param templateVersion  template version used for the narrative
     * @return the publish request
     */
    public record PublishRequest(String orderId, String referrer, String patientId, String studyInstanceUid,
                                 String technique, String comparison, String findings, String impression,
                                 String followUp, String attestation, String signedAt,
                                 String templateVersion) implements ReportFields {
    }

    /**
     * Signed addendum fields bound to the revision the caller last read.
     *
     * @param expectedVersion  current report version the addendum supersedes
     * @param patientId        patient identifier on the report
     * @param studyInstanceUid external DICOM Study Instance UID
     * @param technique        technique section
     * @param comparison       comparison section
     * @param findings         findings section
     * @param impression       impression section
     * @param followUp         follow-up section
     * @param attestation      signer attestation
     * @param signedAt         ISO-8601 signing instant
     * @param templateVersion  template version used for the narrative
     * @param reason           clinical reason for the addendum
     * @return the addendum request
     */
    public record AddendumRequest(int expectedVersion, String patientId, String studyInstanceUid,
                                  String technique, String comparison, String findings, String impression,
                                  String followUp, String attestation, String signedAt,
                                  String templateVersion, String reason) implements ReportFields {
    }
}
