package com.aurevia.labflow.outage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.lab.accession.LabInputs;
import com.aurevia.lab.result.LabRules;
import com.aurevia.labflow.outage.OutageModel.Disposition;
import com.aurevia.labflow.outage.OutageModel.EvidenceAttachment;
import com.aurevia.labflow.outage.OutageModel.ManualResult;
import com.aurevia.labflow.outage.OutageModel.ManualResultRequest;
import com.aurevia.labflow.outage.OutageModel.OutageEvent;
import com.aurevia.tenant.TenantContext;

/**
 * LAB-02 / XCH-03 / PRD-02 partner-outage manual result fallback.
 *
 * <p>When the partner result interface is down, an authorized laboratory user
 * attaches the original report and keys the observations in against the order
 * named by the caller. Boundary fields are validated before a transaction opens;
 * inside one tenant-guarded unit of work the service re-derives membership and
 * role, repeats the accession label match, and either links the evidence to the
 * order or queues the entry for a named owner. Both outcomes append an outage
 * history fact and an OPS-02 audit event with identifiers only - never the
 * inbound clinical payload. Entries and history are keyed by the active tenant,
 * so a peer organization sees neither queue nor history.</p>
 *
 * <p>The queue, order links and history are bounded in-memory projections, the
 * same pattern as the XCH-04 reconciliation decisions: this story reserves no
 * migration version and cannot create durable RLS tables. A persistence story
 * must move them behind a tenant-owned JDBC store before multi-replica use.
 * Refusals that must leave an audit row return {@code null} from the guarded
 * callback and throw the deferred failure after the transaction; a refusal that
 * writes no audit may throw inside the callback.</p>
 */
@Service
public class OutageService {

    /** Audit purpose stamped on every outage-fallback command. */
    public static final String PURPOSE = "PARTNER_OUTAGE_FALLBACK";
    /** Audit action and history action for a manual outage entry. */
    public static final String ACTION = "LAB_OUTAGE_MANUAL_ENTRY";
    /** Audited resource type. */
    public static final String RESOURCE = "MANUAL_RESULT";
    /** Audit scope. */
    public static final String SCOPE = "LAB";

    private final LabAccessionRepository accessions;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final Clock clock;
    private final AtomicInteger sequence = new AtomicInteger();
    private final ConcurrentMap<String, ManualResult> orderLinks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<ManualResult>> unmatchedEntries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<OutageEvent>> outages = new ConcurrentHashMap<>();

    /**
     * Create the service.
     *
     * @param accessions   RLS-bounded accession repository used for order lookup and authorization
     * @param transactions tenant-guarded transaction template
     * @param audit        transactional audit appender
     * @param clock        injected clock for deterministic timestamps
     */
    public OutageService(LabAccessionRepository accessions, TransactionTemplate transactions,
                         AuditAppender audit, Clock clock) {
        this.accessions = accessions;
        this.transactions = transactions;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Attach the original report and key observations in against an order during an outage.
     *
     * @param orderRef order or specimen reference from the route
     * @param request  bounded manual-entry command
     * @return the linked entry, or the queued unmatched entry
     * @throws ApiException on invalid input, denial, concealment or a missing owner
     */
    public ManualResult record(String orderRef, ManualResultRequest request) {
        String reference = LabInputs.id(orderRef);
        if (request == null) {
            throw new ApiException(422, "INVALID_REQUEST", "a manual result entry is required");
        }
        String reason = OutageDecision.reason(request.reason());
        EvidenceAttachment attachment = OutageDecision.attachment(request.attachment());
        String summary = OutageDecision.observationSummary(request.observations());
        String patientId = LabInputs.patientId(request.patientId());
        String number = LabInputs.accessionNo(request.accessionNo());
        String issuer = LabInputs.issuer(request.accessionIssuer());
        Instant at = clock.instant();

        AtomicReference<ApiException> failure = new AtomicReference<>();
        ManualResult result = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, reference);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            LabAccession accession = accessions.findById(reference).orElse(null);
            boolean matched = accession != null && OutageDecision.matches(accession, patientId, number, issuer);
            String owner = matched ? null : OutageDecision.owner(request.unmatchedOwner());
            String orderId = matched ? accession.id() : null;
            Disposition disposition = matched ? Disposition.LINKED : Disposition.UNMATCHED;
            String entryId = "manual-" + sequence.incrementAndGet();
            ManualResult entry = new ManualResult(entryId, orderId, disposition, patientId, reason,
                    attachment, summary, owner, context.subject(), at);
            OutageEvent event = new OutageEvent("outage-" + entryId, reference, ACTION, disposition,
                    reason, context.subject(), at);

            if (matched) {
                orderLinks.put(linkKey(context, orderId), entry);
            } else {
                // Copy-on-write values keep concurrent entries and stable snapshots:
                // a plain ArrayList here could lose an entry or race List.copyOf.
                unmatchedEntries.computeIfAbsent(tenant(context),
                        key -> new CopyOnWriteArrayList<>()).add(entry);
            }
            outages.computeIfAbsent(linkKey(context, reference),
                    key -> new CopyOnWriteArrayList<>()).add(event);
            audit.append(context, PURPOSE, ACTION, RESOURCE, SCOPE, matched ? orderId : reference,
                    matched ? "SUCCESS" : "UNMATCHED");
            return entry;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result;
    }

