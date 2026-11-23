package com.aurevia.smartclinic.acceptance.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.events.DurableEventContract.DeadLetterEntry;
import com.aurevia.smartclinic.events.DurableEventContract.OutboxEvent;
import com.aurevia.smartclinic.events.DurableEventContract.PublishRequest;
import com.aurevia.smartclinic.events.DeliveryActions;
import com.aurevia.smartclinic.events.DurableEventService;
import com.aurevia.smartclinic.events.InMemoryDeliveryStore;
import com.aurevia.smartclinic.exchange.result.ResultActionPolicy;
import com.aurevia.smartclinic.exchange.result.ResultCommands.ImportResultRequest;
import com.aurevia.smartclinic.exchange.result.ResultModel.ExpectedOrder;
import com.aurevia.smartclinic.exchange.result.ResultModel.ImportOutcome;
import com.aurevia.smartclinic.exchange.result.ResultModel.RecipientNotification;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultIdentifiers;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultKind;
import com.aurevia.smartclinic.exchange.result.ResultModel.ResultReport;
import com.aurevia.smartclinic.exchange.result.ResultRepository;
import com.aurevia.smartclinic.exchange.result.ResultRoutingService;
import com.aurevia.smartclinic.messaging.reports.PatientChartLink;
import com.aurevia.smartclinic.messaging.reports.ReleasedReport;
import com.aurevia.smartclinic.messaging.reports.ReleasedReport.Status;
import com.aurevia.smartclinic.messaging.reports.ReleasedReportService;
import com.aurevia.smartclinic.messaging.reports.ReleasedReportService.InMemoryAuditSink;
import com.aurevia.smartclinic.messaging.reports.ReportReleaseStore;

/**
 * Cross-cutting acceptance for a withdrawn clinical document.
 *
 * <p>Requirement PAT-02: only a current, effective revision is served to a
 * linked patient. Spec section 11 "Retracted document": the withdrawn revision
 * stops being served while its disclosure history stays in the audit trail, a
 * correction notifies the original recipient list, and a failed outbound
 * delivery is exposed to the operator rather than silently discarded. All
 * identifiers and notes are synthetic.</p>
 */
class RetractedDocumentAcceptanceTest extends FirstSliceFixture {

    private static final String PATIENT_SUBJECT = "syn-patient-retraction";
    private static final String VERIFIER = "syn-lab-verifier";
    private static final String WORKER = "syn-delivery-worker";
    private static final String ADMIN = "syn-integration-admin";
    private static final String REPORT = "report-retracted";
    private static final String ORDER = "order-retracted";
    private static final String AGGREGATE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String DIGEST = "a".repeat(64);
    private static final String SOURCE = "lab-partner";
    private static final String ACCESSION = "ACC-RETRACTED";
    private static final String DEST = "clinic-a";
    private static final String ORIGINAL_OWNER = "owner-original";
    private static final String PREVIOUS_OWNER = "owner-previous";

    private ReportReleaseStore releaseStore;
    private InMemoryAuditSink auditSink;
    private ReleasedReportService patientService;
    private ResultRepository resultRepository;
    private ResultRoutingService routing;
    private InMemoryDeliveryStore deliveryStore;
    private DurableEventService events;

    /** Compose the patient projection, the result router and the delivery log. */
    @BeforeEach
    void setUpRetraction() {
        releaseStore = mock(ReportReleaseStore.class);
        auditSink = new InMemoryAuditSink();
        patientService = new ReleasedReportService(releaseStore, auditSink, clock());

        resultRepository = mock(ResultRepository.class);
        routing = new ResultRoutingService(resultRepository, new ResultActionPolicy(), transactions,
                audit, clock());

        deliveryStore = new InMemoryDeliveryStore();
        deliveryStore.addMember(CLINIC_A, WORKER, "worker", true);
        deliveryStore.addMember(CLINIC_A, ADMIN, "tenant_admin", true);
        events = new DurableEventService(deliveryStore, new DeliveryActions(), clock());
    }

    @Test
    void withdrawnRevisionStopsBeingServedButItsDisclosureHistoryIsRetained() {
        // PAT-02 / spec 11: withdrawal is terminal for patient access; the fact
        // of the earlier disclosure remains auditable and a second download is
        // refused with the same stable outcome code.
        bind(CLINIC_A, PATIENT_SUBJECT);
        ReleasedReport withdrawn = report(1, Status.WITHDRAWN, "2026-11-20");
        when(releaseStore.link(CLINIC_A, PATIENT_SUBJECT)).thenReturn(
                Optional.of(new PatientChartLink(PATIENT, true, true)));
        when(releaseStore.find(CLINIC_A, REPORT, 1)).thenReturn(Optional.of(withdrawn));
        when(releaseStore.reportsFor(CLINIC_A, PATIENT)).thenReturn(List.of(withdrawn));
        when(releaseStore.withdraw(CLINIC_A, REPORT, 1)).thenReturn(true);

        assertTrue(releaseStore.withdraw(CLINIC_A, REPORT, 1));
        ApiException first = assertThrows(ApiException.class, () -> patientService.download(REPORT, 1));
        assertEquals(410, first.status());
        assertEquals("RELEASE_WITHDRAWN", first.code());
        ApiException replayed = assertThrows(ApiException.class, () -> patientService.download(REPORT, 1));
        assertEquals(410, replayed.status());
        assertEquals("RELEASE_WITHDRAWN", replayed.code());
        assertTrue(patientService.listFor().isEmpty());
        assertTrue(auditSink.events().stream().anyMatch(event -> event.outcome().equals("WITHDRAWN")));
        verify(releaseStore, never()).latestReleasedRevision(anyString(), anyString(), any());
    }

