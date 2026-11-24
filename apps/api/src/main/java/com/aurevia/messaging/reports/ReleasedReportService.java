package com.aurevia.messaging.reports;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;

/**
 * Patient-facing authorization for released report revisions (PAT-02, ARC-02, OPS-02).
 *
 * <p>Every list and download re-derives the active tenant and the verified chart link, then checks the
 * release status, effective date and current revision before returning a document reference. A
 * withdrawn, held, future-dated or superseded revision is never served. Every outcome with an
 * attributable tenant and subject, including authorization denials, is recorded as an audit event
 * in the caller's transaction boundary. An unauthenticated request has no attributable actor or
 * tenant and is rejected before an audit subject can be derived; input validation failures occur
 * before any resource is addressed.</p>
 *
 * <p>Each store read opens its own tenant-guarded transaction, so a withdrawal or
 * correction committed between two reads can change the outcome of an in-flight
 * download. A single-transaction authorization port for concurrent release
 * changes is a follow-up; sequential withdrawal stops the next download.</p>
 */
@Service
public final class ReleasedReportService {

    /** Audit action for listing released reports. */
    public static final String ACTION_LIST = "REPORT_LIST";
    /** Audit action for an authorized or attempted report download. */
    public static final String ACTION_DOWNLOAD = "REPORT_DOWNLOAD";
    /** Business purpose recorded on patient report access audit facts (OPS-02). */
    public static final String PURPOSE = "PATIENT_REPORT_ACCESS";
    /** Scope recorded on patient report access audit facts (OPS-02). */
    public static final String SCOPE = "PATIENT_REPORT";

    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{2,64}");