    /**
     * Read the entry whose evidence was linked to an order in the active tenant.
     *
     * <p>Reads are tenant-scoped but deliberately not role-gated: outage status
     * and queue metadata are operational facts available to any authenticated
     * member of the active organization, as for the XCH-04 reconciliation reads.
     * The write command still requires a laboratory role.</p>
     *
     * @param orderId opaque order identifier
     * @return the linked entry, or empty when this order has no outage entry
     */
    public Optional<ManualResult> forOrder(String orderId) {
        String id = LabInputs.id(orderId);
        TenantContext context = TenantContext.current();
        return Optional.ofNullable(orderLinks.get(linkKey(context, id)));
    }

    /**
     * List unmatched manual entries owned in the active tenant.
     *
     * <p>Tenant-scoped read with the same role policy as the other outage reads:
     * any authenticated member may see the queue; only a laboratory role may add
     * to it.</p>
     *
     * @return immutable snapshot of the unmatched queue
     */
    public List<ManualResult> unmatchedQueue() {
        TenantContext context = TenantContext.current();
        return List.copyOf(unmatchedEntries.getOrDefault(tenant(context), List.of()));
    }

    /**
     * Read the append-only outage history for one order reference in the active tenant.
     *
     * <p>Tenant-scoped read with the same role policy as the other outage reads:
     * any authenticated member may see the outage facts; only a laboratory role
     * may append one.</p>
     *
     * @param orderRef order reference presented by the operator
     * @return immutable snapshot of the outage events in occurrence order
     */
    public List<OutageEvent> history(String orderRef) {
        String reference = LabInputs.id(orderRef);
        TenantContext context = TenantContext.current();
        return List.copyOf(outages.getOrDefault(linkKey(context, reference), List.of()));
    }

    private ApiException authorize(TenantContext context, String reference) {
        if (!accessions.hasActiveMembership(context.subject())) {
            audit.append(context, PURPOSE, ACTION, RESOURCE, SCOPE, reference, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        Set<String> roles = Set.copyOf(accessions.activeRoles(context.subject()));
        if (!LabRules.mayCapture(roles) && !LabRules.mayVerify(roles)) {
            audit.append(context, PURPOSE, ACTION, RESOURCE, SCOPE, reference, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing laboratory permission");
        }
        return null;
    }

    private static String tenant(TenantContext context) {
        return context.organizationId();
    }

    private static String linkKey(TenantContext context, String id) {
        return context.organizationId() + ":" + id;
    }
}