    @Test
    void correctionKeepsTheOriginalRecipientsAndExposesTheFailedDelivery() {
        // Spec 11: the outbound correction task uses the recipient list from the
        // original revision, and a failed delivery is retained as an operator-
        // visible fact instead of disappearing with the withdrawn document.
        bind(CLINIC_A, VERIFIER);
        when(resultRepository.hasActiveMembership(VERIFIER)).thenReturn(true);
        when(resultRepository.activeRoles(VERIFIER)).thenReturn(List.of("lab_verifier", "clinician"));
        when(resultRepository.insertReport(any())).thenReturn(true);
        when(resultRepository.findOrder(ORDER)).thenReturn(Optional.of(order()));
        when(resultRepository.priorRecipients(ORDER)).thenReturn(
                List.of(ORIGINAL_OWNER, PREVIOUS_OWNER));
        when(resultRepository.findReview(anyString())).thenReturn(Optional.empty());
        when(resultRepository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of());
        when(resultRepository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.empty());

        ResultReport first = result(1, ResultKind.FINAL, null, null);
        ImportOutcome initial = routing.importResult(request("final", null, "checksum-1"));
        assertEquals(1, initial.report().version());

        when(resultRepository.lineage(ORDER, SOURCE, ACCESSION)).thenReturn(List.of(first));
        when(resultRepository.findReport(ORDER, SOURCE, ACCESSION, 1)).thenReturn(Optional.of(first));
        ImportOutcome corrected = routing.importResult(request("corrected", 1, "checksum-2"));
        assertEquals(2, corrected.report().version());
        assertEquals(ResultKind.CORRECTED, corrected.report().kind());
        assertEquals(first.id(), corrected.report().previousId());

        ArgumentCaptor<RecipientNotification> notifications =
                ArgumentCaptor.forClass(RecipientNotification.class);
        verify(resultRepository, times(2)).insertNotification(notifications.capture());
        assertEquals(List.of(ORIGINAL_OWNER, PREVIOUS_OWNER),
                notifications.getAllValues().stream().map(RecipientNotification::recipient).toList());
        assertTrue(notifications.getAllValues().stream()
                .allMatch(notification -> notification.reportId().equals(corrected.report().id())));

        bind(CLINIC_A, WORKER);
        OutboxEvent published = events.publish(new PublishRequest(AGGREGATE, 1, SOURCE, "report.corrected",
                "lab-partner", DIGEST, "correction-" + first.id()));
        OutboxEvent sent = events.markSent(published.eventId(), "1");
        OutboxEvent failed = events.markFailed(sent.eventId(), "2", "synthetic partner unavailable");
        DeadLetterEntry dead = events.deadLetter(failed.eventId(), "synthetic partner unavailable");
        assertEquals(published.eventId(), dead.eventId());
        assertEquals(CLINIC_A, dead.organizationId());
        assertEquals("synthetic partner unavailable", dead.reason());
        assertTrue(dead.attempt() >= 0);
        bind(CLINIC_A, ADMIN);
        assertTrue(events.deadLetters().stream()
                .anyMatch(entry -> entry.eventId().equals(published.eventId())));
    }

    private static ReleasedReport report(int revision, Status status, String effectiveDate) {
        return new ReleasedReport(REPORT, revision, PATIENT, CLINIC_A, "Synthetic clinic", effectiveDate,
                "en", "document-" + revision, "checksum-" + revision, status, null);
    }

    private static ExpectedOrder order() {
        return new ExpectedOrder(ORDER, ids(), ORIGINAL_OWNER, "coverage-queue");
    }

    private static ResultIdentifiers ids() {
        return new ResultIdentifiers(PATIENT, SOURCE, ACCESSION, DEST, ORDER);
    }

    private static ResultReport result(int version, ResultKind kind, Integer supersedes, String previousId) {
        String id = version == 1 ? "report-original" : "report-corrected";
        return new ResultReport(id, version, ORDER, ids(), kind, supersedes, previousId,
                ORIGINAL_OWNER, "coverage-queue", NOW, NOW, "checksum-1");
    }

    private static ImportResultRequest request(String kind, Integer supersedes, String checksum) {
        return new ImportResultRequest(ORDER, PATIENT, SOURCE, ACCESSION, DEST, kind, supersedes,
                NOW.toString(), checksum);
    }
}