    private final ReportReleaseStore store;
    private final AuditSink audit;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param store report and link store
     * @param audit audit sink for access outcomes
     * @param clock injected clock for deterministic effective-date checks
     */
    public ReleasedReportService(ReportReleaseStore store, AuditSink audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * List the current, effective, released revisions visible to the linked patient.
     *
     * @return immutable list ordered by effective date descending, then identifier
     * @throws ApiException 401 without context, 403 without a verified active link
     */
    public List<ReleasedReportView> listFor() {
        TenantContext context = requireContext();
        PatientChartLink link = requireLink(context, ACTION_LIST, "LIST");
        List<ReleasedReportView> visible = store.reportsFor(context.organizationId(), link.patientId()).stream()
                .filter(report -> report.status() == ReleasedReport.Status.RELEASED)
                .filter(report -> !store.isSuperseded(context.organizationId(), report))
                .filter(report -> !isAfterToday(report.effectiveDate()))
                .sorted(Comparator.comparing(ReleasedReport::effectiveDate).reversed()
                        .thenComparing(ReleasedReport::id))
                .map(ReleasedReportView::from)
                .toList();
        audit.record(new AuditRecord(ACTION_LIST, link.patientId(), "SUCCESS", context.subject(),
                context.organizationId(), context.correlationId(), PURPOSE, SCOPE, clock.instant()));
        return visible;
    }

    /**
     * Authorize and return a document reference for one exact report revision.
     *
     * @param reportId report identifier
     * @param revision requested revision
     * @return the document reference for the immutable revision
     * @throws ApiException 401, 403, 404, 409, 410, or 422 as described by the acceptance matrix
     */
    public ReportDownload download(String reportId, int revision) {
        TenantContext context = requireContext();
        if (reportId == null || !ID_PATTERN.matcher(reportId).matches()) {
            throw new ApiException(422, "INVALID_REPORT_ID", "reportId must be a bounded identifier");
        }
        if (revision < 1) {
            throw new ApiException(422, "INVALID_REVISION", "revision must be positive");
        }
        PatientChartLink link = requireLink(context, ACTION_DOWNLOAD, reportId);
        ReleasedReport report = store.find(context.organizationId(), reportId, revision).orElse(null);
        if (report == null) {
            record(context, ACTION_DOWNLOAD, reportId, "NOT_FOUND");
            throw new ApiException(404, "NOT_FOUND", "report not found");
        }
        if (!link.patientId().equals(report.patientId())) {
            record(context, ACTION_DOWNLOAD, reportId, "DENIED");
            throw new ApiException(404, "NOT_FOUND", "report not found");
        }
        if (report.status() == ReleasedReport.Status.WITHDRAWN) {
            record(context, ACTION_DOWNLOAD, reportId, "WITHDRAWN");
            throw new ApiException(410, "RELEASE_WITHDRAWN", "the release was withdrawn");
        }
        if (report.status() == ReleasedReport.Status.HELD) {
            record(context, ACTION_DOWNLOAD, reportId, "HELD");
            throw new ApiException(403, "RELEASE_HELD", "the release is held");
        }
        if (isAfterToday(report.effectiveDate())) {
            record(context, ACTION_DOWNLOAD, reportId, "NOT_EFFECTIVE");
            throw new ApiException(403, "RELEASE_NOT_EFFECTIVE", "the release is not yet effective");
        }
        if (store.isSuperseded(context.organizationId(), report)) {
            record(context, ACTION_DOWNLOAD, reportId, "STALE");
            throw new ApiException(409, "STALE_REVISION", "a newer released revision exists");
        }
        int current = store.latestReleasedRevision(context.organizationId(), reportId, LocalDate.now(clock))
                .orElse(revision);
        if (current != revision) {
            record(context, ACTION_DOWNLOAD, reportId, "STALE");
            throw new ApiException(409, "STALE_REVISION", "a newer released revision exists");
        }
        record(context, ACTION_DOWNLOAD, reportId, "SUCCESS");
        return new ReportDownload(report.id(), report.revision(), report.documentId(), report.checksum(),
                report.language());
    }

    private boolean isAfterToday(String effectiveDate) {
        return LocalDate.parse(effectiveDate).isAfter(LocalDate.now(clock));
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private PatientChartLink requireLink(TenantContext context, String action, String resourceId) {
        PatientChartLink link = store.link(context.organizationId(), context.subject()).orElse(null);
        if (link == null || !link.verified() || !link.active()) {
            record(context, action, resourceId, "DENIED");
            throw new ApiException(403, "FORBIDDEN", "no verified patient chart link");
        }
        return link;
    }

    private void record(TenantContext context, String action, String resourceId, String outcome) {
        audit.record(new AuditRecord(action, resourceId, outcome, context.subject(),
                context.organizationId(), context.correlationId(), PURPOSE, SCOPE, clock.instant()));
    }

    /**
     * Audit fact for one patient report access (OPS-02).
     *
     * @param action         list or download action key
     * @param resourceId     addressed patient or report identifier
     * @param outcome        success, denied, not-found, held, withdrawn, stale or not-effective
     * @param subject        authenticated subject
     * @param organizationId active tenant
     * @param correlationId  request correlation identifier
     * @param purpose        business purpose of the access (OPS-02)
     * @param scope          audited scope of the access (OPS-02)
     * @param occurredAt     instant of the decision, from the injected clock (OPS-02)
     * @return the immutable audit fact
     */
    public record AuditRecord(String action, String resourceId, String outcome, String subject,
                              String organizationId, String correlationId, String purpose,
                              String scope, Instant occurredAt) {
    }

    /** Append-only audit sink, replaced by a durable adapter in the persistence layer. */
    @FunctionalInterface
    public interface AuditSink {
        /**
         * Append one audit fact.
         *
         * @param event the fact to append
         */
        void record(AuditRecord event);
    }

    /** Synthetic in-memory audit sink used until a durable adapter is wired. */
    @Component
    public static final class InMemoryAuditSink implements AuditSink {
        private final List<AuditRecord> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void record(AuditRecord event) {
            events.add(event);
        }

        /**
         * Return the recorded facts as an immutable snapshot.
         *
         * @return recorded audit facts in append order
         */
        public List<AuditRecord> events() {
            return List.copyOf(events);
        }
    }

    /**
     * The immutable document reference returned by an authorized download.
     *
     * @param reportId   report identifier
     * @param revision   authorized revision
     * @param documentId opaque rendered artifact identifier
     * @param checksum   artifact checksum for integrity verification
     * @param language   document language
     * @return the authorized document reference
     */
    public record ReportDownload(String reportId, int revision, String documentId, String checksum,
                                 String language) {
    }

    /**
     * The patient-visible projection of one released revision (PAT-02).
     *
     * @param reportId            report identifier
     * @param revision            released revision shown on screen
     * @param issuingOrganization organization shown as the issuer
     * @param effectiveDate       ISO civil effective date
     * @param language            document language
     * @param correctionReason    reason shown for a correction, or {@code null}
     * @param supersedesRevision  revision replaced by this correction, or {@code null}
     * @return the patient-visible projection
     */
    public record ReleasedReportView(String reportId, int revision, String issuingOrganization,
                                     String effectiveDate, String language, String correctionReason,
                                     Integer supersedesRevision) {

        /**
         * Project one released revision, exposing correction context without the document reference.
         *
         * Project one released revision.
         * @return the patient-visible view
         */
        static ReleasedReportView from(ReleasedReport report) {
            ReleasedReport.CorrectionContext correction = report.correction();
            return new ReleasedReportView(report.id(), report.revision(), report.issuingOrganization(),
                    report.effectiveDate(), report.language(),
                    correction == null ? null : correction.reason(),
                    correction == null ? null : correction.supersedesRevision());
        }
    }
}
