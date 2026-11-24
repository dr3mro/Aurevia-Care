package com.aurevia.labflow.release;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.lab.accession.LabAccessionRepository;
import com.aurevia.exchange.release.AuthorizeRelease;
import com.aurevia.exchange.release.PatientRelease;
import com.aurevia.exchange.release.ReleaseEvent;
import com.aurevia.exchange.release.ReleaseKind;
import com.aurevia.exchange.release.ReleaseService;
import com.aurevia.exchange.release.ReleaseState;
import com.aurevia.tenant.TenantContext;

/**
 * Release of verified laboratory reports to the clinician immediately and to the patient by
 * facility policy (LAB-02, PAT-02, XCH-06).
 *
 * <p>The durable XCH-06 {@link com.aurevia.exchange.release.ReleaseService} owns the
 * patient authorization, timed/hold visibility, correction and withdrawal inside forced-RLS
 * tables. This slice composes it with a facility release policy and records append-only viewer
 * notification facts so a correction tells the clinician and any patient who saw the earlier
 * revision, and a withdrawal shows a notice while preserving the full history. Every command
 * re-derives the actor and tenant from the guarded context; no clinical content or identifier
 * is placed in a notification fact.</p>
 *
 * <p>Invariant: a report revision is immutable. A correction points at a different revision
 * identifier and a withdrawal is terminal; the durable release service enforces both rules.</p>
 */
@Service
public class LabReportReleaseService {

    /** Audit purpose stamped on every lab release decision (OPS-02). */
    public static final String PURPOSE = "LAB_REPORT_RELEASE";
    /** Audit action for authorization. */
    public static final String AUTHORIZE = "LAB_REPORT_RELEASE_AUTHORIZE";
    /** Audit action for a correction. */
    public static final String CORRECT = "LAB_REPORT_RELEASE_CORRECT";
    /** Audit action for a withdrawal. */
    public static final String WITHDRAW = "LAB_REPORT_RELEASE_WITHDRAW";
    /** Audit action for a read. */
    public static final String READ = "LAB_REPORT_RELEASE_READ";
    /** Audit action for a history read. */
    public static final String HISTORY = "LAB_REPORT_RELEASE_HISTORY";

    private static final String RESOURCE_TYPE = "LAB_RELEASE";
    private static final String SCOPE = "LAB";
    private static final String CLINICIAN = "ordering-clinician";
    private static final String PATIENT = "linked-patient";
    private static final String HOLD_REASON = "facility-policy-review";

    private final ReleaseService releases;
    private final LabAccessionRepository accessions;
    private final AuditAppender audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Map<String, List<ViewerNotification>> notifications = new HashMap<>();

    /**
     * Create the service.
     *
     * @param releases     durable XCH-06 patient release service
     * @param accessions   RLS-bounded accession repository used to prove tenant ownership
     * @param audit        transactional audit appender
     * @param transactions shared tenant-guarded transaction template for reads and audit facts
     * @param clock        shared UTC clock for visibility decisions
     */
    public LabReportReleaseService(ReleaseService releases, LabAccessionRepository accessions,
                                   AuditAppender audit, TransactionTemplate transactions, Clock clock) {
        this.releases = releases;
        this.accessions = accessions;
        this.audit = audit;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Release an issued report revision: the clinician sees it at once, the patient by policy.
     *
     * @param command validated release command
     * @return the current release projection
     */
    public ReleasedReportView release(ReleaseReportCommand command) {
        TenantContext context = TenantContext.current();
        // The report identifier is a tenant-local specimen id. Prove it belongs to the
        // active tenant through the forced-RLS accession fold before authorizing a release;
        // a peer tenant's specimen is concealed as not found.
        boolean owned = Boolean.TRUE.equals(transactions.execute(status ->
                accessions.findById(command.reportId()).isPresent()));
        if (!owned) {
            throw new ApiException(404, "NOT_FOUND", "report not found in the active tenant");
        }
        Instant now = clock.instant();
        FacilityReleasePolicy policy = command.policy();
        String effectiveFrom = policy.patientKind() == ReleaseKind.TIMED
                ? policy.patientEffectiveFrom(now).toString() : null;
        String holdReason = policy.reviewRequired() ? HOLD_REASON : null;
        String holdExpiresAt = policy.reviewRequired() ? policy.patientEffectiveFrom(now).toString() : null;
        AuthorizeRelease authorization = new AuthorizeRelease(command.revisionId(), command.revisionVersion(),
                command.patientId(), policy.policyVersion(), policy.patientKind().name(),
                effectiveFrom, holdReason, holdExpiresAt);
        PatientRelease created = releases.authorize(authorization);
        emit(context, created.id(), command.revisionId(), CLINICIAN, "RELEASE_CLINICIAN", null, now);
        emit(context, created.id(), command.revisionId(), PATIENT, "RELEASE_PATIENT", null, now);
        appendAudit(context, AUTHORIZE, created.id());
        return ReleasedReportView.of(created, clock, null, null);
    }

    /**
     * Supersede a released revision with a corrected one and notify the earlier viewers.
     *
     * @param command validated correction command
     * @return the replacement release projection and correction context
     */
    public ReleasedReportView correct(CorrectReleaseCommand command) {
        TenantContext context = TenantContext.current();
        PatientRelease updated = releases.correct(command.releaseId(), command.expectedVersion(),
                command.correctedRevisionId(), command.correctedRevisionVersion(), command.reason());
        Instant now = clock.instant();
        Set<String> viewers = notifications.getOrDefault(command.releaseId(), List.of()).stream()
                .map(ViewerNotification::recipientScope)
                .collect(Collectors.toSet());
        if (viewers.isEmpty()) {
            viewers = Set.of(CLINICIAN, PATIENT);
        }
        for (String viewer : viewers) {
            emit(context, updated.id(), command.correctedRevisionId(), viewer, "CORRECTION_NOTICE",
                    command.reason(), now);
        }
        appendAudit(context, CORRECT, command.releaseId());
        String correctionContext = "corrected to " + command.correctedRevisionId() + " v"
                + command.correctedRevisionVersion() + ": " + command.reason();
        return ReleasedReportView.of(updated, clock, correctionContext, null);
    }

    /**
     * Withdraw a report as entered in error, show a notice and notify the earlier viewers.
     *
     * @param command validated withdrawal command
     * @return the withdrawn release projection
     */
    public ReleasedReportView withdraw(WithdrawReleaseCommand command) {
        TenantContext context = TenantContext.current();
        PatientRelease updated = releases.withdraw(command.releaseId(), command.expectedVersion(),
                command.reason());
        Instant now = clock.instant();
        emit(context, updated.id(), updated.revisionId(), CLINICIAN, "WITHDRAWAL_NOTICE",
                command.reason(), now);
        emit(context, updated.id(), updated.revisionId(), PATIENT, "WITHDRAWAL_NOTICE",
                command.reason(), now);
        appendAudit(context, WITHDRAW, command.releaseId());
        return ReleasedReportView.of(updated, clock, null, "Report withdrawn: " + command.reason());
    }

    /**
     * Read the current release with its PAT-02 notice and correction context.
     *
     * @param releaseId tenant-local release identifier
     * @return the projection
     */
    public ReleasedReportView read(String releaseId) {
        TenantContext context = TenantContext.current();
        PatientRelease found = releases.get(releaseId);
        appendAudit(context, READ, releaseId);
        String notice = found.state() == ReleaseState.WITHDRAWN ? "Report withdrawn" : null;
        String correctionContext = found.supersedesId() == null ? null : "corrected from " + found.supersedesId();
        return ReleasedReportView.of(found, clock, correctionContext, notice);
    }

    /**
     * Whether a projection must show a withdrawal notice to its reader.
     *
     * @param view projection to inspect
     * @return {@code true} when the report was withdrawn
     */
    public boolean isWithdrawn(ReleasedReportView view) {
        return view.withdrawalNotice() != null;
    }

    /**
     * Read the complete append-only history of a released report.
     *
     * @param releaseId tenant-local release identifier
     * @return release decisions followed by viewer notifications, in recorded order
     */
    public List<ReleaseHistoryEntry> history(String releaseId) {
        TenantContext context = TenantContext.current();
        List<ReleaseEvent> events = releases.history(releaseId);
        List<ReleaseHistoryEntry> entries = new ArrayList<>();
        for (ReleaseEvent event : events) {
            entries.add(ReleaseHistoryEntry.of(event));
        }
        for (ViewerNotification notification : notifications.getOrDefault(releaseId, List.of())) {
            entries.add(ReleaseHistoryEntry.of(notification));
        }
        appendAudit(context, HISTORY, releaseId);
        return List.copyOf(entries);
    }

    /**
     * Append this slice's audit fact in the tenant-guarded transaction so the RLS-bound
     * actor and organization are derived from the request context. The durable release
     * decisions themselves are written and audited by the release service in its own
     * transaction; this supplementary fact is separate so a deferred release failure
     * is not rolled back here.
     *
     * @param context    active tenant context
     * @param action     lab release audit action
     * @param resourceId addressed release identifier
     */
    private void appendAudit(TenantContext context, String action, String resourceId) {
        transactions.execute(status -> {
            audit.append(context, PURPOSE, action, RESOURCE_TYPE, SCOPE, resourceId, "SUCCESS");
            return null;
        });
    }

    private void emit(TenantContext context, String releaseId, String revisionId, String recipientScope,
                      String type, String reason, Instant occurredAt) {
        notifications.computeIfAbsent(releaseId, key -> new ArrayList<>())
                .add(new ViewerNotification(releaseId, revisionId, recipientScope, type, reason,
                        occurredAt, context.subject()));
    }
}
